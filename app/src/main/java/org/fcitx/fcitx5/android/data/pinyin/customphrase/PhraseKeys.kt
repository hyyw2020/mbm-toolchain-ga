/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 一个词应该挂到哪些拼音键上（v1.0.72）。
 *
 * 根因：native `pinyin.cpp` 用 `customPhrase_.lookup(pyBeforeCursor)` 精确匹配
 * **用户实际敲进来的原始拼音串**，而 v1.0.71 及以前全仓库只生成「首字母缩写键」
 * （keysForName -> PinyinUtils.initials：「安全」= aq、「哔哩哔哩」= blbl）。
 * 后果：习惯打全拼的用户输入 anquan / bilibilli 时，词库里根本没有对应键，
 * v1.0.66~71 的全部个性化排序与 N-gram 预测对他完全不生效 —— 这是预判长期
 * 「时灵时不灵」的架构级盲区，而不是打分权重问题。
 *
 * 修法：写词组时同时挂「首字母键 + 全拼键」，二者各自独立精确匹配、互不干扰；
 * 首字母键一行不动（既有行为零变化），全拼键是**纯新增**通道。
 *  - 只对纯汉字词生成全拼键：ASCII 名 / 中英混排交给 keysForName 原有逻辑。
 *  - 全拼键 2..24 位纯小写字母；单字母一律排除（v1.0.65 emoji 污染防线）。
 *  - 与首字母键去重，避免同一 key=value 写两行。
 *
 * 纯离线：只做本地拼音转写，不联网、不落盘、不记录任何输入内容。
 */
package org.fcitx.fcitx5.android.data.pinyin.customphrase

import org.fcitx.fcitx5.android.utils.PinyinUtils

object PhraseKeys {

    /** 全拼键长度上限：覆盖 8 字词（8×~5=40 会超限，故按 24 截断，长词仍走首字母键）。 */
    const val FULL_PINYIN_MAX = 24
    const val KEY_LEN_MIN = 2

    /**
     * @param word            待挂键的词
     * @param includeFullPinyin 是否附加全拼键（默认 true；置 false 即等价于旧 keysForName）
     */
    fun forWord(word: String, includeFullPinyin: Boolean = true): Set<String> {
        val keys = LinkedHashSet(AppPhraseImporter.keysForName(word))
        if (includeFullPinyin && PinyinUtils.isAllChinese(word)) {
            val full = PinyinUtils.fullPinyin(word)
                ?.lowercase()
                ?.filter { it in 'a'..'z' }
            if (full != null && full.length in KEY_LEN_MIN..FULL_PINYIN_MAX) {
                keys += full
            }
        }
        // 统一口径：键必须是 2..24 位纯小写字母，杜绝单字母键与畸形键进入词库。
        return keys.filterTo(LinkedHashSet()) { k ->
            k.length in KEY_LEN_MIN..FULL_PINYIN_MAX && k.all { it in 'a'..'z' }
        }
    }
}
