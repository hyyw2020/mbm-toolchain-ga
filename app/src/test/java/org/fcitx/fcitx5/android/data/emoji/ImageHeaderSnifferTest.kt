/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * v1.0.76 回归：ContentProvider 输入流分块返回 / 返回 0 / 文件本身短于
 * 嗅探缓冲区时，仍必须正确识别真实图像、且不把补 0 的尾部当文件头。
 */
package org.fcitx.fcitx5.android.data.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageHeaderSnifferTest {

    private fun pngHeader(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52
    )

    @Test
    fun singleReadDetectsPng() {
        val s = ImageHeaderSniffer()
        val head = pngHeader()
        s.accept(head, 0, head.size)
        assertEquals(ClipboardStickerExtractor.SNIFF_BYTES, s.validBytes)
        assertTrue(s.isFull)
        assertEquals("image/png", s.result())
    }

    @Test
    fun byteByByteReadStillDetectsPng() {
        // 旧实现只在第一次 read 后判定，1 字节分块会让 PNG 签名永远凑不齐
        val s = ImageHeaderSniffer()
        pngHeader().forEach { b -> s.accept(byteArrayOf(b), 0, 1) }
        assertEquals(ClipboardStickerExtractor.SNIFF_BYTES, s.validBytes)
        assertEquals("image/png", s.result())
    }

    @Test
    fun twoChunkReadDetectsPng() {
        val s = ImageHeaderSniffer()
        val head = pngHeader()
        s.accept(head, 0, 4)
        assertNull(s.result())
        s.accept(head, 4, head.size - 4)
        assertEquals("image/png", s.result())
    }

    @Test
    fun zeroLengthReadDoesNotPoisonHeader() {
        val s = ImageHeaderSniffer()
        s.accept(ByteArray(16), 0, 0)
        assertEquals(0, s.validBytes)
        assertNull(s.result())
        val head = pngHeader()
        s.accept(head, 0, head.size)
        assertEquals("image/png", s.result())
    }

    @Test
    fun shortFileIsJudgedByValidBytes() {
        // 真实文件可能短于 16 字节缓冲区：不能用数组长度把补 0 的尾部当数据
        val s = ImageHeaderSniffer()
        val gif = "GIF89a".toByteArray()
        s.accept(gif, 0, gif.size)
        assertEquals(gif.size, s.validBytes)
        assertFalse(s.isFull)
        assertEquals("image/gif", s.result())
    }

    @Test
    fun nonImageIsRejectedEvenWithPaddedBuffer() {
        val s = ImageHeaderSniffer()
        s.accept("hello world 123456".toByteArray(), 0, 18)
        assertNull(s.result())
    }

    @Test
    fun outOfRangeOffsetIsClamped() {
        val s = ImageHeaderSniffer()
        val head = pngHeader()
        s.accept(head, off = 999, len = head.size)
        assertEquals(0, s.validBytes)
        s.accept(head, off = 0, len = 999)
        assertEquals(ClipboardStickerExtractor.SNIFF_BYTES, s.validBytes)
        assertEquals("image/png", s.result())
    }
}
