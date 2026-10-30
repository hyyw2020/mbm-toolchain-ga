/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 双通道分词器（冻结方案 B1/B3/B4）：把屏幕捕获文本提炼成可写入自定义词组的候选词条。
 *
 * 通道 A（词典/形态）：最长连续汉字段 → 整词或按窗口切分，叠加无监督新词发现
 *   （互信息 × 邻字熵，见 RecallTextUtils.discoverNewWords）产出的二元组候选。
 * 通道 B（NER）：离线专名识别（RecallNer），对游戏名 / 应用名 / 人名 / 地名等专名召回。
 *
 * 三层过滤（缺一不可，顺序固定）：
 *   1. 长度过滤：词条长度需在 [MIN_PHRASE_LEN, MAX_WHOLE_PHRASE_LEN]；
 *   2. 拼音键可用性：无法派生拼音键（非全汉字）的词条直接丢弃
 *      （RecallWordStore 经 AppPhraseImporter.keysForName 派生，非汉字无键可派）；
 *   3. 敏感打标降权：命中模式级正则（卡号/身份证/验证码）的词条打标 sensitive=true，
 *      由 RecallWordStore 降级为 COLD_ORDER、不出候选首位（E2/E3 降权，而非整屏丢弃）。
 *
 * 全部离线、零网络；新词发现基于一个**有界滚动语料缓冲**，防止内存无界增长。
 */
package org.fcitx.fcitx5.android.recall.segment

import org.fcitx.fcitx5.android.recall.RecallConfig
import org.fcitx.fcitx5.android.recall.RecallPolicy

/** 词条来源通道，用于验收与调试时区分提炼路径。 */
enum class CandidateSource { DICT, NEW_WORD, NER }

/**
 * 一个待写入词库的候选词条。
 * @param phrase   词条文本（纯汉字，拼音首字母键由此派生）
 * @param source   来源通道（DICT / NEW_WORD / NER）
 * @param confidence 置信度（0~1），新词/专名用于排序与阈值裁决
 * @param sensitive 是否命中模式级敏感正则（卡号/身份证/验证码），命中则降权不出首位
 */
data class RecallCandidate(
    val phrase: String,
    val source: CandidateSource,
    val confidence: Float,
    val sensitive: Boolean
)

class RecallSegmenter {

    /**
     * 有界滚动语料缓冲（新词发现语料），超过上限丢弃最旧部分。
     * P0-3 修复：feed 由拾取线程与 OCR 回调协程并发调用，StringBuilder 非线程安全
     * （扩容时 count/value 不一致，读取方可看到半截字符甚至越界）。改为锁内短临界区
     * 维护不可变 String 快照，分词重算在锁外对快照进行，锁竞争纳秒级，不违背线程隔离。
     */
    private val corpusLock = Any()
    private var corpus: String = ""

    /** 喂入行计数：每 N 行重算一次新词集合，避免每次捕获都全量扫描。 */
    private var feedCount = 0

    /** 最近一次重算的无监督新词二元组集合（来源通道 NEW_WORD）。 */
    @Volatile
    private var newWords: Set<String> = emptySet()

    /**
     * 喂入一段文本到语料缓冲（供新词发现）。在 [segment] 中自动调用，
     * 也可由上游显式调用以累积跨次捕获的统计。缓冲有界、重算节流。
     */
    fun feed(text: String) {
        if (text.isBlank()) return
        val snapshot: String
        synchronized(corpusLock) {
            corpus = (corpus + text + '\n').takeLast(RecallConfig.CORPUS_BUFFER_MAX)
            feedCount++
            snapshot = corpus
        }
        // 重算在锁外：互信息/邻字熵计算较重，不阻塞拾取线程
        if (feedCount % 8 == 0) {
            newWords = RecallTextUtils.discoverNewWords(snapshot, 60)
                .map { it.first }
                .toSet()
        }
    }

    /**
     * 核心入口：将一组文本行提炼为候选词条。
     * 双通道 + 三层过滤，结果按 phrase 去重（同短语取最高置信度、敏感标记取并集）。
     */
    fun segment(lines: Sequence<String>): List<RecallCandidate> {
        val best = LinkedHashMap<String, RecallCandidate>()
        for (line in lines) {
            if (line.isBlank()) continue
            // 新词发现语料累积（同进程、有界，重算节流）
            feed(line)

            // —— 通道 A：最长连续汉字段 → 整词 / 窗口切分 ——
            for (run in RecallTextUtils.hanRuns(line)) {
                if (run.length <= RecallConfig.MAX_WHOLE_PHRASE_LEN) {
                    emit(best, run, CandidateSource.DICT, 0.6f)
                } else {
                    var i = 0
                    while (i + RecallConfig.WINDOW_PHRASE_LEN <= run.length) {
                        emit(
                            best,
                            run.substring(i, i + RecallConfig.WINDOW_PHRASE_LEN),
                            CandidateSource.DICT,
                            0.6f
                        )
                        i += RecallConfig.WINDOW_PHRASE_LEN
                    }
                    val tail = run.substring(i)
                    if (tail.length >= RecallConfig.MIN_PHRASE_LEN) {
                        emit(best, tail, CandidateSource.DICT, 0.6f)
                    }
                }
            }

            // —— 通道 A2：无监督新词二元组（命中本行则产出）——
            for (w in newWords) {
                if (line.contains(w)) emit(best, w, CandidateSource.NEW_WORD, 0.7f)
            }

            // —— 通道 B：离线专名识别 ——
            for (e in RecallNer.detect(line)) {
                emit(best, e.text, CandidateSource.NER, e.confidence)
            }
        }
        return best.values.toList()
    }

    /** 三层过滤 + 去重合并：满足条件则写入 best（同 phrase 取更优者）。 */
    private fun emit(
        best: MutableMap<String, RecallCandidate>,
        phrase: String,
        source: CandidateSource,
        confidence: Float
    ) {
        // 第一层：长度过滤
        if (phrase.length < RecallConfig.MIN_PHRASE_LEN ||
            phrase.length > RecallConfig.MAX_WHOLE_PHRASE_LEN
        ) return
        // 第二层：拼音键可用性 —— 无法派生拼音键（非全汉字）直接丢弃
        if (!RecallTextUtils.isAllHan(phrase)) return
        // 第三层：敏感打标降权（不丢弃整屏，仅标记）
        val sensitive = RecallPolicy.findSensitiveTokens(phrase).isNotEmpty()

        val prev = best[phrase]
        if (prev == null) {
            best[phrase] = RecallCandidate(phrase, source, confidence, sensitive)
        } else {
            // 同短语：保留更高置信度，敏感标记取并集（任一通道判敏感即降权）
            val mergedConf = maxOf(prev.confidence, confidence)
            val mergedSensitive = prev.sensitive || sensitive
            best[phrase] = RecallCandidate(phrase, source, mergedConf, mergedSensitive)
        }
    }
}
