/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * ClipboardBlobStore 单元测试（MBM v1.0.31「100MB 原文一字不差」的存储侧）：
 *  - sha256 内容寻址（写入/读回一致、同内容去重）；
 *  - preview 只取前 256 字符；
 *  - .tmp 原子 rename 与崩溃残留清理；
 *  - 孤儿 blob 回收；
 *  - 100MB 规模流式往返（测试自身不占大堆）。
 */

package org.fcitx.fcitx5.android.data.clipboard

import org.fcitx.fcitx5.android.data.crypto.StreamingFileCipher
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

class ClipboardBlobStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File

    private fun setupDir(): File {
        dir = tmp.newFolder("blobs")
        return dir
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /** 模式流（[PatternInputStream]）的 sha256，用于独立对照。 */
    private fun patternSha(size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        val s = PatternInputStream(size)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readAll(stream: InputStream): ByteArray =
        stream.use { it.readBytes() }

    // ---- 基础写入 / 读回 ----

    @Test
    fun `write then open returns identical bytes`() {
        val d = setupDir()
        val text = "无上限剪贴板".repeat(1000) // 15KB < 内联阈值，但仍走 blob 接口验证
        val bytes = text.toByteArray()
        val handle = ClipboardBlobStore.writeTo(d, ByteArrayInputStream(bytes), null)
        Assert.assertEquals(sha256Hex(bytes), handle.sha256)
        Assert.assertEquals(bytes.size.toLong(), handle.byteLen)
        Assert.assertEquals(text.take(ClipboardBlobStore.PREVIEW_CHARS), handle.preview)
        // 文件落盘且内容一致
        val file = File(d, "${handle.sha256}.bin")
        Assert.assertTrue(file.exists())
        Assert.assertArrayEquals(bytes, readAll(ClipboardBlobStore.openIn(d, handle.sha256)!!))
        // 元数据查询
        Assert.assertEquals(1, ClipboardBlobStore.blobCountIn(d))
        Assert.assertEquals(bytes.size.toLong(), ClipboardBlobStore.totalBytesIn(d))
        Assert.assertTrue(ClipboardBlobStore.existsIn(d, handle.sha256))
        Assert.assertEquals(bytes.size.toLong(), ClipboardBlobStore.byteLenIn(d, handle.sha256))
    }

    @Test
    fun `preview truncated to 256 chars`() {
        val d = setupDir()
        val text = "甲".repeat(1000) // 1000 字 → preview 应只有 256 字
        val handle = ClipboardBlobStore.writeTo(d, ByteArrayInputStream(text.toByteArray()), null)
        Assert.assertEquals(256, handle.preview.length)
        Assert.assertEquals("甲".repeat(256), handle.preview)
    }

    @Test
    fun `open missing blob returns null`() {
        val d = setupDir()
        Assert.assertNull(ClipboardBlobStore.openIn(d, "0".repeat(64)))
    }

    // ---- 内容寻址去重 ----

    @Test
    fun `same content dedups to one file`() {
        val d = setupDir()
        val bytes = "重复内容".repeat(500).toByteArray()
        val h1 = ClipboardBlobStore.writeTo(d, ByteArrayInputStream(bytes), null)
        val h2 = ClipboardBlobStore.writeTo(d, ByteArrayInputStream(bytes), null)
        Assert.assertEquals(h1.sha256, h2.sha256)
        Assert.assertEquals(1, ClipboardBlobStore.blobCountIn(d))
        Assert.assertEquals(bytes.size.toLong(), ClipboardBlobStore.totalBytesIn(d))
        // 不留 .tmp 残留
        Assert.assertEquals(0, ClipboardBlobStore.cleanupTmpFilesIn(d))
    }

    // ---- .tmp 清理 ----

    @Test
    fun `stale tmp files are cleaned`() {
        val d = setupDir()
        d.mkdirs()
        File(d, "stale1.tmp").writeBytes(ByteArray(10))
        File(d, "stale2.tmp").writeBytes(ByteArray(10))
        File(d, "keepme.bin").writeBytes(ByteArray(10)) // 正式文件不能误删
        Assert.assertEquals(2, ClipboardBlobStore.cleanupTmpFilesIn(d))
        Assert.assertEquals(1, ClipboardBlobStore.blobCountIn(d))
    }

    // ---- 孤儿回收 ----

    @Test
    fun `orphan blobs are collected`() {
        val d = setupDir()
        val a = ClipboardBlobStore.writeTo(d, ByteArrayInputStream("内容A".toByteArray()), null)
        val b = ClipboardBlobStore.writeTo(d, ByteArrayInputStream("内容B".toByteArray()), null)
        Assert.assertEquals(2, ClipboardBlobStore.blobCountIn(d))
        // DB 只剩 a 的引用 → b 是孤儿
        Assert.assertEquals(1, ClipboardBlobStore.deleteOrphansIn(d, setOf(a.sha256)))
        Assert.assertEquals(1, ClipboardBlobStore.blobCountIn(d))
        Assert.assertNull(ClipboardBlobStore.openIn(d, b.sha256))
        Assert.assertNotNull(ClipboardBlobStore.openIn(d, a.sha256))
        Assert.assertTrue(ClipboardBlobStore.deleteIn(d, a.sha256))
        Assert.assertEquals(0, ClipboardBlobStore.blobCountIn(d))
    }

    // ---- 100MB 规模（流式往返） ----

    /** 纯 ASCII 可打印循环（天然合法 UTF-8），按位置确定性生成，不占内存。 */
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

    @Test
    fun `100MB streamed round trip is byte identical`() {
        val d = setupDir()
        val size = 100L * 1024 * 1024
        val gen = PatternInputStream(size)
        val handle = ClipboardBlobStore.writeTo(d, gen, null)
        Assert.assertEquals("100MB 字节数", size, handle.byteLen)
        // 独立算一遍 sha256 对照
        Assert.assertEquals(patternSha(size), handle.sha256)
        // 逐字节读回校验（流式，不整篇进内存）
        var pos = 0L
        var mismatches = 0
        ClipboardBlobStore.openIn(d, handle.sha256)!!.use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    if (buf[i] != (32 + ((pos + i) % 95)).toByte()) mismatches++
                }
                pos += n
            }
        }
        Assert.assertEquals("100MB 一字不差", 0, mismatches)
        Assert.assertEquals(size, pos)
        // preview 只有前 256 字符，DB 行不会撑爆 CursorWindow
        Assert.assertEquals(256, handle.preview.length)
    }

    // ---- MBM v1.0.34：blob 静态加密（R3 第一期） ----

    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val wrongKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun `encrypted write then open returns identical bytes`() {
        val d = setupDir()
        val text = "加密剪贴板内容".repeat(20000) // ~120KB
        val bytes = text.toByteArray()
        val handle = ClipboardBlobStore.writeTo(d, ByteArrayInputStream(bytes), key)
        // sha256 仍按明文计算（内容寻址语义不变）
        Assert.assertEquals(sha256Hex(bytes), handle.sha256)
        Assert.assertEquals(bytes.size.toLong(), handle.byteLen)
        Assert.assertEquals(text.take(ClipboardBlobStore.PREVIEW_CHARS), handle.preview)
        // 落盘是密文：长度 = 封装后长度，且不含明文片段
        val file = File(d, "${handle.sha256}.bin")
        Assert.assertEquals(StreamingFileCipher.encodedLength(bytes.size.toLong()), file.length())
        Assert.assertFalse(
            "plaintext leaked",
            file.readBytes().toString(Charsets.ISO_8859_1).contains(text.take(64))
        )
        // 读回 = 原文
        Assert.assertArrayEquals(
            bytes,
            readAll(ClipboardBlobStore.openIn(d, handle.sha256, key)!!)
        )
    }

    @Test
    fun `legacy plaintext blob still readable with key`() {
        val d = setupDir()
        // 模拟 v1.0.33 及以前写入的明文 blob（无 magic 头）
        val bytes = "升级前的明文历史".repeat(100).toByteArray()
        val legacy = ClipboardBlobStore.writeTo(d, ByteArrayInputStream(bytes), null) // key=null → 明文
        val file = File(d, "${legacy.sha256}.bin")
        Assert.assertEquals("legacy file is plaintext", bytes.size.toLong(), file.length())
        // 带密钥打开：自动识别为遗留明文，原样返回（升级不丢数据）
        Assert.assertArrayEquals(bytes, readAll(ClipboardBlobStore.openIn(d, legacy.sha256, key)!!))
    }

    @Test
    fun `legacy plaintext is lazily migrated on dedup hit`() {
        val d = setupDir()
        val bytes = "惰性迁移测试内容".repeat(500).toByteArray()
        val legacy = ClipboardBlobStore.writeTo(d, ByteArrayInputStream(bytes), null) // 明文落盘
        val plainLen = File(d, "${legacy.sha256}.bin").length()
        // 同内容再次写入（带密钥）：长度判据不命中 → 覆写为密文
        val again = ClipboardBlobStore.writeTo(d, ByteArrayInputStream(bytes), key)
        Assert.assertEquals(legacy.sha256, again.sha256)
        val file = File(d, "${legacy.sha256}.bin")
        Assert.assertEquals(
            "migrated to ciphertext",
            StreamingFileCipher.encodedLength(bytes.size.toLong()),
            file.length()
        )
        Assert.assertTrue("migrated file is ciphertext", plainLen != file.length())
        Assert.assertArrayEquals(bytes, readAll(ClipboardBlobStore.openIn(d, again.sha256, key)!!))
        // 不留 .tmp 残留
        Assert.assertEquals(0, ClipboardBlobStore.cleanupTmpFilesIn(d))
    }

    @Test
    fun `same content dedups with encryption key`() {
        val d = setupDir()
        val bytes = "重复加密内容".repeat(1000).toByteArray()
        val h1 = ClipboardBlobStore.writeTo(d, ByteArrayInputStream(bytes), key)
        val h2 = ClipboardBlobStore.writeTo(d, ByteArrayInputStream(bytes), key)
        Assert.assertEquals(h1.sha256, h2.sha256)
        Assert.assertEquals(1, ClipboardBlobStore.blobCountIn(d))
        // 只有一个密文文件，无重复写入
        Assert.assertEquals(
            StreamingFileCipher.encodedLength(bytes.size.toLong()),
            ClipboardBlobStore.totalBytesIn(d)
        )
        Assert.assertEquals(0, ClipboardBlobStore.cleanupTmpFilesIn(d))
    }

    @Test
    fun `wrong key read fails authentication`() {
        val d = setupDir()
        val bytes = "错密钥必须读不出".repeat(100).toByteArray()
        val handle = ClipboardBlobStore.writeTo(d, ByteArrayInputStream(bytes), key)
        val s = ClipboardBlobStore.openIn(d, handle.sha256, wrongKey)!!
        Assert.assertThrows(AEADBadTagException::class.java) { s.readBytes() }
    }

    @Test
    fun `empty input encrypted round trip`() {
        val d = setupDir()
        val handle = ClipboardBlobStore.writeTo(d, ByteArrayInputStream(ByteArray(0)), key)
        Assert.assertEquals(0L, handle.byteLen)
        val file = File(d, "${handle.sha256}.bin")
        Assert.assertEquals("空明文只落 完整头+终结块+尾部", StreamingFileCipher.encodedLength(0), file.length())
        Assert.assertEquals(0, readAll(ClipboardBlobStore.openIn(d, handle.sha256, key)!!).size)
    }

    @Test
    fun `100MB encrypted round trip is byte identical`() {
        val d = setupDir()
        val size = 100L * 1024 * 1024
        val handle = ClipboardBlobStore.writeTo(d, PatternInputStream(size), key)
        Assert.assertEquals(size, handle.byteLen)
        Assert.assertEquals(patternSha(size), handle.sha256)
        var pos = 0L
        var mismatches = 0
        ClipboardBlobStore.openIn(d, handle.sha256, key)!!.use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                for (i in 0 until n) {
                    if (buf[i] != (32 + ((pos + i) % 95)).toByte()) mismatches++
                }
                pos += n
            }
        }
        Assert.assertEquals("100MB 加密往返一字不差", 0, mismatches)
        Assert.assertEquals(size, pos)
    }
}
