/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * v1.0.76 回归：剪贴板表情包配额。旧实现达到 MAX_ITEMS_PER_PACK 后直接
 * 拒绝新导入（淘汰逻辑是死代码），且重复复制同一张图无限新增条目，
 * 导致「复制表情」在累计 500 次后永久失效。
 */
package org.fcitx.fcitx5.android.data.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardStickerPackPolicyTest {

    private fun item(file: String) = CustomEmojiItem(file, file, "image/png", 128L)

    @Test
    fun appendsNewItemInOrder() {
        val a = item("clip_a.png")
        val b = item("clip_b.png")
        assertEquals(listOf(a, b), ClipboardStickerPackPolicy.merge(listOf(a), b, 500))
    }

    @Test
    fun duplicateDoesNotGrowAndMovesToEnd() {
        val a = item("clip_a.png")
        val b = item("clip_b.png")
        val merged = ClipboardStickerPackPolicy.merge(listOf(a, b), a, 500)
        assertEquals(2, merged.size)
        assertEquals(listOf(b, a), merged)
    }

    @Test
    fun acceptsNewItemWhenAlreadyAtLimit() {
        // 核心回归：旧实现在这里 return false，新表情永远进不来
        val existing = (1..3).map { item("clip_$it.png") }
        val fresh = item("clip_new.png")
        val merged = ClipboardStickerPackPolicy.merge(existing, fresh, 3)
        assertEquals(3, merged.size)
        assertEquals(fresh, merged.last())
        assertEquals(listOf("clip_2.png", "clip_3.png", "clip_new.png"), merged.map { it.file })
    }

    @Test
    fun repeatedSameImageNeverExhaustsQuota() {
        val same = item("clip_same.png")
        var items = emptyList<CustomEmojiItem>()
        repeat(1000) { items = ClipboardStickerPackPolicy.merge(items, same, 10) }
        assertEquals(1, items.size)
        assertEquals(same, items.single())
    }

    @Test
    fun evictsOldestWhenFull() {
        val existing = (1..500).map { item("clip_$it.png") }
        val merged = ClipboardStickerPackPolicy.merge(existing, item("clip_fresh.png"))
        assertEquals(CustomEmojiProtocol.MAX_ITEMS_PER_PACK, merged.size)
        assertEquals("clip_fresh.png", merged.last().file)
        assertFalse(merged.any { it.file == "clip_1.png" })
        assertTrue(merged.any { it.file == "clip_2.png" })
    }

    @Test
    fun keepFilesContainsOnlyLiveEntries() {
        val items = listOf(item("a.png"), item("b.png"))
        assertEquals(setOf("a.png", "b.png"), ClipboardStickerPackPolicy.keepFiles(items))
    }

    @Test
    fun nonPositiveLimitReturnsEmpty() {
        assertTrue(ClipboardStickerPackPolicy.merge(listOf(item("a.png")), item("b.png"), 0).isEmpty())
    }
}
