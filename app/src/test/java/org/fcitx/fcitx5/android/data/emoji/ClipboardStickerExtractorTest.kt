/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 剪贴板图像提取器 JVM 单测：多形态 URI 提取 + 魔数嗅探 + 扩展名映射。
 * 覆盖 v1.0.75 修复的「只认 image 类 MIME 声明 + Item.uri」窄门回归。
 */
package org.fcitx.fcitx5.android.data.emoji

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardStickerExtractorTest {

    // ---------------------------------------------------------------- URI 提取

    @Test
    fun extractPlainContentUri() {
        val s = "content://media/external/images/42"
        assertEquals(listOf(s), ClipboardStickerExtractor.extractContentUris(s))
    }

    @Test
    fun extractUriListSeparatedByNewline() {
        val s = "content://a.provider/img/1\ncontent://b.provider/img/2"
        assertEquals(2, ClipboardStickerExtractor.extractContentUris(s).size)
    }

    @Test
    fun extractImgSrcFromHtml() {
        val s = "<p>hi</p><img src=\"content://stickers/p/9\" width=\"32\"/>"
        assertEquals(
            listOf("content://stickers/p/9"),
            ClipboardStickerExtractor.extractContentUris(s)
        )
    }

    @Test
    fun extractEmojiShortCodeWrappedText() {
        // 用户实测：复制表情后剪贴板只有乱码/短码文本，不应误判出 URI
        val s = "¨Ľ🐸Ľ /微笑 [憨笑]"
        assertTrue(ClipboardStickerExtractor.extractContentUris(s).isEmpty())
    }

    @Test
    fun ignoresNonContentSchemes() {
        val s = "https://example.com/a.png file:///sdcard/b.png content://ok/img/3"
        assertEquals(
            listOf("content://ok/img/3"),
            ClipboardStickerExtractor.extractContentUris(s)
        )
    }

    @Test
    fun rejectsMalformedAndDedupes() {
        val s = "content:// content:///nope content://a/img/1 content://a/img/1"
        assertEquals(listOf("content://a/img/1"), ClipboardStickerExtractor.extractContentUris(s))
    }

    @Test
    fun capsCandidateCount() {
        val sb = StringBuilder()
        repeat(20) { sb.append("content://p/img/").append(it).append('\n') }
        assertEquals(
            ClipboardStickerExtractor.MAX_CANDIDATES,
            ClipboardStickerExtractor.extractContentUris(sb.toString()).size
        )
    }

    // ---------------------------------------------------------------- 魔数嗅探

    private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { i -> v[i].toByte() }

    @Test
    fun sniffPng() {
        val head = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13)
        assertEquals("image/png", ClipboardStickerExtractor.sniffImageMime(head))
    }

    @Test
    fun sniffJpeg() {
        assertEquals("image/jpeg", ClipboardStickerExtractor.sniffImageMime(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
    }

    @Test
    fun sniffGif() {
        // v1.0.76：GIF 必须完整 6 字节签名（GIF87a / GIF89a）
        assertEquals(
            "image/gif",
            ClipboardStickerExtractor.sniffImageMime(bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x61))
        )
        assertEquals(
            "image/gif",
            ClipboardStickerExtractor.sniffImageMime(bytes(0x47, 0x49, 0x46, 0x38, 0x37, 0x61))
        )
    }

    @Test
    fun sniffWebp() {
        val h = ByteArray(16)
        "RIFF".forEachIndexed { i, c -> h[i] = c.code.toByte() }
        "WEBP".forEachIndexed { i, c -> h[8 + i] = c.code.toByte() }
        assertEquals("image/webp", ClipboardStickerExtractor.sniffImageMime(h))
    }

    @Test
    fun sniffBmp() {
        // v1.0.76：BMP 必须有 14 字节 BITMAPFILEHEADER 且 bfSize 合法
        val h = bytes(
            0x42, 0x4D, 0x1A, 0x00, 0x00, 0x00, // "BM" + bfSize = 26
            0x00, 0x00, 0x00, 0x00,             // reserved
            0x1A, 0x00, 0x00, 0x00              // bfOffBits = 26
        )
        assertEquals("image/bmp", ClipboardStickerExtractor.sniffImageMime(h))
    }

    @Test
    fun sniffRejectsWeakSignatures() {
        // 旧实现只要 "GIF8" / "BM" 就判为图像，纯文本会被误收进表情面板
        assertNull(ClipboardStickerExtractor.sniffImageMime(bytes(0x47, 0x49, 0x46, 0x38)))
        assertNull(ClipboardStickerExtractor.sniffImageMime(bytes(0x47, 0x49, 0x46, 0x38, 0x39, 0x62)))
        assertNull(ClipboardStickerExtractor.sniffImageMime(bytes(0x42, 0x4D, 0x00, 0x00)))
        assertNull(ClipboardStickerExtractor.sniffImageMime("BMW 汽车".toByteArray()))
        // 够 14 字节、但保留字段非 0 的普通文本同样不能算 BMP
        assertNull(ClipboardStickerExtractor.sniffImageMime("BM hello world 1234567890".toByteArray()))
        val badBmp = ByteArray(14).also { it[0] = 0x42; it[1] = 0x4D; it[2] = 0x02 }
        assertNull(ClipboardStickerExtractor.sniffImageMime(badBmp))
    }

    @Test
    fun sniffUsesValidBytesNotArrayLength() {
        // 固定长缓冲区末尾补 0，判定必须只看有效字节数
        val buf = ByteArray(16)
        val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        System.arraycopy(png, 0, buf, 0, png.size)
        assertEquals("image/png", ClipboardStickerExtractor.sniffImageMime(buf, png.size))
        assertNull(ClipboardStickerExtractor.sniffImageMime(buf, 4))
        // 有效字节不足 6 时不能把 "GIF89a" 判成 GIF
        val gif = "GIF89a".toByteArray()
        val gifBuf = ByteArray(16).also { System.arraycopy(gif, 0, it, 0, gif.size) }
        assertEquals("image/gif", ClipboardStickerExtractor.sniffImageMime(gifBuf, gif.size))
        assertNull(ClipboardStickerExtractor.sniffImageMime(gifBuf, 4))
        assertNull(ClipboardStickerExtractor.sniffImageMime(null, 16))
    }

    @Test
    fun sniffHeicAndAvif() {
        fun bmff(brand: String): ByteArray {
            val h = ByteArray(16)
            "ftyp".forEachIndexed { i, c -> h[4 + i] = c.code.toByte() }
            brand.forEachIndexed { i, c -> h[8 + i] = c.code.toByte() }
            return h
        }
        assertEquals("image/heic", ClipboardStickerExtractor.sniffImageMime(bmff("heic")))
        assertEquals("image/avif", ClipboardStickerExtractor.sniffImageMime(bmff("avis")))
    }

    @Test
    fun sniffRejectsNonImageAndShortData() {
        assertNull(ClipboardStickerExtractor.sniffImageMime(null))
        assertNull(ClipboardStickerExtractor.sniffImageMime(bytes(0x50, 0x4B, 0x03, 0x04))) // zip
        assertNull(ClipboardStickerExtractor.sniffImageMime("hello".toByteArray()))
        assertNull(ClipboardStickerExtractor.sniffImageMime(bytes(0x89, 0x50)))
    }

    // ---------------------------------------------------------------- 扩展名

    @Test
    fun extensionMapping() {
        assertEquals(".gif", ClipboardStickerExtractor.extensionForMime("image/gif"))
        assertEquals(".webp", ClipboardStickerExtractor.extensionForMime("image/webp"))
        assertEquals(".jpg", ClipboardStickerExtractor.extensionForMime("image/jpeg"))
        assertEquals(".heic", ClipboardStickerExtractor.extensionForMime("image/heic"))
        assertEquals(".avif", ClipboardStickerExtractor.extensionForMime("image/avif"))
        assertEquals(".png", ClipboardStickerExtractor.extensionForMime(null))
        // 映射出的扩展名必须都在容器白名单内
        listOf("image/png", "image/gif", "image/webp", "image/jpeg", "image/bmp",
            "image/heic", "image/heif", "image/avif").forEach { mime ->
            val ext = ClipboardStickerExtractor.extensionForMime(mime)
            assertTrue("$mime -> $ext 不在白名单", CustomEmojiProtocol.mimeFor("a$ext") != null)
        }
    }
}
