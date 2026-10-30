/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 捕获文本 → 剪贴板候选的自动写入器。
 *
 * MBM v1.0.26（Recall 独立化）：插件不能直接写主输入法的剪贴板库，
 * 自动路径降级为追加到本地队列文件（files/recall_clipboard_queue.txt）。
 *
 * ⚠️ 绝不自动调用系统剪贴板 setPrimaryClip：
 *  ① 会覆盖用户刚复制的内容；
 *  ② Android 10+ 只有默认输入法/前台应用能可靠写剪贴板；
 *  ③ Android 14+ 每次系统剪贴板变更系统都会弹出「已复制」提示，自动写会刷屏。
 * 只有 RecallActivity 的显式「导入」按钮才会写系统剪贴板。
 */
package org.fcitx.fcitx5.android.recall

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.recall.data.RecallAuditLog
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.util.LinkedHashSet

object RecallClipboardWriter {

    /** 过短的片段（如单字、符号）没有复制价值，会刷屏 */
    private const val MIN_LEN = 4
    /**
     * MBM v1.0.31：移除 MAX_LEN=200 / MAX_PER_CAPTURE=3 硬上限。
     * 应用户「无上限」：长文本整条入队，行数不设上限。
     * MIN_INTERVAL_MS 是**频率**节流（防滚动刷屏），不是内容截断，保留。
     */
    /** 全局最小写入间隔：滚动时每条捕获都写会瞬间灌满队列 */
    private const val MIN_INTERVAL_MS = 2000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val queueFile: File
        get() = File(appContext.filesDir, "recall_clipboard_queue.txt")

    @Volatile
    private var lastWriteAt = 0L

    /** 内存去重集合：惰性加载一次，追加时同步更新（避免每次 offer 全量读文件）。 */
    private val known: HashSet<String> by lazy {
        runCatching {
            queueFile.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.toHashSet()
                ?: hashSetOf()
        }.getOrDefault(hashSetOf())
    }

    /**
     * 从捕获文本提炼适合进剪贴板的片段（**无长度/条数上限**）：
     *  - 整段文本 ≥ [MIN_LEN] 则整段入队；
     *  - 含多行的长文本按行提炼，每行 ≥ [MIN_LEN]，行数不设上限。
     */
    fun extractCandidates(text: String): List<String> {
        val trimmed = text.trim()
        if (trimmed.length < MIN_LEN) return emptyList()
        if (!trimmed.contains('\n')) return listOf(trimmed)
        return trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.length >= MIN_LEN }
            .toList()
    }

    /**
     * 捕获回调中调用：提炼 + 节流 + 后台追加到本地队列（不阻塞捕获主路径）。
     */
    fun offer(text: String) {
        if (text.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastWriteAt < MIN_INTERVAL_MS) return
        val candidates = extractCandidates(text)
        if (candidates.isEmpty()) return
        lastWriteAt = now
        scope.launch {
            try {
                var appended = 0
                val sb = StringBuilder()
                synchronized(known) {
                    candidates.forEach { c ->
                        if (known.add(c)) {
                            sb.append(c.replace('\n', ' ')).append('\n')
                            appended++
                        }
                    }
                }
                if (appended > 0) {
                    queueFile.appendText(sb.toString())
                    RecallAuditLog.log(
                        RecallAuditLog.ACTION_AUTO_CLIPBOARD,
                        "自动入队剪贴板候选：$appended 条（RecallActivity 内可一键复制）"
                    )
                }
            } catch (t: Throwable) {
                Timber.w(t, "recall clipboard auto write failed")
            }
        }
    }

    /** 读取并清空本地队列（由 RecallActivity 显式导入时调用） */
    @Synchronized
    fun drainQueue(): List<String> {
        return runCatching {
            if (!queueFile.exists()) return emptyList()
            val lines = queueFile.readLines().filter { it.isNotBlank() }
            queueFile.writeText("")
            synchronized(known) { known.clear() }
            lines.distinct()
        }.getOrDefault(emptyList())
    }
}
