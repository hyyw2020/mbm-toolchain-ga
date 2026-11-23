/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Cyletix
 *
 * 移植自 CyIME（https://github.com/Cyletix/CyIME），原路径
 * app/src/main/java/com/kingzcheung/xime/association/EnglishContractionCompletion.kt。
 * 依 GPLv3 保留原版权与许可；来源说明见仓库 THIRD_PARTY_NOTICES.md 与 LICENSE 附录。
 */
package org.fcitx.fcitx5.android.recall.enhance

/**
 * 英文补全中的缩写处理。缩写表与滑行输入共用 english_contractions.txt。
 * 输入完整的去撇号写法（如 im、whats、its）时，缩写优先出现在补全首位，候选栏首位仍保留输入原词；
 * 补全中去撇号的写法若本身不是常用词（如 dont、whats），替换为缩写；若本身是常用词
 * （如 its、were、well、ill），保留原样。无法识别的写法原样返回。
 */
internal object EnglishContractionCompletion {
    /** 本身即常用英文词的去撇号写法：补全中不替换为缩写。 */
    private val PLAIN_WORDS = setOf("its", "were", "well", "ill", "hell", "shell", "id", "wed", "shed", "lets", "cant", "wont")

    /**
     * 按缩写表重排补全列表。[formOf] 将小写去撇号写法映射为规范缩写（i'm 已首字母大写为 I'm），非缩写返回空。
     */
    fun apply(typed: String, completions: List<String>, limit: Int, formOf: (String) -> String?): List<String> {
        if (typed.isEmpty() || limit <= 0) return completions
        val key = typed.lowercase()
        val typedForm = formOf(key)?.let { cased(it, typed) }
        val shown = completions.map { word ->
            val lower = word.lowercase()
            if (lower in PLAIN_WORDS) word else formOf(lower)?.let { cased(it, typed) } ?: word
        }
        if (typedForm == null && shown == completions) return completions
        val result = ArrayList<String>(completions.size + 1)
        typedForm?.let(result::add)
        for ((index, word) in shown.withIndex()) {
            if (typedForm != null && completions[index].lowercase() == key) continue
            if (word !in result) result += word
        }
        return result.take(limit)
    }

    /**
     * 输入已含撇号（如 what'、don't）时的补全。[forms] 为缩写表中以输入开头的写法（I'm 已首字母大写），
     * 大小写跟随输入，与输入完全一致的写法不再重复。仅一个字母加撇号（I'、i'）时只跟随首字母大小写。
     */
    fun withApostrophe(typed: String, forms: List<String>, limit: Int): List<String> {
        if (typed.isEmpty() || limit <= 0) return emptyList()
        val key = typed.lowercase()
        val oneLetter = typed.count(Char::isLetter) <= 1
        return forms.filter { it.lowercase().startsWith(key) && it.lowercase() != key }
            .map {
                if (!oneLetter) cased(it, typed)
                else if (typed.first().isUpperCase()) it.replaceFirstChar(Char::uppercaseChar)
                else it
            }
            .distinct()
            .take(limit)
    }

    /** 大小写跟随输入：全大写则缩写全大写（DON'T），首字母大写则缩写首字母大写（Don't）。 */
    private fun cased(form: String, typed: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> form.uppercase()
        typed.first().isUpperCase() -> form.replaceFirstChar(Char::uppercaseChar)
        else -> form
    }
}
