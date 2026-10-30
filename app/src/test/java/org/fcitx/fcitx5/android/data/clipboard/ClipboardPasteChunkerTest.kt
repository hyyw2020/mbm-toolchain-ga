/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * ClipboardPasteChunker 单元测试（MBM v1.0.31「粘贴=完整原文」核心）：
 *  - UTF-8 跨块边界（中文 3 字节 / emoji 4 字节 / ASCII 1 字节混排）；
 *  - 分块拼接 == 原文（一字不差）；
 *  - 失败即停 + 已提交字节数；
 *  - 100MB 规模（流式生成/校验，测试自身不占大堆）。
 */

package org.fcitx.fcitx5.android.data.clipboard

import org.junit.Assert
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking

class ClipboardPasteChunkerTest {

    // ---- 确定性字节流：100MB 规模也不占内存 ----

    /** 纯 ASCII 可打印循环（天然合法 UTF-8），按位置确定性生成。 */
    private class PatternInputStream(val size: Long) : InputStream() {
        var pos = 0L
            private set

        private fun byteAt(p: Long) = (32 + (p % 95)).toByte()

        override fun read(): Int =
            if (pos >= size) -1 else byteAt(pos++).toInt() and 0xFF

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= size) return -1
            val n = minOf(len.toLong(), size - pos).toInt()
            for (i in 0 until n) b[off + i] = byteAt(pos + i)
            pos += n
            return n
        }
    }

    private fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- incompleteUtf8Tail ----

    @Test
    fun `utf8 tail detects incomplete lead bytes`() {
        val f = ClipboardPasteChunker::incompleteUtf8Tail
        // ASCII：完整
        Assert.assertEquals(0, f("abc".toByteArray(), 3))
        // 「中」= E4 B8 AD：完整 3 字节
        Assert.assertEquals(0, f("中".toByteArray(), 3))
        // 只有首字节 E4
        Assert.assertEquals(1, f(byteArrayOf(0x61, 0xE4.toByte()), 2))
        // E4 B8（3 字节序列的 2/3）
        Assert.assertEquals(2, f(byteArrayOf(0xE4.toByte(), 0xB8.toByte()), 2))
        // 4 字节 emoji「😀」= F0 9F 98 80：完整
        Assert.assertEquals(0, f("😀".toByteArray(), 4))
        // F0 / F0 9F / F0 9F 98 分别缺 3/2/1 字节
        Assert.assertEquals(1, f(byteArrayOf(0xF0.toByte()), 1))
        Assert.assertEquals(2, f(byteArrayOf(0xF0.toByte(), 0x9F.toByte()), 2))
        Assert.assertEquals(3, f(byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte()), 3))
        // 非法前导字节：当完整处理（不误伤）
        Assert.assertEquals(0, f(byteArrayOf(0xFF.toByte()), 1))
        // 连续 continuation 超过 4 个：循环保护
        Assert.assertEquals(0, f(ByteArray(6) { 0x80.toByte() }, 6))
    }

    // ---- chunks：拼接完整性 ----

    @Test
    fun `chunks join back to original with tiny sizes`() {
        // 中文 + ASCII + emoji 混排，专门砸 UTF-8 边界
        val unit = "中文测试abc😀混合XYZ🎉结尾"
        val original = unit.repeat(5000) // ~120KB
        val bytes = original.toByteArray()
        for (size in intArrayOf(1, 2, 3, 4, 5, 7, 13, 64)) {
            val chunks = mutableListOf<String>()
            val committed = runBlocking {
                ClipboardPasteChunker.chunks(
                    ByteArrayInputStream(bytes), size
                ) { text, _ -> chunks += text; true }
            }
            Assert.assertEquals("committed@size=$size", bytes.size.toLong(), committed)
            Assert.assertEquals("join@size=$size", original, chunks.joinToString(""))
            // 任何块都不含替换符（说明没有字符被切断）
            Assert.assertTrue("no U+FFFD@size=$size", chunks.none { it.contains('\uFFFD') })
        }
    }

    @Test
    fun `chunks with production chunk size round trips`() {
        val original = "无上限剪贴板".repeat(20000) // ~360KB > 256KB，必分多块
        val bytes = original.toByteArray()
        val chunks = mutableListOf<String>()
        val committed = runBlocking {
            ClipboardPasteChunker.chunks(ByteArrayInputStream(bytes)) { text, _ ->
                chunks += text; true
            }
        }
        Assert.assertEquals(bytes.size.toLong(), committed)
        Assert.assertEquals(original, chunks.joinToString(""))
        Assert.assertTrue("should split into multiple chunks", chunks.size > 1)
    }

    @Test
    fun `chunks stops on failure and reports committed bytes`() {
        val original = "失败即停测试".repeat(40000) // ~640KB → 3 块
        val bytes = original.toByteArray()
        var chunkIndex = 0
        val committed = runBlocking {
            ClipboardPasteChunker.chunks(
                ByteArrayInputStream(bytes), 256 * 1024
            ) { _, _ ->
                chunkIndex++
                chunkIndex <= 1 // 第一块成功，第二块起失败
            }
        }
        Assert.assertEquals("只处理了第一块（第二块失败即停）", 2, chunkIndex)
        // 第一块 ≈ 256KB（3 字节中文可能剩 1~2 字节 carry 到下一轮，已停在失败块）
        Assert.assertTrue("committed=$committed", committed in (256 * 1024 - 4)..(256 * 1024).toLong())
        Assert.assertTrue("committed < total", committed < bytes.size)
    }

    @Test
    fun `chunks empty input produces nothing`() {
        val chunks = mutableListOf<String>()
        val committed = runBlocking {
            ClipboardPasteChunker.chunks(ByteArrayInputStream(ByteArray(0))) { t, _ ->
                chunks += t; true
            }
        }
        Assert.assertEquals(0L, committed)
        Assert.assertTrue(chunks.isEmpty())
    }

    @Test
    fun `chunks tolerates mid-stream read errors`() {
        val data = "读流出错也要优雅停止".repeat(20000).toByteArray()
        var served = 0
        val flaky = object : InputStream() {
            override fun read(): Int = throw java.io.IOException("boom")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served >= data.size / 2) throw java.io.IOException("boom")
                val n = minOf(len, data.size / 2 - served)
                System.arraycopy(data, served, b, off, n)
                served += n
                return n
            }
        }
        val committed = runBlocking {
            ClipboardPasteChunker.chunks(flaky, 64 * 1024) { _, _ -> true }
        }
        // 读错误按 EOF 处理：已提交量 < 总量但不抛异常
        Assert.assertTrue(committed > 0)
        Assert.assertTrue(committed < data.size)
    }

    // ---- 100MB 规模（流式校验，不join大字符串） ----

    @Test
    fun `chunks 100MB scale commits every byte`() {
        val size = 100L * 1024 * 1024 // 100MB
        val chunkSize = ClipboardBlobStore.PASTE_CHUNK_BYTES // 256KB
        var committed = 0L
        var chunkCount = 0
        runBlocking {
            ClipboardPasteChunker.chunks(PatternInputStream(size), chunkSize) { text, byteLen ->
                // 逐字符校验：块内第 i 个字符必须等于模式流的第 (committed + i) 字节
                val base = committed
                for (i in text.indices) {
                    val expected = (32 + ((base + i) % 95)).toInt().toChar()
                    Assert.assertEquals("char at ${base + i}", expected, text[i])
                }
                Assert.assertEquals(byteLen.toLong(), text.length.toLong())
                committed += byteLen
                chunkCount++
                true
            }
        }
        Assert.assertEquals("100MB 全部提交", size, committed)
        Assert.assertEquals("100MB / 256KB = 400 块", 400, chunkCount)
    }

    // ---- 尾部校验 ----

    @Test
    fun `expectedTailText reads stream tail`() {
        // MBM v1.0.34：签名从 (File, Long, Int) 改为 (InputStream, Int)——
        // blob 加密后不能 seek 文件，由 ClipboardBlobStore.open 给出解密流。
        val content = "前缀内容……真正的结尾文字ABC".toByteArray()
        val tail = ClipboardPasteChunker.expectedTailText(ByteArrayInputStream(content), 15)
        Assert.assertNotNull(tail)
        Assert.assertEquals("结尾文字ABC", tail)
        // n 超过流长度：返回全文（调用方 tailMatches 走 16 字符容忍兜底，不误报）
        Assert.assertEquals(
            String(content, Charsets.UTF_8),
            ClipboardPasteChunker.expectedTailText(ByteArrayInputStream(content), 9999)
        )
        // n<=0 与空流：空串
        Assert.assertEquals("", ClipboardPasteChunker.expectedTailText(ByteArrayInputStream(content), 0))
        Assert.assertEquals("", ClipboardPasteChunker.expectedTailText(ByteArrayInputStream(ByteArray(0)), 15))
        // 窗口起点切碎多字节字符：前导替换符被剔除
        val multi = "甲乙丙丁戊己庚辛壬癸".toByteArray() // 30B = 10 个 3 字节汉字
        // 末 10 字节 = [庚尾字节]辛壬癸 → 解码后 "\uFFFD辛壬癸"
        Assert.assertEquals("辛壬癸", ClipboardPasteChunker.expectedTailText(ByteArrayInputStream(multi), 10))
    }

    @Test
    fun `tailMatches detects truncation`() {
        val expected = "这是结尾的一段文字"
        // 完整粘贴：通过
        Assert.assertTrue(ClipboardPasteChunker.tailMatches("前面很多字$expected", expected))
        // maxLength 截断（目标框只留前半）：告警
        Assert.assertFalse(ClipboardPasteChunker.tailMatches("前面很多字这是", expected))
        // 目标应用加工过中部、但最后 16 字符幸存：容忍（takeLast(16) 兜底）
        val long = "abcdefghijklmnopqrstuvwxyz"
        Assert.assertTrue(ClipboardPasteChunker.tailMatches("xxx" + long.takeLast(16), long))
        Assert.assertFalse(ClipboardPasteChunker.tailMatches("xxxabcdefghij", long))
        // 空值放过
        Assert.assertTrue(ClipboardPasteChunker.tailMatches("", expected))
        Assert.assertTrue(ClipboardPasteChunker.tailMatches("abc", ""))
    }

    @Test
    fun `sha helper consistency`() {
        val data = "sha256 自检".repeat(1000).toByteArray()
        Assert.assertEquals(
            sha256Hex(ByteArrayInputStream(data)),
            sha256Hex(ByteArrayInputStream(data))
        )
    }
}
