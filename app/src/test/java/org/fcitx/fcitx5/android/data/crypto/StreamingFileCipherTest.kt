/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * StreamingFileCipher 单元测试（MBM v1.0.34 R3 第一期：blob 静态加密）。
 * 纯 JVM 无 Android 依赖 → 直接 JUnit 跑。
 *
 * 覆盖：
 *  - 往返一致性：0 / 1 / chunk-1 / chunk / chunk+1 / 100MB 规模逐字节；
 *  - 文件头格式（magic/version/chunkShift/noncePrefix）；
 *  - encodedLength 精确性（dedup 长度判据依赖它，尾块短必须算对）；
 *  - 认证失败：篡改密文 / 错密钥 / 块重排 → AEADBadTagException；
 *  - 截断：EOFException；
 *  - 遗留明文兼容：无 magic 头原样透读；
 *  - decryptedLength 辅助入口。
 */

package org.fcitx.fcitx5.android.data.crypto

import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

class StreamingFileCipherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val otherKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun patternBytes(size: Long): ByteArray {
        // 不占大内存的校验模式与 blob store 测试一致：纯 ASCII 可打印循环
        return ByteArray(size.toInt()) { (32 + (it % 95)).toByte() }
    }

    private fun encryptToFile(name: String, plain: ByteArray, k: ByteArray = key): File {
        val f = File(tmp.newFolder(), name)
        StreamingFileCipher.EncryptingSink(f.outputStream(), k).use { sink ->
            sink.write(plain)
        }
        return f
    }

    private fun readAll(s: InputStream): ByteArray = s.use { it.readBytes() }

    // ---- 往返一致性（含分块边界） ----

    @Test
    fun `round trip across chunk boundaries`() {
        val cs = StreamingFileCipher.CHUNK_SIZE
        val sizes = longArrayOf(0, 1, cs - 1L, cs.toLong(), cs + 1L, 2 * cs.toLong(), 2 * cs + 1L)
        for (size in sizes) {
            val plain = patternBytes(size)
            val f = encryptToFile("rt_$size.bin", plain)
            // 文件长度必须精确等于 encodedLength（dedup 判据）
            Assert.assertEquals(
                "encodedLength@$size", StreamingFileCipher.encodedLength(size), f.length()
            )
            Assert.assertTrue("isEncrypted@$size", StreamingFileCipher.isEncrypted(f.inputStream().buffered()))
            val back = readAll(StreamingFileCipher.openDecrypted(f, key))
            Assert.assertArrayEquals("roundtrip@$size", plain, back)
            Assert.assertEquals(
                "decryptedLength@$size",
                size,
                StreamingFileCipher.decryptedLength(f, key)
            )
        }
    }

    @Test
    fun `100MB streamed round trip is byte identical`() {
        val size = 100L * 1024 * 1024
        val f = File(tmp.newFolder(), "big.bin")
        // 流式写入（不把 100MB 整篇留在堆里）
        StreamingFileCipher.EncryptingSink(f.outputStream(), key).use { sink ->
            val buf = ByteArray(64 * 1024)
            var written = 0L
            while (written < size) {
                val n = minOf(buf.size.toLong(), size - written).toInt()
                for (i in 0 until n) buf[i] = (32 + ((written + i) % 95)).toByte()
                sink.write(buf, 0, n)
                written += n
            }
        }
        Assert.assertEquals(StreamingFileCipher.encodedLength(size), f.length())
        // 逐字节读回校验
        var pos = 0L
        var mismatches = 0
        StreamingFileCipher.openDecrypted(f, key).use { s ->
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
    }

    @Test
    fun `single byte write path works`() {
        val plain = "逐字节写入".toByteArray()
        val f = File(tmp.newFolder(), "onebyone.bin")
        StreamingFileCipher.EncryptingSink(f.outputStream(), key).use { sink ->
            for (b in plain) sink.write(b.toInt())
        }
        Assert.assertArrayEquals(plain, readAll(StreamingFileCipher.openDecrypted(f, key)))
    }

    // ---- 文件头格式 ----

    @Test
    fun `header format is magic version chunkShift noncePrefix`() {
        val f = encryptToFile("hdr.bin", "头格式自检".toByteArray())
        val h = f.readBytes().copyOfRange(0, StreamingFileCipher.HEADER_SIZE)
        Assert.assertArrayEquals("magic=MBMC", StreamingFileCipher.MAGIC, h.copyOfRange(0, 4))
        Assert.assertEquals(StreamingFileCipher.VERSION.toLong(), h[4].toLong())
        Assert.assertEquals(StreamingFileCipher.CHUNK_SHIFT.toLong(), h[5].toLong())
        Assert.assertEquals(0, h[6].toInt()) // reserved
        Assert.assertEquals(0, h[7].toInt())
        // noncePrefix 是随机 8 字节：不同文件几乎必然不同（防跨文件 nonce 复用）
        val f2 = encryptToFile("hdr2.bin", "头格式自检".toByteArray())
        val h2 = f2.readBytes().copyOfRange(0, StreamingFileCipher.HEADER_SIZE)
        Assert.assertFalse("nonce prefix must differ", h.copyOfRange(8, 16).contentEquals(h2.copyOfRange(8, 16)))
    }

    // ---- 认证失败场景 ----

    @Test
    fun `tampered ciphertext fails authentication`() {
        val plain = patternBytes(1024)
        val f = encryptToFile("tamper.bin", plain)
        val raw = f.readBytes()
        // 篡改第一块正文密文区一个字节（跳过 32B 完整头 + 4B 长度前缀，命中第一块密文）
        val off = StreamingFileCipher.HEADER_FULL_SIZE + StreamingFileCipher.LEN_PREFIX_SIZE + 5
        raw[off] = (raw[off] + 1).toByte()
        f.writeBytes(raw)
        val ex = Assert.assertThrows(AEADBadTagException::class.java) {
            readAll(StreamingFileCipher.openDecrypted(f, key))
        }
        Assert.assertNotNull(ex)
    }

    @Test
    fun `tampered header fails`() {
        val f = encryptToFile("tamperhdr.bin", "头也不能改".toByteArray())
        val raw = f.readBytes()
        raw[4] = 99 // version
        f.writeBytes(raw)
        Assert.assertThrows(IllegalArgumentException::class.java) {
            readAll(StreamingFileCipher.openDecrypted(f, key))
        }
    }

    @Test
    fun `wrong key fails authentication`() {
        val plain = patternBytes(2048)
        val f = encryptToFile("wrongkey.bin", plain)
        Assert.assertThrows(AEADBadTagException::class.java) {
            readAll(StreamingFileCipher.openDecrypted(f, otherKey))
        }
    }

    @Test
    fun `reordered chunks fail authentication`() {
        // 两个满块，整体对调：块序号进 AAD，第二块的密文过不了第 0 块的校验
        val cs = StreamingFileCipher.CHUNK_SIZE
        val plain = patternBytes(2L * cs)
        val f = encryptToFile("reorder.bin", plain)
        val raw = f.readBytes()
        val chunkBytes = cs + StreamingFileCipher.LEN_PREFIX_SIZE + 16
        val swapped = ByteArray(raw.size)
        System.arraycopy(raw, 0, swapped, 0, StreamingFileCipher.HEADER_FULL_SIZE)
        System.arraycopy(
            raw, StreamingFileCipher.HEADER_FULL_SIZE + chunkBytes,
            swapped, StreamingFileCipher.HEADER_FULL_SIZE, chunkBytes
        )
        System.arraycopy(
            raw, StreamingFileCipher.HEADER_FULL_SIZE,
            swapped, StreamingFileCipher.HEADER_FULL_SIZE + chunkBytes, chunkBytes
        )
        f.writeBytes(swapped)
        Assert.assertThrows(AEADBadTagException::class.java) {
            readAll(StreamingFileCipher.openDecrypted(f, key))
        }
    }

    @Test
    fun `truncated file fails loudly`() {
        val cs = StreamingFileCipher.CHUNK_SIZE
        val plain = patternBytes(cs + 100L)
        val f = encryptToFile("trunc.bin", plain)
        val raw = f.readBytes()
        // 砍掉最后 10 字节：落在尾部(32B)里 → 尾部读不全 → EOFException，
        // 绝不会静默读出半截内容
        f.writeBytes(raw.copyOfRange(0, raw.size - 10))
        Assert.assertThrows(EOFException::class.java) {
            readAll(StreamingFileCipher.openDecrypted(f, key))
        }
        // 第二块只剩 5 字节（连长度前缀 + 一个 tag 都不够）→ EOFException
        f.writeBytes(
            raw.copyOfRange(
                0,
                StreamingFileCipher.HEADER_FULL_SIZE + cs +
                    StreamingFileCipher.LEN_PREFIX_SIZE + 16 + 5
            )
        )
        Assert.assertThrows(EOFException::class.java) {
            readAll(StreamingFileCipher.openDecrypted(f, key))
        }
    }

    @Test
    fun `truncated header fails loudly`() {
        val f = encryptToFile("trunchdr.bin", "头被截断".toByteArray())
        val raw = f.readBytes()
        f.writeBytes(raw.copyOfRange(0, 8))
        Assert.assertThrows(EOFException::class.java) {
            readAll(StreamingFileCipher.openDecrypted(f, key))
        }
    }

    @Test
    fun `dropping whole tail and last block fails loudly`() {
        // v1.0.86 真 bug 回归：设计承诺「整块截断（哪怕刚好砍掉 N 个完整块）也能被检出」。
        // 若把末块连尾部 marker(4)+trailer(32) 一起砍掉，readLengthPrefix 会在读到任何
        // 前缀字节前遇到 EOF 返回 null。旧实现把它当成正常结束（eof=true; return false），
        // 从不校验尾部 → 静默返回只剩前面的截断明文，数据丢失且不报错。
        val cs = StreamingFileCipher.CHUNK_SIZE
        val partial = 100
        val plain = patternBytes(cs + partial.toLong())
        val f = encryptToFile("trunctail.bin", plain)
        val raw = f.readBytes()
        // 砍掉「末块(4 前缀 + partial + 16 tag)」+「整个尾部(4 marker + 28 trailer)」
        val lastBlock = StreamingFileCipher.LEN_PREFIX_SIZE + partial + 16
        val drop = lastBlock + StreamingFileCipher.TRAILER_SIZE
        f.writeBytes(raw.copyOfRange(0, raw.size - drop))
        Assert.assertThrows(EOFException::class.java) {
            readAll(StreamingFileCipher.openDecrypted(f, key))
        }
    }

    // ---- 遗留明文兼容 ----

    @Test
    fun `legacy plaintext passes through untouched`() {
        val plain = "v1.0.33 及以前的明文 blob".toByteArray()
        val f = File(tmp.newFolder(), "legacy.bin")
        f.writeBytes(plain)
        Assert.assertFalse(StreamingFileCipher.isEncrypted(f.inputStream().buffered()))
        Assert.assertArrayEquals(plain, readAll(StreamingFileCipher.openDecrypted(f, key)))
    }

    @Test
    fun `empty and tiny streams are not mistaken for ciphertext`() {
        val f = File(tmp.newFolder(), "tiny.bin")
        f.writeBytes(ByteArray(0))
        Assert.assertFalse(StreamingFileCipher.isEncrypted(f.inputStream().buffered()))
        f.writeBytes(byteArrayOf(0x4D)) // 只有 magic 首字节
        Assert.assertFalse(StreamingFileCipher.isEncrypted(f.inputStream().buffered()))
    }

    // ---- encodedLength 精确性 ----

    @Test
    fun `encodedLength matches real file length across sizes`() {
        val cs = StreamingFileCipher.CHUNK_SIZE
        val sizes = listOf(
            0L, 1L, 15L, 16L, 17L, 255L, 1024L,
            cs - 2L, cs - 1L, cs.toLong(), cs + 1L, cs + 2L,
            2 * cs - 1L, 2 * cs.toLong(), 2 * cs + 1L,
            3 * cs + 12345L
        )
        for (size in sizes) {
            val f = encryptToFile("enc_$size.bin", patternBytes(size))
            Assert.assertEquals("size=$size", StreamingFileCipher.encodedLength(size), f.length())
        }
    }

    @Test
    fun `encrypted blob does not leak plaintext prefix`() {
        val secret = "SuperSecretPassword123456".toByteArray()
        val f = encryptToFile("leak.bin", secret)
        val raw = f.readBytes()
        // 密文里不允许出现明文片段（前 8 字节也不允许）
        Assert.assertFalse(
            "plaintext leaked into ciphertext",
            raw.toString(Charsets.ISO_8859_1).contains(String(secret))
        )
    }

    @Test
    fun `decryptingInputStream single byte read path`() {
        val plain = patternBytes(5000)
        val f = encryptToFile("single.bin", plain)
        StreamingFileCipher.DecryptingInputStream(
            ByteArrayInputStream(f.readBytes()), key
        ).use { s ->
            for (i in plain.indices) {
                Assert.assertEquals("byte $i", plain[i].toInt() and 0xFF, s.read())
            }
            Assert.assertEquals(-1, s.read())
        }
    }
}
