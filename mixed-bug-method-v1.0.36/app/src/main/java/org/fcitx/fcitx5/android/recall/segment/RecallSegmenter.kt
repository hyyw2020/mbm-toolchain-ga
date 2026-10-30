/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 双通道分词器（冻结方案 B1/B3/B4）：
 *  - 通道 A：屏幕文本 → 最长汉字段 → 整词（≤6 字）成词 / 长字段按 4 字窗口切分；
 *    叠加无监督新词发现（互信息 × 邻字熵）产出的二元组作为 NEW_WORD 候选。
 *  - 通道 B：NER（形态强规则 + 词典兜底，含 ONNX 模型钩子）识别专名候选。
 *  - 三层过滤：① 长度 / 格式；② 拼音键可用性（无法派生拼音键则无法写入词组，丢弃）；
 *    ③ 敏感打标降权（命中卡号 / 身份证 / 验证码正则的词条标记 sensitive，由词库层降权）。
 *
 * 输出 [RecallCandidate]，供 [org.fcitx.fcitx5.android.recall.store.RecallWordStore] 落库。
 */
package org.fcitx.fcitx5.android.recall.segment

import org.fcitx.fcitx5.android.recall.RecallConfig
import org.fcitx.fcitx5.android.recall.RecallPolicy
import org.fcitx.fcitx5.android.utils.PinyinUtils

enum class CandidateSource { DICT, NEW_WORD, NER }

data class RecallCandidate(
    val phrase: String,
    val source: CandidateSource,
    val confidence: Float,
    val sensitive: Boolean
)

class RecallSegmenter {

    private val corpus = StringBuilder()
    private val newWords = LinkedHashSet<String>()
    private var feedCount = 0

    /** 持续喂入捕获文本，维护滚动语料缓冲用于新词发现（有界）。 */
    fun feed(text: String) {
        synchronized(corpus) {
            corpus.append(text).append('\n')
            if (corpus.length > RecallConfig.CORPUS_BUFFER_MAX) {
                corpus.delete(0, corpus.length - RecallConfig.CORPUS_BUFFER_MAX)
            }
            // 新词发现代价为 O(语料)，不必每行都跑：每 8 行重算一次。
            if (++feedCount % 8 == 0) {
                for ((w, _) in RecallTextUtils.discoverNewWords(corpus.toString())) newWords += w
                if (newWords.size > 4000) {
                    // 仅保留最近一半，避免无界增长
                    val it = newWords.iterator()
                    repeat(newWords.size / 2) { it.next(); it.remove() }
                }
            }
        }
    }

    /** 对一批行做双通道分词 + 三层过滤，产出可写入词库的候选（已去重）。 */
    fun segment(lines: Sequence<String>): List<RecallCandidate> {
        val candidates = mutableListOf<RecallCandidate>()
        val seen = mutableSetOf<String>()
        for (line in lines) {
            if (line.isBlank()) continue
            feed(line)
            // 通道 A：最长汉字段 → 整词 / 窗口切分
            for (run in RecallTextUtils.hanRuns(line)) addRun(candidates, seen, run)
            // 通道 B：NER 专名
            for (e in RecallNer.detect(line)) {
                if (e.text in seen) continue
                seen += e.text
                candidates += RecallCandidate(
                    e.text, CandidateSource.NER, e.confidence,
                    isSensitive(e.text)
                )
            }
        }
        return candidates
    }

    private fun addRun(
        candidates: MutableList<RecallCandidate>,
        seen: MutableSet<String>,
        run: String
    ) {
        if (run.length <= RecallConfig.MAX_WHOLE_PHRASE_LEN) {
            emit(candidates, seen, run,
                if (run in newWords) CandidateSource.NEW_WORD else CandidateSource.DICT, 0.8f)
        } else {
            var i = 0
            while (i + RecallConfig.WINDOW_PHRASE_LEN <= run.length) {
                emit(candidates, seen, run.substring(i, i + RecallConfig.WINDOW_PHRASE_LEN),
                    CandidateSource.DICT, 0.7f)
                i += RecallConfig.WINDOW_PHRASE_LEN
            }
            val tail = run.substring(i)
            if (tail.length >= RecallConfig.MIN_PHRASE_LEN) {
                emit(candidates, seen, tail, CandidateSource.DICT, 0.7f)
            }
        }
    }

    private fun emit(
        candidates: MutableList<RecallCandidate>,
        seen: MutableSet<String>,
        phrase: String,
        source: CandidateSource,
        confidence: Float
    ) {
        if (phrase in seen) return
        // 第一层：长度 / 格式
        if (phrase.length < RecallConfig.MIN_PHRASE_LEN ||
            phrase.length > RecallConfig.MAX_WHOLE_PHRASE_LEN
        ) return
        // 第二层：拼音键可用性（无法派生拼音首字母/全拼则无法写入自定义词组，丢弃）
        if (PinyinUtils.initials(phrase) == null) return
        // 第三层：敏感打标降权（不丢弃整词，仅标记，由词库层降级处理）
        val sensitive = isSensitive(phrase)
        seen += phrase
        candidates += RecallCandidate(phrase, source, confidence, sensitive)
    }

    private fun isSensitive(phrase: String): Boolean =
        RecallPolicy.findSensitiveTokens(phrase).isNotEmpty()
}
