package com.gao.esimlocal

import android.content.Context
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** eUICC 操作结果 */
data class LpacResult(val ok: Boolean, val data: Any?, val message: String, val raw: String)

object Lpac {
    @Volatile var slot: Int = -1            // 自动探测后缓存
    private const val BIN = "lpac"
    private val slotCandidates = listOf(2, 1, 0, 3)

    private fun binDir(ctx: Context) = File(ctx.filesDir, "bin")

    /** 首次运行：把 assets/bin 释放到 filesDir/bin 并 chmod */
    fun install(ctx: Context) {
        val dir = binDir(ctx)
        val drv = File(dir, "driver")
        dir.mkdirs(); drv.mkdirs()
        val am = ctx.assets
        for (name in am.list("bin")!!) {
            if (name == "driver") continue
            copyAsset(ctx, "bin/$name", File(dir, name))
        }
        for (name in am.list("bin/driver")!!) {
            copyAsset(ctx, "bin/driver/$name", File(drv, name))
        }
        File(dir, BIN).setExecutable(true, false)
    }

    private fun copyAsset(ctx: Context, assetPath: String, out: File) {
        ctx.assets.open(assetPath).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        out.setReadable(true, false)
        out.setExecutable(true, false)
    }

    /** 以 root 执行 lpac（非下载类），返回 stdout 全文 */
    private fun runRoot(ctx: Context, args: String, slotOverride: Int? = null): String {
        val dir = binDir(ctx).absolutePath
        val s = slotOverride ?: slot
        val cmd = "cd $dir && export LD_LIBRARY_PATH=$dir && export LPAC_APDU=qrtr && " +
                "export LPAC_HTTP=stdio && export LPAC_APDU_QRTR_SLOT=$s && ./$BIN $args"
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(false).start()
        val out = p.inputStream.bufferedReader().readText()
        p.errorStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    fun hasRoot(): Boolean = try {
        val p = ProcessBuilder("su", "-c", "id").start()
        p.waitFor() == 0 && p.inputStream.bufferedReader().readText().contains("uid=0")
    } catch (e: Exception) { false }

    /** 自动探测 eUICC 所在 slot */
    fun detectSlot(ctx: Context): Int {
        for (c in slotCandidates) {
            val out = runRoot(ctx, "chip info", c)
            val j = lastJson(out)
            if (j != null && j.optInt("code", -1) == 0) {
                slot = c; return c
            }
        }
        return -1
    }

    fun chipInfo(ctx: Context): LpacResult = parse(runRoot(ctx, "chip info"))
    fun profileList(ctx: Context): LpacResult = parse(runRoot(ctx, "profile list"))
    // 第二个参数 1 = refreshFlag，让 eUICC 向 modem 发 REFRESH，基带重读后系统 SIM 管理器才会刷新
    fun enable(ctx: Context, iccid: String): LpacResult = parse(runRoot(ctx, "profile enable $iccid 1"))
    fun disable(ctx: Context, iccid: String): LpacResult = parse(runRoot(ctx, "profile disable $iccid 1"))
    fun delete(ctx: Context, iccid: String): LpacResult = parse(runRoot(ctx, "profile delete $iccid"))
    fun nickname(ctx: Context, iccid: String, nick: String): LpacResult =
        parse(runRoot(ctx, "profile nickname $iccid '${nick.replace("'", "")}'"))
    fun processNotifications(ctx: Context): LpacResult = parse(runRoot(ctx, "notification process -a"))

    /**
     * 下载 profile：lpac 走 stdio HTTP 后端，HTTPS 请求由本函数用 OkHttp 代发。
     * onLog 回调用于把进度打到界面。
     */
    fun download(ctx: Context, activationCode: String, onLog: (String) -> Unit): LpacResult {
        val dir = binDir(ctx).absolutePath
        // 激活码写文件，规避 shell 引号/$ 问题
        File(binDir(ctx), "code.txt").writeText(activationCode.trim())
        val cmd = "cd $dir && export LD_LIBRARY_PATH=$dir && export LPAC_APDU=qrtr && " +
                "export LPAC_HTTP=stdio && export LPAC_APDU_QRTR_SLOT=$slot && " +
                "export LPAC_HTTP_SSL_VERIFY=false && " +
                "CODE=\$(cat $dir/code.txt) && ./$BIN profile download -a \"\$CODE\""
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(false).start()
        val reader = BufferedReader(InputStreamReader(p.inputStream))
        val writer = OutputStreamWriter(p.outputStream)
        // 把 stderr 异步打到日志
        Thread {
            p.errorStream.bufferedReader().forEachLine { if (it.isNotBlank()) onLog("· $it") }
        }.start()

        val http = insecureHttpClient()
        var last = ""
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            last = line
            val msg = try { JSONObject(line) } catch (e: Exception) { onLog(line); continue }
            when (msg.optString("type")) {
                "http" -> {
                    val payload = msg.getJSONObject("payload")
                    val resp = doHttp(http, payload, onLog)
                    writer.write(resp); writer.write("\n"); writer.flush()
                }
                "progress" -> onLog("进度: " + msg.optJSONObject("payload")?.optString("message"))
                "lpa" -> {
                    val pl = msg.optJSONObject("payload")
                    onLog("结果: " + (pl?.optString("message") ?: line))
                }
                else -> onLog(line)
            }
        }
        p.waitFor()
        return parse(last)
    }

    /**
     * 信任全部证书的 OkHttp 客户端。SM-DP+ 的证书链常不在 Android 系统信任库里；
     * eSIM 下载的真正鉴权由 eUICC 的 ES9+ 签名保证，传输层 TLS 放宽与 lpac 默认
     * LPAC_HTTP_SSL_VERIFY=false 一致。
     */
    private fun insecureHttpClient(): OkHttpClient {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), java.security.SecureRandom()) }
        return OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private fun doHttp(http: OkHttpClient, payload: JSONObject, onLog: (String) -> Unit): String {
        val url = payload.getString("url")
        val txHex = payload.optString("tx", "")
        val body = hexToBytes(txHex)
        val b = Request.Builder().url(url)
        // Content-Type 必须用 lpac 给的（SM-DP+ 要求 application/json）；
        // 其它 header 照传，但不要重复加 Content-Type，交给 RequestBody 的 mediaType
        var contentType = "application/json"
        val headers = payload.optJSONArray("headers")
        if (headers != null) for (i in 0 until headers.length()) {
            val h = headers.getString(i); val idx = h.indexOf(':')
            if (idx <= 0) continue
            val k = h.substring(0, idx).trim(); val v = h.substring(idx + 1).trim()
            if (k.equals("Content-Type", ignoreCase = true)) contentType = v
            else b.addHeader(k, v)
        }
        b.post(body.toRequestBody(contentType.toMediaTypeOrNull()))
        return try {
            http.newCall(b.build()).execute().use { r ->
                val rb = r.body?.bytes() ?: ByteArray(0)
                onLog("[HTTP ${r.code}] $url")
                // ES9+ 响应体是 JSON，出错原因在此；打印出来便于诊断
                val text = try { String(rb, Charsets.UTF_8) } catch (e: Exception) { "" }
                if (text.contains("functionExecutionStatus") || text.contains("statusCode")) {
                    onLog("↳ 响应: " + text.take(600))
                }
                JSONObject().put("type", "http").put(
                    "payload", JSONObject().put("rcode", r.code).put("rx", bytesToHex(rb))
                ).toString()
            }
        } catch (e: Exception) {
            onLog("[HTTP 错误] ${e.message}")
            JSONObject().put("type", "http").put(
                "payload", JSONObject().put("rcode", 0).put("rx", "")
            ).toString()
        }
    }

    // ---- 解析 lpac 输出 ----
    private fun lastJson(out: String): JSONObject? {
        var res: JSONObject? = null
        out.lineSequence().forEach { l ->
            val t = l.trim()
            if (t.startsWith("{")) try {
                val j = JSONObject(t)
                if (j.optString("type") == "lpa") res = j.optJSONObject("payload")
            } catch (_: Exception) {}
        }
        return res
    }

    private fun parse(out: String): LpacResult {
        val pl = lastJson(out)
            ?: return LpacResult(false, null, "无有效响应（检查 root / slot）", out)
        val ok = pl.optInt("code", -1) == 0
        return LpacResult(ok, pl.opt("data"), pl.optString("message"), out)
    }

    private fun hexToBytes(h: String): ByteArray {
        if (h.isEmpty()) return ByteArray(0)
        val out = ByteArray(h.length / 2)
        var i = 0
        while (i < out.size) { out[i] = ((Character.digit(h[i*2],16) shl 4) + Character.digit(h[i*2+1],16)).toByte(); i++ }
        return out
    }
    private fun bytesToHex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) sb.append("%02x".format(x))
        return sb.toString()
    }
}
