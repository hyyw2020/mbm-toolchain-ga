/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 进程退出归因（MBM v1.0.26 / P1-4）。
 *
 * 输入法进程被杀有太多种原因，用户只知道「键盘没了」：
 *   - 自己崩溃（Java/Kotlin 未捕获异常）—— 已有 FcitxApplication 的
 *     UncaughtExceptionHandler + LogActivity(FROM_CRASH) 链路；
 *   - 原生崩溃（librime/libime 段错误、JNI abort）—— 不会走 Java 的
 *     UncaughtExceptionHandler，此前完全无线索；
 *   - 系统 LMK 杀掉、ANR 后被系统杀、用户手动清除 —— 同样无线索。
 *
 * Android 11（API 30）起 `ActivityManager#getHistoricalProcessExitReasons`
 * 把上面这些全部按 pid 记录在案。本类在上一次进程退出后、新进程启动时读取，
 * 落一份**脱敏**的归因日志，与 v1.0.25 的 Java 崩溃链互补，把
 * 「键盘突然消失」从玄学变成可诊断。
 *
 * 隐私边界（与 CrashLogSanitizer 同一套规则）：
 *  - description / trace 字段一律过 [CrashLogSanitizer]；
 *  - 只写进程级元数据（reason/status/pss/rss/importance/timestamp），
 *    不写任何用户内容、不写窗口内容、不写输入连接快照。
 */
package org.fcitx.fcitx5.android.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import org.fcitx.fcitx5.android.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ProcessExitAttributor {

    private const val LOG_FILE_NAME = "crash_attribution.log"
    private const val MAX_FILE_BYTES = 64 * 1024
    private const val MAX_ENTRIES = 8

    private val TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** ApplicationExitInfo.REASON_* → 人话 */
    private fun reasonToString(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF(主动退出)"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED(收到信号/原生崩溃)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY(系统低内存杀)"
        ApplicationExitInfo.REASON_ANR -> "ANR(无响应后被杀)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH(原生崩溃 abort)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE(原生崩溃)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE(初始化失败)"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE(权限被撤销)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE(资源超限)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED(用户手动停止)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED(用户强制停止)"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED(依赖进程死亡)"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER(被缓存冻结后杀)"
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED(包更新)"
        else -> "REASON_$reason"
    }

    /**
     * 读取指定 pid 的历史退出原因并格式化为可读文本。
     *
     * @param pid 上一次进程的 pid；传 0 表示不按 pid 过滤（取本包最近的若干条）
     * @return 纯文本；无记录时返回空串
     */
    fun dump(context: Context, pid: Int = 0, max: Int = MAX_ENTRIES): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return ""
        val reasons = runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, pid, max)
        }.getOrNull() ?: return ""
        if (reasons.isEmpty()) return ""
        return reasons.joinToString("\n") { info ->
            buildString {
                append("---- exit @ ").append(TIME_FORMAT.format(Date(info.timestamp)))
                append(" pid=").append(info.pid)
                append(" process=").append(info.processName)
                append(" reason=").append(reasonToString(info.reason))
                append(" status=").append(info.status)
                append(" description=").append(sanitize(info.description))
                append(" importance=").append(info.importance)
                append(" pss=").append(info.pss)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    append(" rssKb=").append(info.rss)
                }
            }
        }
    }

    /**
     * 把上一次进程的退出归因追加到 `files/crash_attribution.log`。
     * 文件超过 [MAX_FILE_BYTES] 时整体重写为最新一条，避免无限增长。
     */
    fun appendToFile(context: Context, pid: Int): Boolean {
        // dump() 无记录时返回空串（非 null），这里判空避免写入只有标题、没有正文的空条目
        val text = dump(context, pid).takeIf { it.isNotBlank() } ?: return false
        val file = File(context.filesDir, LOG_FILE_NAME)
        val header = "===== ${TIME_FORMAT.format(Date())} v${BuildConfig.VERSION_NAME} " +
            "lastPid=$pid =====\n"
        runCatching {
            if (file.length() > MAX_FILE_BYTES) {
                file.writeText("")
            }
            file.appendText(header)
            file.appendText(CrashLogSanitizer.sanitize(text))
            file.appendText("\n")
        }.onFailure { return false }
        return true
    }

    /**
     * 供 UI 展示用：读取归因日志全文（有则返回）。
     */
    fun readFile(context: Context): String {
        val file = File(context.filesDir, LOG_FILE_NAME)
        if (!file.exists()) return ""
        return runCatching { file.readText() }.getOrDefault("")
    }

    private fun sanitize(raw: String?): String {
        if (raw.isNullOrBlank()) return "<none>"
        val capped = if (raw.length > 512) raw.take(512) + "<truncated>" else raw
        return CrashLogSanitizer.sanitize(capped)
    }
}
