/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Cyletix
 *
 * 移植自 CyIME（https://github.com/Cyletix/CyIME），原路径
 * app/src/test/java/com/kingzcheung/xime/association/WordContinuationTableTest.kt。
 * 保留纯算法测试；依赖额外联想引擎与生成资产的表集成测试未纳入。依 GPLv3 保留原版权与许可。
 */
package org.fcitx.fcitx5.android.recall.enhance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

class WordContinuationTableTest {
    private val header = WordContinuationTable.HEADER + "\n"

    private fun table() = WordContinuationTable.read(StringReader(
        header +
            "厄尔尼诺\t现象\t1248\t1400\n" +
            "厄尔尼诺\t事件\t83\t1400\n" +
            "尼诺\t曼\t10\t100\n" +
            "诺贝尔\t奖\t50\t100\n"
    ))

    @Test fun theLongestWordTheContextEndsWithIsUsed() {
        val table = table()
        assertEquals(3, table.contextCount)
        val whole = table.lookup("今年的厄尔尼诺")!!
        assertEquals(4, whole.contextLength)
        assertEquals(listOf("现象", "事件"), whole.candidates.map { it.text })
        assertEquals(1248f / 1400f, whole.candidates.first().score, 1e-6f)
        assertEquals(listOf("现象"), table.lookup("厄尔尼诺", 1)!!.candidates.map { it.text })
        val shorter = table.lookup("帕尼诺")!!
        assertEquals(2, shorter.contextLength)
        assertEquals(listOf("曼"), shorter.candidates.map { it.text })
        assertEquals(3, table.lookup("诺贝尔")!!.contextLength)
        assertNull(table.lookup("他说诺"))
        assertNull(table.lookup("厄尔尼诺。"))
        assertNull(table.lookup("hello"))
        assertNull(table.lookup(""))
        assertNull(table.lookup("厄尔尼诺", 0))
    }

    @Test fun onlyAWordLongerThanTheCorpusContextJoins() {
        val words = table().lookup("厄尔尼诺")!!
        val scores = mutableMapOf("一" to 0.07f, "曼" to 0.02f)
        mergeWordContinuations(scores, 1, words, 0.7f)
        assertEquals(listOf("现象", "一", "事件", "曼"), scores.entries.sortedByDescending { it.value }.map { it.key })
        assertEquals(0.7f * 1248f / 1400f, scores.getValue("现象"), 1e-6f)
        val two = table().lookup("尼诺")!!
        val unchanged = mutableMapOf("先生" to 0.3f)
        mergeWordContinuations(unchanged, 2, two, 0.7f)
        assertEquals(mapOf("先生" to 0.3f), unchanged)
        mergeWordContinuations(unchanged, 1, null, 0.7f)
        assertEquals(mapOf("先生" to 0.3f), unchanged)
        val both = mutableMapOf("现象" to 0.9f, "事件" to 0.01f)
        mergeWordContinuations(both, 0, words, 0.7f)
        assertEquals(0.9f, both.getValue("现象"), 1e-6f)
        assertEquals(0.7f * 83f / 1400f, both.getValue("事件"), 1e-6f)
    }

    private fun rejected(rows: String, withHeader: Boolean = true): Boolean = try {
        WordContinuationTable.read(StringReader((if (withHeader) header else "") + rows))
        false
    } catch (expected: IllegalArgumentException) {
        true
    }

    @Test fun malformedFilesAreRejected() {
        assertTrue(rejected("厄尔尼诺\t现象\t1248\t1400\n", withHeader = false))
        assertTrue(rejected("诺贝尔\t奖\t50\t100\n尼诺\t曼\t10\t100\n"))
        assertTrue(rejected("尼诺\t曼\t10\t100\n诺贝尔\t奖\t50\t100\n尼诺\t斯\t5\t100\n"))
        assertTrue(rejected("尼诺\t曼\t10\t100\n尼诺\t斯\t20\t100\n"))
        assertTrue(rejected("尼诺\t甲\t9\t100\n尼诺\t乙\t8\t100\n尼诺\t丙\t7\t100\n尼诺\t丁\t6\t100\n尼诺\t戊\t5\t100\n"))
        assertTrue(rejected("诺\t曼\t10\t100\n"))
        assertTrue(rejected("a诺\t曼\t10\t100\n"))
        assertTrue(rejected("尼诺\t曼\t101\t100\n"))
        assertFalse(rejected("尼诺\t曼\t10\t100\n\n诺贝尔\t奖\t50\t100\n"))
    }
}
