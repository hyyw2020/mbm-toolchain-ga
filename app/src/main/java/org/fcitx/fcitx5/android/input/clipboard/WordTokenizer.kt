/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: MBM Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.icu.text.BreakIterator
import java.util.Locale

/**
 * MBM v1.0.45：「拆分选词 / 滑动选词」分词器。
 *
 * 把一段文本切成可在面板里滑动选择的「词块」，要求与主流输入法的拆词体验一致：
 *  - 中文按词典切词（保持/一致/的/边界/感…），不机械逐字；
 *  - 英文/数字按词边界，标点独立成块；
 *  - 链接（http/https/www 开头）整体成块，不被拆散；
 *  - 词块之间的空白与换行不是词块，但复制选中范围时原样保留。
 *
 * 实现基于平台 ICU 的词典断词（[BreakIterator]），无需引入任何第三方分词库。
 */
object WordTokenizer {

    /** 词块：源文本上的半开区间 [start, end)，复制时直接取子串以保留原始空白 */
    data class Token(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    data class Result(
        /** 原始全文（未截断），词块区间以它为基准 */
        val text: String,
        val tokens: List<Token>,
        /** 因超长被截断（字符数或词块数触顶） */
        val truncated: Boolean
    )

    /**
     * 面板单次加载的最大字符数。超出直接截断——原条目内容不做任何写操作，
     * 只是面板不展示后半部分（与「查看全文」的限长思路一致）。
     */
    const val MAX_CHARS = 100_000

    /** 词块数量上限，防止极端文本把面板 measure/layout 拖垮 */
    const val MAX_TOKENS = 1500

    private val urlRegex = Regex("(?i)(?:https?://|www\\.)\\S+")

    /** URL 尾部若粘着中英文标点（如「https://a.com，」），从链接块里去掉 */
    private const val URL_TRAILING_PUNCT = "。，、；：！？）】》〉…·\"'”’»›,.;:!?)]}>"

    fun tokenize(raw: String): Result {
        val truncatedByChars = raw.length > MAX_CHARS
        val text = if (truncatedByChars) raw.substring(0, MAX_CHARS) else raw
        if (text.isEmpty()) return Result(raw, emptyList(), truncatedByChars)

        // 预扫所有链接区间（排序去重），分词主循环里把它们当原子块
        val urls = urlRegex.findAll(text)
            .map { trimUrlRange(text, it.range) }
            .filter { !it.isEmpty() }
            .sortedBy { it.first }
            .toList()

        val iterator = try {
            // 中文 locale 走 ICU 词典断词；异常兜底换 ROOT 规则（退化为按字/空白切）
            BreakIterator.getWordInstance(Locale.CHINESE)
        } catch (t: Throwable) {
            BreakIterator.getWordInstance(Locale.ROOT)
        }
        iterator.setText(text)

        val tokens = ArrayList<Token>(minOf(256, text.length / 2 + 1))
        var pos = 0
        while (pos < text.length && tokens.size < MAX_TOKENS) {
            val url = urls.firstOrNull { it.first == pos }
            if (url != null) {
                tokens.add(Token(url.first, url.last + 1))
                pos = url.last + 1
                continue
            }
            val boundary = iterator.following(pos)
            if (boundary == BreakIterator.DONE) break
            // 词块不得越过最近的链接起点，否则链接会被前面的块吞掉
            val nextUrlStart = urls.firstOrNull { it.first > pos }?.first ?: Int.MAX_VALUE
            val end = minOf(boundary, nextUrlStart).coerceAtMost(text.length)
            if (end <= pos) break
            // 空白/换行不是词块，跳过
            if (!isBlankRange(text, pos, end)) {
                tokens.add(Token(pos, end))
            }
            pos = end
        }
        val truncated = truncatedByChars || tokens.size >= MAX_TOKENS || pos < text.length
        return Result(raw, tokens, truncated)
    }

    private fun trimUrlRange(text: String, range: IntRange): IntRange {
        var end = range.last
        while (end > range.first && text[end] in URL_TRAILING_PUNCT) end--
        return range.first..end
    }

    private fun isBlankRange(text: String, start: Int, end: Int): Boolean {
        for (i in start until end) {
            if (!Character.isWhitespace(text[i])) return false
        }
        return true
    }
}
