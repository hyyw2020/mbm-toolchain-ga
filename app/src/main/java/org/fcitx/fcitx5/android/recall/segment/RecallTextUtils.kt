/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 分词层通用文本工具：汉字段抽取、字符级统计、互信息 / 邻字熵新词发现。
 * 全部离线、零依赖，对屏幕捕获文本做轻量语言学处理。
 */
package org.fcitx.fcitx5.android.recall.segment

import kotlin.math.ln

object RecallTextUtils {

    /** 常用汉字区间（含扩展 A 之外的核心区）。 */
    private val HAN = Regex("[\\u4e00-\\u9fff]")

    fun isHan(ch: Char): Boolean = ch in '\u4e00'..'\u9fff'

    fun isAllHan(s: String): Boolean = s.isNotEmpty() && s.all { isHan(it) }

    /** 抽取文本中每段最长连续汉字段（长度 ≥ 2）。 */
    fun hanRuns(text: String): List<String> =
        HAN.findAll(text).flatMap { m ->
            // Regex 匹配的是单字，需要把相邻汉字聚合成长字段
            emptyList<String>()
        }.toList().let {
            // 上面不可行，改用逐字符聚合
            val runs = mutableListOf<String>()
            val sb = StringBuilder()
            for (ch in text) {
                if (isHan(ch)) sb.append(ch)
                else if (sb.isNotEmpty()) { runs += sb.toString(); sb.setLength(0) }
            }
            if (sb.isNotEmpty()) runs += sb.toString()
            runs.filter { it.length >= 2 }
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
