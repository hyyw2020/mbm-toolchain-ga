/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Cyletix
 *
 * 移植自 CyIME（https://github.com/Cyletix/CyIME），原路径
 * app/src/test/java/com/kingzcheung/xime/association/EnglishContractionCompletionTest.kt。
 * 测试数据内联，避免依赖文件路径；依 GPLv3 保留原版权与许可。
 */
package org.fcitx.fcitx5.android.recall.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class EnglishContractionCompletionTest {
    private val table = EnglishContractions(
        listOf(
            "im i'm", "ive i've 99", "ill i'll", "id i'd", "youre you're 38",
            "youve you've 149", "youll you'll 184", "youd you'd 249", "hes he's 79",
            "hell he'll 141 ?", "shes she's 143", "shell she'll", "its it's",
            "were we're ?", "well we'll ?", "wed we'd", "whats what's",
            "whatre what're 934", "whatd what'd 754", "dont don't", "cant can't", "wont won't 164",
        ).asSequence()
    )

    private fun apply(typed: String, completions: List<String>, limit: Int = 20) =
        EnglishContractionCompletion.apply(typed, completions, limit) { word ->
            table.display(word).takeIf { it != word } ?: table.offer(word)
        }

    @Test fun fullSpellingPutsTheContractionFirst() {
        assertEquals(
            listOf("I'm", "image", "important", "impact"),
            apply("im", listOf("image", "important", "im", "impact")),
        )
        assertEquals(listOf("what's", "whatsoever"), apply("whats", listOf("whats", "whatsoever")))
        assertEquals(listOf("don't"), apply("dont", listOf("dont")))
        assertEquals(listOf("you're"), apply("youre", emptyList()))
    }

    @Test fun realWordsStayFirstAndTheContractionComesSecond() {
        assertEquals(listOf("it's", "itself"), apply("its", listOf("its", "itself")))
        assertEquals(listOf("we're", "werewolf"), apply("were", listOf("were", "werewolf")))
        assertEquals(listOf("I'll", "illness", "illegal"), apply("ill", listOf("ill", "illness", "illegal")))
        assertEquals(listOf("we'll", "wellness"), apply("well", listOf("well", "wellness")))
        assertEquals(listOf("he'll", "hello"), apply("hell", listOf("hell", "hello")))
        assertEquals(listOf("she'll", "shells"), apply("shell", listOf("shell", "shells")))
    }

    @Test fun prefixCompletionsSwapOnlyNonWords() {
        assertEquals(listOf("do", "don't", "done", "doing"), apply("do", listOf("do", "dont", "done", "doing")))
        assertEquals(listOf("its", "itself"), apply("it", listOf("its", "itself")))
        assertEquals(listOf("we", "were", "well"), apply("we", listOf("we", "were", "well")))
    }

    @Test fun followsTypedCase() {
        assertEquals(listOf("I'm"), apply("Im", listOf("Im")))
        assertEquals(listOf("I'M"), apply("IM", listOf("IM")))
        assertEquals(listOf("Don't", "Done"), apply("Dont", listOf("Dont", "Done")))
        assertEquals(listOf("DON'T"), apply("DONT", listOf("DONT")))
        assertEquals(listOf("Don't", "Done"), apply("Do", listOf("Dont", "Done")))
    }

    @Test fun otherWordsAreUntouched() {
        val words = listOf("hello", "help", "helpful")
        assertSame(words, apply("hel", words))
        assertEquals(20, apply("im", List(20) { "im$it" }).size)
    }

    @Test fun typedApostropheOffersMatchingContractions() {
        fun typed(text: String) = EnglishContractionCompletion.withApostrophe(text, table.forms(), 20)
        assertEquals(listOf("what's", "what're", "what'd"), typed("what'"))
        assertEquals(listOf("don't"), typed("don'"))
        assertEquals(listOf("What's", "What're", "What'd"), typed("What'"))
        assertEquals(listOf("DON'T"), typed("DON'"))
        assertEquals(listOf("I'm", "I've", "I'll", "I'd"), typed("i'"))
        assertEquals(listOf("I'm", "I've", "I'll", "I'd"), typed("I'"))
        assertEquals(listOf("you're", "you've", "you'll", "you'd"), typed("you'"))
        assertEquals(emptyList<String>(), typed("what's"))
        assertEquals(emptyList<String>(), typed("xyz'"))
    }
}
