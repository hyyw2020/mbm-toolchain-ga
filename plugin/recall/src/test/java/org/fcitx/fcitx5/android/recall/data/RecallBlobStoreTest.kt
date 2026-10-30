/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * RecallBlobStore 单元测试（MBM v1.0.31「历史无上限」的存储侧）。
 * 与主输入法 ClipboardBlobStore 同构（两个 APK 独立分发，不能共用代码），
 * 因此测试也各自独立覆盖：sha256 寻址 / 去重 / .tmp 清理 / 孤儿回收 / 100MB 规模。
 */

package org.fcitx.fcitx5.android.recall.data

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

class RecallBlobStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File

    private fun setupDir(): File {
        dir = tmp.newFolder("recall_blobs")
        return dir
    }

    @Test
    fun `write then open returns identical bytes`() {
        val d = setupDir()
        val text = "屏幕捕获记忆".repeat(500)
        val bytes = text.toByteArray()
        val handle = RecallBlobStore.writeTo(d, ByteArrayInputStream(bytes), null)
        Assert.assertEquals(bytes.size.toLong(), handle.byteLen)
        Assert.assertEquals(text.take(RecallBlobStore.PREVIEW_CHARS), handle.preview)
        Assert.assertArrayEquals(
            bytes,
            RecallBlobStore.openIn(d, handle.sha256)!!.use { it.readBytes() }
        )
        Assert.assertEquals(bytes.size.toLong(), RecallBlobStore.totalBytesIn(d))
    }

    @Test
    fun `sha256 matches independent digest`() {
        val d = setupDir()
        val bytes = "哈希自检".repeat(2000).toByteArray()
        val expected = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val handle = RecallBlobStore.writeTo(d, ByteArrayInputStream(bytes), null)
        Assert.assertEquals(expected, handle.sha256)
        // 文件名就是 sha256（内容寻址）
        Assert.assertTrue(File(d, "${handle.sha256}.bin").exists())
    }

    @Test
    fun `same content dedups to one file`() {
        val d = setupDir()
        val bytes = "重复捕获".repeat(300).toByteArray()
        val h1 = RecallBlobStore.writeTo(d, ByteArrayInputStream(bytes), null)
        val h2 = RecallBlobStore.writeTo(d, ByteArrayInputStream(bytes), null)
        Assert.assertEquals(h1.sha256, h2.sha256)
        Assert.assertEquals(bytes.size.toLong(), RecallBlobStore.totalBytesIn(d))
        Assert.assertEquals(0, RecallBlobStore.cleanupTmpFilesIn(d))
    }

    @Test
    fun `stale tmp files are cleaned`() {
        val d = setupDir()
        File(d, "a.tmp").writeBytes(ByteArray(4))
        File(d, "b.bin").writeBytes(ByteArray(4))
        Assert.assertEquals(1, RecallBlobStore.cleanupTmpFilesIn(d))
        Assert.assertEquals(4L, RecallBlobStore.totalBytesIn(d))
    }

    @Test
    fun `orphan blobs are collected and delete works`() {
        val d = setupDir()
        val a = RecallBlobStore.writeTo(d, ByteArrayInputStream("甲".toByteArray()), null)
        val b = RecallBlobStore.writeTo(d, ByteArrayInputStream("乙".toByteArray()), null)
        Assert.assertEquals(1, RecallBlobStore.deleteOrphansIn(d, setOf(a.sha256)))
        Assert.assertNull(RecallBlobStore.openIn(d, b.sha256))
        Assert.assertTrue(RecallBlobStore.deleteIn(d, a.sha256))
        Assert.assertEquals(0L, RecallBlobStore.totalBytesIn(d))
    }

    @Test
    fun `open missing blob returns null`() {
        val d = setupDir()
        Assert.assertNull(RecallBlobStore.openIn(d, "f".repeat(64)))
    }

    // ---- 100MB 规模 ----

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
        val handle = RecallBlobStore.writeTo(d, PatternInputStream(size), null)
        Assert.assertEquals(size, handle.byteLen)
        Assert.assertEquals(256, handle.preview.length)
        var pos = 0L
        var mismatches = 0
        RecallBlobStore.openIn(d, handle.sha256)!!.use { s ->
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
        Assert.assertEquals(0, mismatches)
        Assert.assertEquals(size, pos)
    }

    // ---- MBM v1.0.34：blob 静态加密（R3 第一期，与主输入法同构） ----

    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val wrongKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

    @Test
    fun `encrypted write then open returns identical bytes`() {
        val d = setupDir()
        val text = "屏幕捕获加密记忆".repeat(20000)
        val bytes = text.toByteArray()
        val handle = RecallBlobStore.writeTo(d, ByteArrayInputStream(bytes), key)
        Assert.assertEquals(bytes.size.toLong(), handle.byteLen)
        Assert.assertEquals(text.take(RecallBlobStore.PREVIEW_CHARS), handle.preview)
        // 落盘是密文：长度 = 封装后长度，且不含明文片段
        val file = File(d, "${handle.sha256}.bin")
        Assert.assertEquals(StreamingFileCipher.encodedLength(bytes.size.toLong()), file.length())
        Assert.assertFalse(
            "plaintext leaked",
            file.readBytes().toString(Charsets.ISO_8859_1).contains(text.take(64))
        )
        Assert.assertArrayEquals(
            bytes,
            RecallBlobStore.openIn(d, handle.sha256, key)!!.use { it.readBytes() }
        )
    }

    @Test
    fun `legacy plaintext blob still readable with key`() {
        val d = setupDir()
        // 模拟 v1.0.33 及以前写入的明文 blob
        val bytes = "升级前的明文捕获".repeat(100).toByteArray()
        val legacy = RecallBlobStore.writeTo(d, ByteArrayInputStream(bytes), null) // key=null → 明文
        val file = File(d, "${legacy.sha256}.bin")
        Assert.assertEquals("legacy file is plaintext", bytes.size.toLong(), file.length())
        Assert.assertArrayEquals(
            bytes,
            RecallBlobStore.openIn(d, legacy.sha256, key)!!.use { it.readBytes() }
        )
    }

    @Test
    fun `legacy plaintext is lazily migrated on dedup hit`() {
        val d = setupDir()
        val bytes = "惰性迁移捕获内容".repeat(500).toByteArray()
        val legacy = RecallBlobStore.writeTo(d, ByteArrayInputStream(bytes), null)
        val plainLen = File(d, "${legacy.sha256}.bin").length()
        val again = RecallBlobStore.writeTo(d, ByteArrayInputStream(bytes), key)
        Assert.assertEquals(legacy.sha256, again.sha256)
        val file = File(d, "${legacy.sha256}.bin")
        Assert.assertEquals(
            "migrated to ciphertext",
            StreamingFileCipher.encodedLength(bytes.size.toLong()),
            file.length()
        )
        Assert.assertTrue("migrated file is ciphertext", plainLen != file.length())
        Assert.assertArrayEquals(
            bytes,
            RecallBlobStore.openIn(d, again.sha256, key)!!.use { it.readBytes() }
        )
        Assert.assertEquals(0, RecallBlobStore.cleanupTmpFilesIn(d))
    }

    @Test
    fun `same content dedups with encryption key`() {
        val d = setupDir()
        val bytes = "重复加密捕获".repeat(1000).toByteArray()
        val h1 = RecallBlobStore.writeTo(d, ByteArrayInputStream(bytes), key)
        val h2 = RecallBlobStore.writeTo(d, ByteArrayInputStream(bytes), key)
        Assert.assertEquals(h1.sha256, h2.sha256)
        Assert.assertEquals(
            StreamingFileCipher.encodedLength(bytes.size.toLong()),
            RecallBlobStore.totalBytesIn(d)
        )
        Assert.assertEquals(0, RecallBlobStore.cleanupTmpFilesIn(d))
    }

    @Test
    fun `wrong key read fails authentication`() {
        val d = setupDir()
        val bytes = "错密钥必须读不出".repeat(100).toByteArray()
        val handle = RecallBlobStore.writeTo(d, ByteArrayInputStream(bytes), key)
        val s = RecallBlobStore.openIn(d, handle.sha256, wrongKey)!!
        Assert.assertThrows(AEADBadTagException::class.java) { s.readBytes() }
    }

    @Test
    fun `empty input encrypted round trip`() {
        val d = setupDir()
        val handle = RecallBlobStore.writeTo(d, ByteArrayInputStream(ByteArray(0)), key)
        Assert.assertEquals(0L, handle.byteLen)
        Assert.assertEquals("空明文只落 完整头+终结块+尾部", StreamingFileCipher.encodedLength(0), File(d, "${handle.sha256}.bin").length())
        Assert.assertEquals(
            0,
            RecallBlobStore.openIn(d, handle.sha256, key)!!.use { it.readBytes() }.size
        )
    }

    @Test
    fun `100MB encrypted round trip is byte identical`() {
        val d = setupDir()
        val size = 100L * 1024 * 1024
        val handle = RecallBlobStore.writeTo(d, PatternInputStream(size), key)
        Assert.assertEquals(size, handle.byteLen)
        var pos = 0L
        var mismatches = 0
        RecallBlobStore.openIn(d, handle.sha256, key)!!.use { s ->
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
        Assert.assertEquals(0, mismatches)
        Assert.assertEquals(size, pos)
    }
}
