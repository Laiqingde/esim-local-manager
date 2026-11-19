package com.gao.esimlocal

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var header: TextView
    private lateinit var list: LinearLayout
    private lateinit var log: TextView
    private lateinit var logScroll: ScrollView

    private val scan = registerForActivityResult(ScanContract()) { r ->
        if (r.contents != null) startDownload(r.contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        header = findViewById(R.id.header)
        list = findViewById(R.id.list)
        log = findViewById(R.id.log)
        logScroll = findViewById(R.id.logScroll)
        findViewById<Button>(R.id.btnRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnDownload).setOnClickListener { askCode() }

        Lpac.install(this)
        refresh()
    }

    private fun logln(s: String) {
        try { java.io.File(filesDir, "applog.txt").appendText(s + "\n") } catch (_: Exception) {}
        runOnUiThread {
            log.append(s + "\n")
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun refresh() {
        header.text = "正在连接 eUICC…"
        list.removeAllViews()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (!Lpac.hasRoot()) return@withContext "NOROOT"
                if (Lpac.slot < 0 && Lpac.detectSlot(this@MainActivity) < 0) return@withContext "NOSLOT"
                "OK"
            }
            if (ok == "NOROOT") { header.text = "未获得 root 权限，无法操作"; return@launch }
            if (ok == "NOSLOT") { header.text = "未找到 eUICC（QRTR/slot 探测失败）"; return@launch }

            var chip = withContext(Dispatchers.IO) { Lpac.chipInfo(this@MainActivity) }
            // 缓存的 slot 失效（如 MEP 端口变化）时，重新探测后再读一次
            if (!chip.ok) {
                header.text = "slot 变化，重新探测…"
                val s = withContext(Dispatchers.IO) { Lpac.slot = -1; Lpac.detectSlot(this@MainActivity) }
                if (s >= 0) chip = withContext(Dispatchers.IO) { Lpac.chipInfo(this@MainActivity) }
            }
            if (chip.ok && chip.data is JSONObject) {
                val d = chip.data as JSONObject
                val eid = d.optString("eidValue")
                val free = d.optJSONObject("EUICCInfo2")?.optJSONObject("extCardResource")
                    ?.optInt("freeNonVolatileMemory") ?: -1
                header.text = "slot ${Lpac.slot}  ·  EID ${eid.takeLast(12)}\n剩余空间 ${if(free>=0) "${free/1024} KB" else "?"}"
            } else header.text = "读取芯片失败: ${chip.message}"

            val pl = withContext(Dispatchers.IO) { Lpac.profileList(this@MainActivity) }
            renderProfiles(pl.data as? JSONArray)
        }
    }

    private fun renderProfiles(arr: JSONArray?) {
        list.removeAllViews()
        if (arr == null || arr.length() == 0) {
            val t = TextView(this); t.text = "（无 profile）"; t.setPadding(24,24,24,24)
            list.addView(t); return
        }
        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            list.addView(profileCard(p))
        }
    }

    private fun profileCard(p: JSONObject): View {
        val iccid = p.optString("iccid")
        val state = p.optString("profileState").lowercase()
        val enabled = state == "enabled" || state == "1"
        val nick = p.optString("profileNickname").takeIf { it.isNotEmpty() && it != "null" }
        val spn = p.optString("serviceProviderName")
        val pname = p.optString("profileName")
        val title = nick ?: pname.ifEmpty { spn.ifEmpty { iccid } }

        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(28, 24, 28, 24)
        card.setBackgroundColor(if (enabled) 0xFFE8F5E9.toInt() else 0xFFF5F5F5.toInt())
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 0, 0, 16); card.layoutParams = lp

        val tv = TextView(this)
        tv.text = "${if (enabled) "● 已启用" else "○ 未启用"}  $title\n$spn\nICCID $iccid"
        tv.textSize = 14f
        card.addView(tv)

        val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL
        fun btn(label: String, f: () -> Unit): Button {
            val b = Button(this); b.text = label; b.textSize = 12f
            b.setOnClickListener { f() }
            val blp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            b.layoutParams = blp; return b
        }
        if (enabled) row.addView(btn("停用") { op("停用") { Lpac.disable(this, iccid) } })
        else row.addView(btn("启用") { op("启用") { Lpac.enable(this, iccid) } })
        row.addView(btn("改名") { askNick(iccid) })
        row.addView(btn("删除") { confirmDelete(iccid, title) })
        card.addView(row)
        return card
    }

    private fun op(name: String, f: () -> LpacResult) {
        logln("$name…")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { f() }
            logln("$name ${if (r.ok) "成功" else "失败: ${r.message}"}")
            if (r.ok && (name == "启用" || name == "停用"))
                withContext(Dispatchers.IO) { Lpac.processNotifications(this@MainActivity) }
            refresh()
        }
    }

    private fun confirmDelete(iccid: String, title: String) {
        AlertDialog.Builder(this).setTitle("删除 profile")
            .setMessage("确定删除「$title」？此操作不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                op("删除") {
                    val r = Lpac.delete(this, iccid)
                    if (r.ok) Lpac.processNotifications(this)
                    r
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun askNick(iccid: String) {
        val input = EditText(this); input.hint = "新昵称"
        AlertDialog.Builder(this).setTitle("修改昵称").setView(input)
            .setPositiveButton("确定") { _, _ ->
                op("改名") { Lpac.nickname(this, iccid, input.text.toString()) }
            }.setNegativeButton("取消", null).show()
    }

    private fun askCode() {
        val box = LinearLayout(this); box.orientation = LinearLayout.VERTICAL; box.setPadding(40,24,40,0)
        val input = EditText(this)
        input.hint = "LPA:1\$地址\$MatchingID"
        input.inputType = InputType.TYPE_CLASS_TEXT
        box.addView(input)
        val scanBtn = Button(this); scanBtn.text = "扫码"; box.addView(scanBtn)

        val dlg = AlertDialog.Builder(this).setTitle("下载 eSIM").setView(box)
            .setPositiveButton("下载") { _, _ -> startDownload(input.text.toString()) }
            .setNegativeButton("取消", null).create()
        scanBtn.setOnClickListener {
            dlg.dismiss()
            scan.launch(ScanOptions().setOrientationLocked(false).setPrompt("对准 eSIM 二维码"))
        }
        dlg.show()
    }

    private fun startDownload(code: String) {
        if (code.isBlank()) { logln("激活码为空"); return }
        logln("开始下载: $code")
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { Lpac.download(this@MainActivity, code) { logln(it) } }
            logln(if (r.ok) "✅ 下载完成" else "❌ 下载失败: ${r.message}")
            if (r.ok) withContext(Dispatchers.IO) { Lpac.processNotifications(this@MainActivity) }
            // eUICC 刚下载完还在收尾，等一下再刷新，避免读到空列表
            logln("等待 eUICC 就绪…")
            kotlinx.coroutines.delay(2000)
            refresh()
        }
    }
}
