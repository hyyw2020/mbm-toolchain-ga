/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Cyletix
 *
 * 移植自 CyIME（https://github.com/Cyletix/CyIME），原路径
 * app/src/main/java/com/kingzcheung/xime/glide/EnglishBigrams.kt 中的 EnglishContractions 类。
 * 依 GPLv3 保留原版权与许可；来源说明见仓库 THIRD_PARTY_NOTICES.md 与 LICENSE 附录。
 */
package org.fcitx.fcitx5.android.recall.enhance

/**
 * 英文缩写表，数据来自 english_contractions.txt。词表采用去除撇号的小写写法（如 im、dont），
 * 上屏时替换为规范缩写（如 I'm、don't）。部分去撇号写法本身也是常用词（如 were、well、hell），
 * 此类写法保留原词，并把缩写作为附加候选。
 */
class EnglishContractions(lines: Sequence<String>) {
    private class Entry(val form: String, val rank: Int?, val offerOnly: Boolean)
    private val entries = LinkedHashMap<String, Entry>()

    init {
        for (line in lines) {
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.trim().split(' ')
            if (parts.size < 2) continue
            entries[parts[0]] = Entry(parts[1], parts.getOrNull(2)?.toIntOrNull(), parts.last() == "?")
        }
    }

    /**
     * 在英文词表基础上按排名插入缩写写法与附加词 [extra]。词表经去空白、小写、按首次出现去重处理。
     * 排名相同时，插入写法优先于附加词。
     */
    fun vocabulary(english: List<String>, extra: List<Pair<String, Int>> = emptyList()): List<String> {
        val words = english.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val inserted = (entries.entries.mapNotNull { (stem, entry) -> entry.rank?.let { stem to it } } + extra).sortedBy { it.second }
        val known = words.toHashSet()
        val result = ArrayList<String>(words.size + inserted.size)
        var next = 0
        for ((index, word) in words.withIndex()) {
            while (next < inserted.size && inserted[next].second <= index) {
                inserted[next].first.takeIf { it !in known }?.let(result::add)
                next++
            }
            result += word
        }
        while (next < inserted.size) {
            inserted[next].first.takeIf { it !in known }?.let(result::add)
            next++
        }
        return result
    }

    /** 返回上屏写法：可直接替换的缩写执行替换，i 开头的缩写首字母大写（i'm → I'm）。 */
    fun display(word: String): String {
        val entry = entries[word]?.takeIf { !it.offerOnly } ?: return word
        return if (entry.form.startsWith("i'")) "I" + entry.form.drop(1) else entry.form
    }

    /** 返回表内全部缩写写法（i 开头者首字母大写），顺序与表一致。 */
    fun forms(): List<String> = entries.values.map { if (it.form.startsWith("i'")) "I" + it.form.drop(1) else it.form }

    /** 返回仅作为候选提供的缩写；原词本身常用时不执行替换。不存在时返回空。 */
    fun offer(word: String): String? = entries[word]?.takeIf { it.offerOnly }?.form
}
