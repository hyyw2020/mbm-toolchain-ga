/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: MBM Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.icu.text.BreakIterator
import org.fcitx.fcitx5.android.data.clipboard.ClipboardCapacityPolicy
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
     * UI 单次装载软边界（字符）。v1.0.77 起由 [ClipboardCapacityPolicy] 统一定义，
     * 且可在剪贴板设置里调整；**不是容量上限**——单条存储与分块粘贴上屏仍无上限。
     * 触发时面板必须明确披露（见 WordSelectUi.truncationHint），绝不静默丢字。
     */
    const val MAX_CHARS = ClipboardCapacityPolicy.DEFAULT_UI_LOAD_CHARS

    /** 单批渲染词块数上限，防止极端文本把面板 measure/layout 拖垮。 */
    const val MAX_TOKENS = ClipboardCapacityPolicy.DEFAULT_CHIPS_PER_PAGE

    private val urlRegex = Regex("(?i)(?:https?://|www\\.)\\S+")

    /** URL 尾部若粘着中英文标点（如「https://a.com，」），从链接块里去掉 */
    private const val URL_TRAILING_PUNCT = "。，、；：！？）】》〉…·\"'”’»›,.;:!?)]}>"

    /**
     * v1.0.77：字符级截断改由读入侧（ClipboardWindow.readCapped，可配置）控制，
     * 本函数默认不再按字符砍断全文，只按 [maxTokens] 控制单批渲染的词块数。
     * 这样「在设置里调大单次加载字数」才真正生效，不会被这里的旧常量二次截断。
     * 调用方须把读入侧的截断标志一并传给面板披露（见 WordSelectWindow）。
     */
    fun tokenize(
        raw: String,
        maxChars: Int = Int.MAX_VALUE,
        maxTokens: Int = MAX_TOKENS
    ): Result {
        val truncatedByChars = raw.length > maxChars
        val text = if (truncatedByChars) raw.substring(0, maxChars) else raw
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
        while (pos < text.length && tokens.size < maxTokens) {
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
        val truncated = truncatedByChars || tokens.size >= maxTokens || pos < text.length
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
