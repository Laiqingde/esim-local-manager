package com.gao.esimlocal

import android.content.Context

/**
 * 原生 eSIM 解锁：root(resetprop) 临时改设备区域标识，绕过三星 LPA 的国行限制，
 * 让系统原生「添加 eSIM」可加境外卡、且解除「最多 2 张」数量上限。
 * 全部为 runtime 改动，重启手机自动还原；也可随时一键恢复。
 *
 * 判定依据（来自三星 LPA 反编译 EuiccService/TelephonyUI）：
 *  - 硬件硬闸 isRestrictedProfileDownloadForChinaHW()
 *      = ("CHC"==ro.boot.carrierid) && ("CN"!=ro.csc.countryiso_code)
 *  - 下载 TLS 证书 isCHNModel() = ("CHC"==ro.csc.sales_code) → 为真则按 GPS 选中国证书
 *  - eSIM 数量上限 = (ro.csc.countryiso_code=="CN") ? 2 : 20
 *  - isNotAllowCondition = userBinary && development_settings_enabled
 *
 * 纯国行(ro.boot.carrierid=CHC)必须连 carrierid 一起改，否则把 countryiso 改成非 CN
 * 反而会触发硬件硬闸。台版刷国行(carrierid=BRI 等)则无需动 carrierid。
 */
object NativeUnlock {
    private const val UNLOCK_SALES = "TGY"      // 非 CHC
    private const val UNLOCK_ISO = "HK"         // 非 CN
    private const val UNLOCK_CARRIER = "BRI"    // 非 CHC（仅纯国行需要）
    private const val ORIG_SALES = "CHC"
    private const val ORIG_ISO = "CN"
    private const val PREF = "unlock"
    private const val KEY_ORIG_CARRIER = "orig_carrier"

    private fun sh(cmd: String): String {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.trim()
    }

    private fun rp(): String {
        for (c in listOf("/data/adb/ksu/bin/resetprop", "/data/adb/magisk/resetprop", "resetprop")) {
            if (sh("[ -x $c ] && echo ok").contains("ok") ||
                sh("command -v $c >/dev/null 2>&1 && echo ok").contains("ok")) return c
        }
        return "resetprop"
    }

    fun getProp(name: String) = sh("getprop $name")

    fun hasRoot(): Boolean = try { sh("id").contains("uid=0") } catch (e: Exception) { false }

    /** countryiso 非 CN 即视为已解锁 */
    fun isUnlocked(): Boolean {
        val iso = getProp("ro.csc.countryiso_code")
        return iso.isNotEmpty() && !iso.equals(ORIG_ISO, true)
    }

    fun unlock(ctx: Context): Boolean {
        val resetprop = rp()
        val carrier = getProp("ro.boot.carrierid")
        // 记住原始 carrierid 以便精确还原
        ctx.getSharedPreferences(PREF, 0).edit().putString(KEY_ORIG_CARRIER, carrier).apply()
        val sb = StringBuilder()
        sb.append("$resetprop ro.csc.sales_code $UNLOCK_SALES; ")
        sb.append("$resetprop ro.boot.sales_code $UNLOCK_SALES; ")
        sb.append("$resetprop ro.csc.countryiso_code $UNLOCK_ISO; ")
        // 纯国行：carrierid=CHC 时必须一起改，否则触发硬件硬闸
        if (carrier.equals("CHC", true))
            sb.append("$resetprop ro.boot.carrierid $UNLOCK_CARRIER; ")
        sb.append("settings put global development_settings_enabled 0; ")
        sb.append("settings put secure development_settings_enabled 0; ")
        sb.append("am force-stop com.samsung.android.app.telephonyui; ")
        sb.append("am force-stop com.android.settings; ")
        sb.append("pkill -f com.samsung.euicc")
        sh(sb.toString())
        return isUnlocked()
    }

    fun restore(ctx: Context): Boolean {
        val resetprop = rp()
        val origCarrier = ctx.getSharedPreferences(PREF, 0).getString(KEY_ORIG_CARRIER, "") ?: ""
        val sb = StringBuilder()
        sb.append("$resetprop ro.csc.sales_code $ORIG_SALES; ")
        sb.append("$resetprop ro.boot.sales_code $ORIG_SALES; ")
        sb.append("$resetprop ro.csc.countryiso_code $ORIG_ISO; ")
        // 只有当初改过 carrierid（原始是 CHC）才还原回 CHC
        if (origCarrier.equals("CHC", true))
            sb.append("$resetprop ro.boot.carrierid CHC; ")
        sb.append("am force-stop com.samsung.android.app.telephonyui; ")
        sb.append("am force-stop com.android.settings; ")
        sb.append("pkill -f com.samsung.euicc")
        sh(sb.toString())
        return !isUnlocked()
    }

    fun status(): String {
        val sales = getProp("ro.csc.sales_code")
        val iso = getProp("ro.csc.countryiso_code")
        val carrier = getProp("ro.boot.carrierid")
        return "sales=$sales  iso=$iso  carrier=$carrier"
    }
}
