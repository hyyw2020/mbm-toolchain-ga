/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 捕获文本 → 自定义词组候选的自动写入器。
 *
 * MBM v1.0.26（Recall 独立化）：
 * 插件 APK 与主输入法是**两个进程/两个沙盒**，不能再直接写 fcitx 自定义词组文件，
 * 也不能 hot-reload。因此自动路径降级为：提炼结果追加写入本地队列文件
 * （files/recall_phrase_queue.txt），用户在 RecallActivity 里一键
 * 「导出为词组」→ 触发主输入法的 .txt 导入 Intent（主输入法本就有 .txt 导入能力，
 * 见主包 AndroidManifest 的 VIEW intent-filter），由主输入法完成拼音键推导与落盘。
 *
 * 提炼规则保持不变（键=拼音首字母 + 全拼键由主输入法导入时推导，顺序固定 2）。
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
import java.util.Collections
import java.util.LinkedHashSet

object PhraseAutoWriter {

    private const val PHRASE_ORDER = 2
    private const val MIN_PHRASE_LEN = 2
    private const val MAX_WHOLE_PHRASE_LEN = 6
    private const val WINDOW_PHRASE_LEN = 4
    // MBM v1.0.31：移除 PHRASE_HARD_LIMIT=200 硬上限（应用户「无上限」）。
    // 提炼量由用户导出动作控制，不再有代码级截断。

    /** 落盘去抖：至少间隔这么久、或积累到 BATCH_LIMIT 才写一次
     *  （BATCH_LIMIT 是**批量大小**不是内容上限：超出部分留在 pending 下一批再写，不丢内容） */
    private const val FLUSH_INTERVAL_MS = 1500L
    private const val BATCH_LIMIT = 120

    /** 插件本地队列文件（供 RecallActivity 导出给主输入法导入） */
    private val queueFile: File
        get() = File(appContext.filesDir, "recall_phrase_queue.txt")

    private val pending = Collections.synchronizedSet(LinkedHashSet<String>())
    private var lastFlush = 0L
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 从捕获文本提炼词组：按行切分 → 取最大连续汉字段 →
     * ≤6 字整段成词；更长的按 4 字窗口切分（末尾余 2~3 字另成一词）。
     * 结果去重，**条数无上限**（MBM v1.0.31 移除 PHRASE_HARD_LIMIT）。
     */
    fun extractPhrases(texts: List<String>): List<String> =
        extractPhrasesFromLines(texts.asSequence().flatMap { it.lineSequence() })

    /**
     * 行序列版提炼（规则与 [extractPhrases] 完全一致）。
     *
     * MBM v1.0.31 blob 化后，RecallActivity 对 100MB 级大文本逐行流式提炼，
     * 不把全文读进内存（[extractPhrases] 的 List<String> 入参做不到）。
     */
    fun extractPhrasesFromLines(lines: Sequence<String>): List<String> {
        val han = Regex("[\\u4e00-\\u9fff]{2,}")
        val out = LinkedHashSet<String>()
        lines.forEach { line ->
            han.findAll(line).forEach { m ->
                val run = m.value
                if (run.length <= MAX_WHOLE_PHRASE_LEN) {
                    out += run
                } else {
                    var i = 0
                    while (i + WINDOW_PHRASE_LEN <= run.length) {
                        out += run.substring(i, i + WINDOW_PHRASE_LEN)
                        i += WINDOW_PHRASE_LEN
                    }
                    val tail = run.substring(i)
                    if (tail.length >= MIN_PHRASE_LEN) out += tail
                }
            }
        }
        return out.toList()
    }

    /** 捕获回调中调用：提炼 + 入队（极廉价，无延迟） */
    fun offer(text: String) {
        if (text.isBlank()) return
        val phrases = extractPhrases(listOf(text))
        if (phrases.isEmpty()) return
        synchronized(pending) { pending.addAll(phrases) }
        maybeFlush(false)
    }

    /** 立即把队列里剩余的词组落盘（如服务销毁前） */
    fun flushNow() = maybeFlush(true)

    /** 追加到本地队列文件（重复内容不入队；内存集合惰性加载一次） */
    @Synchronized
    fun appendToQueue(phrases: List<String>): Int {
        if (phrases.isEmpty()) return 0
        return runCatching {
            var appended = 0
            val sb = StringBuilder()
            synchronized(known) {
                phrases.forEach { p ->
                    if (known.add(p)) {
                        sb.append(p).append('\n')
                        appended++
                    }
                }
            }
            if (appended > 0) {
                queueFile.appendText(sb.toString())
                RecallAuditLog.log(
                    RecallAuditLog.ACTION_AUTO_PHRASE,
                    "自动提炼词组入队：$appended 条（导出给主输入法后落盘，顺序=$PHRASE_ORDER）"
                )
            }
            appended
        }.onFailure { Timber.w(it, "append phrase queue failed") }.getOrDefault(0)
    }

    /** 读取并清空本地队列（由 RecallActivity 导出时调用） */
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

    fun pendingQueueSize(): Int = runCatching {
        queueFile.takeIf { it.exists() }?.readLines()?.count { it.isNotBlank() } ?: 0
    }.getOrDefault(0)

    /** 内存去重集合：惰性加载一次（避免每次 append 全量读文件） */
    private val known: HashSet<String> by lazy {
        runCatching {
            queueFile.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() }?.toHashSet()
                ?: hashSetOf()
        }.getOrDefault(hashSetOf())
    }

    private fun maybeFlush(force: Boolean) {
        val now = System.currentTimeMillis()
        val size = synchronized(pending) { pending.size }
        if (!force && size < BATCH_LIMIT && now - lastFlush < FLUSH_INTERVAL_MS) return
        val batch = synchronized(pending) {
            val list = pending.take(BATCH_LIMIT)
            pending.removeAll(list.toSet())
            list
        }
        if (batch.isEmpty()) return
        lastFlush = now
        if (force) {
            // N70：服务销毁前必须同步落盘，否则紧接着的 scope.cancel() 会丢弃 pending 中
            // 最多 BATCH_LIMIT 条词组（连同 known 去重集合一起丢失）。force 路径直接同步写入。
            appendToQueue(batch)
        } else {
            flushScope.launch { appendToQueue(batch) }
        }
    }
}
