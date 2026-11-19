package com.gao.esimlocal

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var sw: Switch
    private lateinit var state: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sw = findViewById(R.id.switchUnlock)
        state = findViewById(R.id.state)
        findViewById<Button>(R.id.btnOpenSim).setOnClickListener { openSimSettings() }
        refreshState()
    }

    override fun onResume() { super.onResume(); refreshState() }

    private fun refreshState() {
        lifecycleScope.launch {
            if (!withContext(Dispatchers.IO) { NativeUnlock.hasRoot() }) {
                state.text = "未获得 root 权限。本功能需要 root（Magisk / KernelSU / APatch）。"
                sw.isEnabled = false
                return@launch
            }
            val on = withContext(Dispatchers.IO) { NativeUnlock.isUnlocked() }
            val st = withContext(Dispatchers.IO) { NativeUnlock.status() }
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = on
            sw.isEnabled = true
            renderState(on, st)
            sw.setOnCheckedChangeListener { _, checked -> apply(checked) }
        }
    }

    private fun apply(checked: Boolean) {
        sw.isEnabled = false
        state.text = if (checked) "正在解锁…" else "正在恢复…"
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (checked) NativeUnlock.unlock(this@MainActivity)
                else NativeUnlock.restore(this@MainActivity)
            }
            val st = withContext(Dispatchers.IO) { NativeUnlock.status() }
            renderState(checked, st)
            sw.isEnabled = true
            if (ok != checked) state.append("\n⚠️ 状态未如预期，请重试或查看说明。")
        }
    }

    private fun renderState(on: Boolean, st: String) {
        state.text = if (on)
            "✅ 已解锁\n$st\n\n现在去「设置 → 连接 → SIM 管理器 → 添加 eSIM」即可添加境外 eSIM，" +
            "数量上限也已解除。\n\n注意：重启手机后自动失效，重新打开开关即可。"
        else
            "已恢复国行区域（关闭状态）\n$st"
    }

    private fun openSimSettings() {
        val tries = listOf(
            Intent("android.settings.MANAGE_ALL_SIM_PROFILES_SETTINGS"),
            Intent().setClassName("com.android.settings",
                "com.android.settings.Settings\$SimCardManagementActivity"),
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (i in tries) {
            try { startActivity(i); return } catch (e: Exception) {}
        }
    }
}
