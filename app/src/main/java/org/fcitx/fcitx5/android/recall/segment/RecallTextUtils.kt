/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 分词层通用文本工具：汉字段抽取、字符级统计、互信息 / 邻字熵新词发现。
 * 全部离线、零依赖，对屏幕捕获文本做轻量语言学处理。
 */
package org.fcitx.fcitx5.android.recall.segment

import kotlin.math.ln
import org.fcitx.fcitx5.android.recall.RecallConfig

object RecallTextUtils {

    fun isHan(ch: Char): Boolean = ch in '\u4e00'..'\u9fff'

    fun isAllHan(s: String): Boolean = s.isNotEmpty() && s.all { isHan(it) }

    /**
     * 专名词符：汉字 / 字母数字 / 命名连接符（· • ・ ･ + # @ - _ .）。
     * 这些字符允许在「应用名 / 游戏名」里粘合出现，不应像普通标点那样把专名切碎。
     */
    private val PROPERNOUN_GLUE = setOf('·', '•', '・', '･', '+', '#', '@', '-', '_', '.')

    private fun isProperNounChar(ch: Char): Boolean =
        isHan(ch) || ch.isLetterOrDigit() || ch in PROPERNOUN_GLUE

    /**
     * 通道 D：混合中英文专名抽取（应用名 / 游戏名 / 带版本号或英文名的词组）。
     *
     * 设计动机：原 [hanRuns] 只抽取「连续纯汉字段」，遇到任何非汉字即切分，导致
     * 「云·战双帕弥什」（含 ·）、「雨见cBeta」（含英文）这类真实专名被切碎后整条丢弃——
     * 既拆不出词，也导不进词库。
     *
     * 本函数把「汉字 + 相邻字母/数字/命名连接符」粘合为一个 token，再按以下判据过滤，
     * 仅保留像专名的 token，避免把整句中文也吞进来（整句汉字交给 [hanRuns] 处理）：
     *   1) 至少一个汉字；
     *   2) 至少一个「专有名词信号」——ASCII 字母，或命名分隔符（·/•/・ 等）；
     *      （仅含数字混合的中文短句如「苹果12个」不视为专名，不抽取）
     *   3) 长度 ∈ [2, MAX_PROPERNOUN_LEN]。
     *
     * 通用实现：不依赖任何具体词表 / 训练语料，新增专名形态时无需改代码。
     */
    fun properNounTokens(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val tokens = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            if (isProperNounChar(ch)) sb.append(ch)
            else if (sb.isNotEmpty()) { tokens += sb.toString(); sb.setLength(0) }
        }
        if (sb.isNotEmpty()) tokens += sb.toString()
        return tokens.filter { t ->
            if (t.length < 2 || t.length > RecallConfig.MAX_PROPERNOUN_LEN) return@filter false
            val hasHan = t.any { isHan(it) }
            if (!hasHan) return@filter false
            val hasNameSignal = t.any { it.isLetter() || it in PROPERNOUN_GLUE }
            hasNameSignal
        }
    }

    /**
     * 抽取文本中每段最长连续汉字段（长度 ≥ 2）。
     * 旧实现先跑了一次 Regex.findAll 再把结果丢掉（死代码），等于对每次捕获文本
     * 多做一遍无谓的全文正则扫描；这里直接逐字符聚合，零正则开销。
     */
    fun hanRuns(text: String): List<String> {
        val runs = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            if (isHan(ch)) sb.append(ch)
            else if (sb.isNotEmpty()) { runs += sb.toString(); sb.setLength(0) }
        }
        if (sb.isNotEmpty()) runs += sb.toString()
        return runs.filter { it.length >= 2 }
    }

    /**
     * 新词发现（无监督）：在一段语料上用「互信息(PMI) × 最小邻字熵」给二元组打分，
     * 高分二元组即潜在新词（内部凝固度高、左右边界清晰）。
     *
     *  - PMI(a,c) = log( count(a,c)·N / (count(a)·count(c)) )
     *  - H_left(b) / H_right(b) = 以 b 为右 / 左邻的字符分布熵
     *  - score(b) = PMI(b) · min(H_left, H_right)
     *
     * 语料来自屏幕滚动捕获的滚动缓冲（见 [RecallSegmenter]），规模有限，
     * 故阈值取保守值，只吐出高置信二元组；更长的词由窗口切分 + 新词二元组拼接提供。
     */
    fun discoverNewWords(corpus: String, topK: Int = 60): List<Pair<String, Float>> {
        val chars = corpus.filter { isHan(it) }
        if (chars.length < 8) return emptyList()
        val n = chars.length.toFloat()
        val charCount = mutableMapOf<Char, Int>()
        val bigramCount = mutableMapOf<String, Int>()
        val leftNb = mutableMapOf<String, MutableMap<Char, Int>>()
        val rightNb = mutableMapOf<String, MutableMap<Char, Int>>()
        for (i in chars.indices) {
            charCount[chars[i]] = charCount.getOrDefault(chars[i], 0) + 1
            if (i + 1 < chars.length) {
                val b = "${chars[i]}${chars[i + 1]}"
                bigramCount[b] = bigramCount.getOrDefault(b, 0) + 1
                if (i - 1 >= 0) {
                    leftNb.getOrPut(b) { mutableMapOf() }
                        .merge(chars[i - 1], 1, Int::plus)
                }
                if (i + 2 < chars.length) {
                    rightNb.getOrPut(b) { mutableMapOf() }
                        .merge(chars[i + 2], 1, Int::plus)
                }
            }
        }
        val results = mutableListOf<Pair<String, Float>>()
        for ((b, c) in bigramCount) {
            if (c < 3) continue // 频次过低不可信
            val ca = charCount[b[0]] ?: continue
            val cc = charCount[b[1]] ?: continue
            val pmi = ln(c.toFloat() * n / (ca * cc))
            if (pmi <= 0) continue
            val hLeft = entropy(leftNb[b])
            val hRight = entropy(rightNb[b])
            val score = pmi * minOf(hLeft, hRight)
            if (score > 1.5f) results += b to score
        }
        return results.sortedByDescending { it.second }.take(topK)
    }

    private fun entropy(dist: Map<Char, Int>?): Float {
        if (dist.isNullOrEmpty()) return 0f
        val total = dist.values.sum().toFloat()
        var h = 0f
        for (v in dist.values) {
            val p = v / total
            if (p > 0f) h -= p * ln(p)
        }
        return h
    }
}
