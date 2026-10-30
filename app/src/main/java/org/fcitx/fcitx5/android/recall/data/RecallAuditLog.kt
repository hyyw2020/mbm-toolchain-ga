/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * Recall 数据去向审计日志。
 *
 * 目的：让用户清楚知道本机捕获的屏幕文字“去了哪里”——导入输入法剪贴板、
 * 导出为文本、删除、清空，全部追加写入本地明文日志文件（与 SQLCipher 密文库
 * 分离，便于用户随时查看），任何操作都不会离开本机。
 */
package org.fcitx.fcitx5.android.recall.data

import org.fcitx.fcitx5.android.utils.appContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RecallAuditLog {

    private val file by lazy { File(appContext.filesDir, "recall_audit.log") }

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    /** 追加一条审计记录（线程安全） */
    @Synchronized
    fun log(action: String, detail: String) {
        runCatching {
            val line = "${fmt.format(Date())}  $action  $detail\n"
            file.appendText(line)
        }
    }

    /** 读取最近 n 条（按写入顺序，最新在后） */
    fun recent(n: Int = 30): List<String> =
        runCatching { file.readLines().takeLast(n) }.getOrDefault(emptyList())

    fun exists(): Boolean = file.exists()

    fun path(): String = file.absolutePath

    /** 明文日志文件大小（便于 UI 展示） */
    fun sizeBytes(): Long = runCatching { file.length() }.getOrDefault(0L)

    // 操作类型常量
    const val ACTION_IMPORT_CLIPBOARD = "导入输入法(剪贴板)"
    const val ACTION_EXPORT = "导出文本"
    const val ACTION_DELETE = "删除记录"
    const val ACTION_CLEAR = "清空全部"
    const val ACTION_TRIM = "过期清理"
    const val ACTION_IMPORT_PHRASE = "导入自定义词组"
    const val ACTION_AUTO_PHRASE = "自动写入自定义词组"
    const val ACTION_AUTO_CLIPBOARD = "自动写入剪贴板"
    /** MBM v1.0.26：系统高级保护模式命中，服务停用 */
    const val ACTION_APM_BLOCK = "高级保护模式停用"
}
