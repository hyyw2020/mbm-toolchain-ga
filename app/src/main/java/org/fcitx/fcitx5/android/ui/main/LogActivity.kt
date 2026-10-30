/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.view.Menu
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.databinding.ActivityLogBinding
import org.fcitx.fcitx5.android.ui.main.log.LogView
import org.fcitx.fcitx5.android.utils.DeviceInfo
import org.fcitx.fcitx5.android.utils.Logcat
import org.fcitx.fcitx5.android.utils.iso8601UTCDateTime
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.toast
import splitties.resources.styledColor
import splitties.views.topPadding

class LogActivity : AppCompatActivity() {

    private var fromCrash = false

    private lateinit var launcher: ActivityResultLauncher<String>
    private lateinit var logView: LogView
    /** 保存当前使用的 Logcat 实例，供导出时抓快照兜底 */
    private var activeLogcat: Logcat? = null

    /** N47：采集是否在跑（退后台要停，回前台要恢复，避免后台一直攒日志 → OOM） */
    private var logcatRunning = false

    /**
     * N47：启动采集。Logcat 的 logcat 子进程关掉后无法在同一实例上重启
     * （process 字段不复位，再 initLogFlow 会抛异常），所以恢复时重建实例。
     */
    private fun startLogcat() {
        activeLogcat?.shutdownLogFlow()
        activeLogcat = if (fromCrash) Logcat(FcitxApplication.getLastPid()) else Logcat()
        logView.setLogcat(activeLogcat!!)
        logcatRunning = true
    }

    private fun registerLauncher() {
        launcher = registerForActivityResult(CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        // 修复（docs/pitfalls.md #7）：实时流为空时（空行崩溃 / ROM 限制 logcat），
                        // 现场抓一次快照兜底，确保导出文件含正文；若仍为空则写明原因，
                        // 引导用户改用电脑 adb logcat 抓取。
                        val live = logView.currentLog
                        val snapshot = if (live.isBlank()) {
                            activeLogcat?.snapshot()?.getOrElse { emptyList() }
                                ?: emptyList()
                        } else emptyList()
                        contentResolver.openOutputStream(uri)!!.use { stream ->
                            stream.bufferedWriter().use { writer ->
                                writer.write(DeviceInfo.get(this@LogActivity))
                                when {
                                    snapshot.isNotEmpty() -> {
                                        writer.write("--------- logcat snapshot（实时流为空，快照兜底）\n")
                                        snapshot.forEach { writer.appendLine(it) }
                                    }
                                    live.isNotBlank() -> writer.write(live)
                                    else -> writer.write(
                                        "\n--------- 警告：未读到任何 logcat 输出。可能原因：" +
                                            "本机 ROM 限制了应用读取日志缓冲区，或缓冲区已被清空。" +
                                            "请用电脑执行 `adb logcat` 抓取（需开启开发者选项与 USB 调试）。\n"
                                    )
                                }
                            }
                        }
                    }
                }.let { toast(it) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val binding = ActivityLogBinding.inflate(layoutInflater)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            binding.root.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            binding.toolbar.topPadding = statusBars.top
            binding.logView.setBottomPadding(navBars.bottom)
            windowInsets
        }
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        this@LogActivity.logView = binding.logView
        if (intent.hasExtra(FROM_CRASH)) {
            fromCrash = true
            supportActionBar!!.setTitle(R.string.crash_logs)
            AlertDialog.Builder(this@LogActivity)
                .setTitle(R.string.app_crash)
                .setMessage(R.string.app_crash_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            logView.append("--------- Crash stacktrace")
            logView.append(intent.getStringExtra(CRASH_STACK_TRACE) ?: "<empty>")
            startLogcat()
        } else {
            supportActionBar!!.setDisplayHomeAsUpEnabled(true)
            supportActionBar!!.setTitle(R.string.real_time_logs)
            startLogcat()
        }
        registerLauncher()
    }

    override fun onResume() {
        super.onResume()
        // N47：回到前台恢复采集（onCreate 里已启动的不会重复启动）
        if (!logcatRunning) startLogcat()
    }

    override fun onPause() {
        // N47：页面不可见（退后台 / 被其它页面盖住）即停掉 logcat 子进程与采集协程，
        // 不再往日志集合里堆行；日志条数本身另有 LogAdapter.MAX_ENTRIES 兜底。
        activeLogcat?.shutdownLogFlow()
        logcatRunning = false
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val iconTint = styledColor(android.R.attr.colorControlNormal)
        if (!fromCrash) {
            menu.item(R.string.clear, R.drawable.ic_baseline_delete_24, iconTint, true) {
                logView.clear()
            }
        }
        menu.item(R.string.export, R.drawable.ic_baseline_save_24, iconTint, true) {
            launcher.launch("$packageName-${iso8601UTCDateTime()}.txt")
        }
        return true
    }

    companion object {
        const val FROM_CRASH = "from_crash"
        const val CRASH_STACK_TRACE = "crash_stack_trace"
    }
}
