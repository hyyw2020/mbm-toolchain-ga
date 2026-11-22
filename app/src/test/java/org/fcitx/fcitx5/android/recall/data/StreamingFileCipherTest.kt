/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * Recall 版 StreamingFileCipher 单元测试（两个 APK 独立分发、代码同构但不可共用）。
 * 纯 JVM 无 Android 依赖 → 直接 JUnit 跑。
 *
 * 本测试文件为 v1.0.88 新增：把 data/crypto 版已验证的 fail-closed 契约同步到
 * recall/data 版。两个实现同构，但此前 recall 版没有任何单测，导致 v1.0.86 的
 * 「整块截断 fail-open」修复只落在了 data/crypto 版，recall 版（RecallBlobStore
 * 屏幕捕获大文本用的就是它）漏修——同一个真 bug 在两个 APK 里只修了一个。
 *
 * 覆盖：
 *  - 往返一致性（合法行为不变）；
 *  - 整块截断 / 全前缀截断 fails loudly（fail-closed 不变量）；
 *  - 遗留明文兼容。
 */

package org.fcitx.fcitx5.android.recall.data

import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.security.SecureRandom

class StreamingFileCipherTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun patternBytes(size: Long): ByteArray =
        ByteArray(size.toInt()) { (32 + (it % 95)).toByte() }

    private fun encryptToFile(name: String, plain: ByteArray, k: ByteArray = key): File {
        val f = File(tmp.newFolder(), name)
        StreamingFileCipher.EncryptingSink(f.outputStream(), k).use { sink ->
            sink.write(plain)
        }
        return f
    }

    private fun readAll(s: InputStream): ByteArray = s.use { it.readBytes() }

    @Test
    fun `round trip across chunk boundaries`() {
        val cs = StreamingFileCipher.CHUNK_SIZE
        val sizes = longArrayOf(0, 1, cs - 1L, cs.toLong(), cs + 1L, 2 * cs + 1L)
        for (size in sizes) {
            val plain = patternBytes(size)
            val f = encryptToFile("rt_$size.bin", plain)
            Assert.assertEquals(
                "encodedLength@$size", StreamingFileCipher.encodedLength(size), f.length()
            )
            Assert.assertArrayEquals("roundtrip@$size", plain, readAll(StreamingFileCipher.openDecrypted(f, key)))
        }
    }

    @Test
    fun `dropping whole tail and last block fails loudly`() {
        // v1.0.86 同源真 bug 回归（recall 版漏修）：设计承诺「整块截断也能被检出」。
        // 若把末块连尾部 marker+trailer 一起砍掉，readLengthPrefix 会在读到任何前缀
        // 字节前遇到 EOF 返回 null。旧实现当成正常结束（eof=true; return false），
        // 从不校验尾部 → 静默返回只剩前面的截断明文，数据丢失且不报错。
        val cs = StreamingFileCipher.CHUNK_SIZE
        val partial = 100
        val plain = patternBytes(cs + partial.toLong())
        val f = encryptToFile("trunctail.bin", plain)
        val raw = f.readBytes()
        val lastBlock = StreamingFileCipher.LEN_PREFIX_SIZE + partial + 16
        val drop = lastBlock + StreamingFileCipher.TRAILER_SIZE
        f.writeBytes(raw.copyOfRange(0, raw.size - drop))
        Assert.assertThrows(EOFException::class.java) {
            readAll(StreamingFileCipher.openDecrypted(f, key))
        }
    }

    @Test
    fun `every truncation of a v2 file fails loudly (fail-closed invariant)`() {
        // 固化「损坏输入绝不静默成功」：只要 magic 头完整，任何严格前缀都必须抛 EOFException。
        // 0~3 字节截断刻意不判——openDecrypted 对不足 4 字节按遗留明文透读（老 blob 必须能读）。
        val small = "屏幕捕获小文本 ABC123".toByteArray(Charsets.UTF_8)
        val sf = encryptToFile("trunc-all-small.bin", small)
        val smallRaw = sf.readBytes()
        val dir = tmp.newFolder("trunc-small")
        for (k in 4 until smallRaw.size) {
            val t = File(dir, "k$k.bin")
            t.writeBytes(smallRaw.copyOfRange(0, k))
            Assert.assertThrows(
                "截断到 $k/${smallRaw.size} 必须失败（fail-closed）",
                EOFException::class.java
            ) { readAll(StreamingFileCipher.openDecrypted(t, key)) }
        }

        val cs = StreamingFileCipher.CHUNK_SIZE
        val big = patternBytes(2L * cs + 123L)
        val bf = encryptToFile("trunc-all-big.bin", big)
        val bigRaw = bf.readBytes()
        val chunkBytes = cs + StreamingFileCipher.LEN_PREFIX_SIZE + 16
        val points = sortedSetOf<Int>()
        points.add(StreamingFileCipher.HEADER_FULL_SIZE)
        points.add(bigRaw.size - StreamingFileCipher.TRAILER_SIZE)
        var off = StreamingFileCipher.HEADER_FULL_SIZE
        while (off + chunkBytes <= bigRaw.size) {
            points.add(off)
            points.add(off + 1)
            off += chunkBytes
        }
        val dir2 = tmp.newFolder("trunc-big")
        for (k in points) {
            if (k !in 0 until bigRaw.size) continue
            val t = File(dir2, "k$k.bin")
            t.writeBytes(bigRaw.copyOfRange(0, k))
            Assert.assertThrows(
                "截断到 $k/${bigRaw.size} 必须失败（fail-closed）",
                EOFException::class.java
            ) { readAll(StreamingFileCipher.openDecrypted(t, key)) }
        }
    }

    @Test
    fun `legacy plaintext passes through untouched`() {
        val plain = "v1.0.33 及以前的明文 blob".toByteArray()
        val f = File(tmp.newFolder(), "legacy.bin")
        f.writeBytes(plain)
        Assert.assertArrayEquals(plain, readAll(StreamingFileCipher.openDecrypted(f, key)))
    }
}
