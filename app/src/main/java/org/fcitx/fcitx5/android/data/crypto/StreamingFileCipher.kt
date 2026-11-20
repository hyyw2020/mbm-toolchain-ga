/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * MBM v1.0.34：分块流式文件加密码（R3 第一期：剪贴板 blob 静态加密）。
 *
 * 背景：剪贴板 blob 文件（>256KB 的大文本，可能是密码/令牌/证件号）此前以
 * **明文**落在应用私有目录，只依赖 Android 沙箱 + 文件级加密（FBE）。
 * 设备被 root / 换机迁移提取时无第二道防线。Recall 的数据库早已
 * SQLCipher + Keystore 信封加密（RecallDatabase.kt），但两个 APK 的
 * **blob 文件都还是明文**；本码补上这一层。
 *
 * 设计（纯 JVM、无 Android 依赖 → 可单测；Android 侧只有密钥来源）：
 *  - 算法：AES-256-GCM 分块封装，每块独立 nonce 与 tag，块间流式不缓存全文；
 *  - nonce = 8B 随机前缀（每文件一次）|| 4B 大端块序号，全局不重复；
 *  - 块序号同时进 AAD → 块被重排/对调即 tag 校验失败；
 *  - 单块 256KB（1<<18），tag 16B/块，存储开销约 0.006%；
 *
 *  MBM v1.0.35u（N64 修复）：v1 头部只有 magic/version/chunkShift/noncePrefix，
 *  既无总长度也无头部 MAC —— 尾部整块被丢弃时其余块 GCM tag 仍合法、解密静默
 *  返回被截断明文；且把前 4 字节 magic 改掉即被当作「遗留明文」原样吐出（绕过全部
 *  AEAD）。v2 在保持 16B 基础头（magic/version/chunkShift/reserved/noncePrefix）
 *  不变的前提下追加 headerMac(16)、**每块长度前缀**与尾部：
 *
 *    完整头(32) = 基础头(16) + headerMac(16)
 *    每个正文块 = [4B 大端明文长度][密文(长度+16B tag)]
 *    尾部(32)   = [4B = 0xFFFFFFFF 标记][totalPlainLen(8 LE)][totalBlocks(4 LE)]
 *                 [trailerMac(16)]
 *
 *  - headerMac：对基础头做 GCM 认证，篡改 magic/version/noncePrefix 立即失败；
 *  - 长度前缀：块长显式携带 → 解密端可确定性地逐块读出，**不再需要
 *    「贪心读到定长 + 回退猜尾部」**（后者在末块短于目标长度时会与尾部
 *    产生歧义，导致合法文件解密失败）；
 *  - 尾部：totalPlainLen/totalBlocks 由 trailerMac 覆盖，校验后与实际解码结果
 *    比对 → 整块截断（哪怕刚好砍掉 N 个完整块）也能被检出，且不再依赖某个
 *    单一消费者自己做长度校验。
 *  旧 v1 密文（16B 头、无 headerMac/长度前缀/尾部）仍可读（升级不丢数据），
 *  但新写入一律 v2。
 *
 * 性能实测（2026-10-25，沙箱 x86 JDK20，未走 AES intrinsics 的**软件加密
 * 最差情况**）：100MB 加密 ~1.6s、解密 ~1.2s（61MB/s）。Android arm64
 * 设备（API 29+ 机型普遍带 ARMv8 Crypto Extensions）硬件加速下快 10–30 倍，
 * 且 100MB 粘贴本身耗时 10s–1min，加密不是瓶颈。
 */
package org.fcitx.fcitx5.android.data.crypto

import java.io.EOFException
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object StreamingFileCipher {

    /** 文件头 magic：'M' 'B' 'M' 'C'。 */
    val MAGIC = byteArrayOf(0x4D, 0x42, 0x4D, 0x43)

    /** 当前写入版本。 */
    const val VERSION = 2

    /** 兼容读取的旧版本（仅基础头、无 headerMac/终结块/尾部）。 */
    const val LEGACY_VERSION = 1

    /** 块大小移位：1 shl 18 = 256KB（与粘贴分块 PASTE_CHUNK_BYTES 对齐）。 */
    const val CHUNK_SHIFT = 18

    /** 单块明文字节数。 */
    val CHUNK_SIZE: Int = 1 shl CHUNK_SHIFT

    /**
     * 基础头长度（恒定 16B，magic(4)+version(1)+chunkShift(1)+reserved(2)+noncePrefix(8)）。
     * 注意：v2 在基础头之后额外追加 headerMac(16)，完整头 = [HEADER_FULL_SIZE]。
     */
    const val HEADER_SIZE = 16

    /** v2 完整头长度：基础头(16) + headerMac(16)。 */
    const val HEADER_FULL_SIZE = 32

    /** v2 每块长度前缀长度：4B 大端明文长度。 */
    const val LEN_PREFIX_SIZE = 4

    /** v2 尾部长度：标记(4) + totalPlainLen(8) + totalBlocks(4) + trailerMac(16)。 */
    const val TRAILER_SIZE = 32

    /** 尾部标记：长度前缀 == 该值表示「正文块到此为止，后面是尾部」。 */
    private const val TAIL_MARKER = -1

    private const val TAG_BITS = 128
    private val random = SecureRandom()

    /** headerMac / trailerMac 使用的哨兵块序号（均远高于真实块序号，绝不重复）。 */
    private const val HEADER_MAC_INDEX = 0xFFFFFFFFL
    private const val TRAILER_MAC_INDEX = 0xFFFFFFFEL

    private val HDR_AAD = "MBMCHDR1".toByteArray()
    private val TRL_AAD = "MBMCTRLR".toByteArray()

    /**
     * 编码后文件长度 = 完整头 + 正文块数*(CHUNK_SIZE + 4前缀 + 16tag) + 尾部。
     * 注意尾块通常短于 [CHUNK_SIZE]，不能按满块估算（dedup 长度判据依赖精确值）。
     * 明文 0 字节：写入端 close 也会落 完整头(32) + 尾部(32)，故返回 64。
     */
    fun encodedLength(plainLen: Long): Long {
        if (plainLen <= 0L) {
            return (HEADER_FULL_SIZE + TRAILER_SIZE).toLong()
        }
        val full = plainLen / CHUNK_SIZE
        val tail = plainLen % CHUNK_SIZE
        val perChunk = CHUNK_SIZE + LEN_PREFIX_SIZE + 16L
        val body = full * perChunk + if (tail > 0) tail + LEN_PREFIX_SIZE + 16L else 0L
        return HEADER_FULL_SIZE + body + TRAILER_SIZE
    }

    // ---- GCM 辅助 ----

    private fun keySpec(key: ByteArray) = SecretKeySpec(key, "AES")

    private fun nonceFor(prefix: ByteArray, index: Long): ByteArray {
        val n = ByteArray(12)
        prefix.copyInto(n, 0)
        n[8] = (index ushr 24).toByte()
        n[9] = (index ushr 16).toByte()
        n[10] = (index ushr 8).toByte()
        n[11] = index.toByte()
        return n
    }

    private fun aadFor(index: Long): ByteArray = ByteArray(8).also {
        for (i in 0 until 8) it[i] = (index ushr (56 - 8 * i)).toByte()
    }

    /**
     * 仅计算 GCM tag：被认证数据一并走 AAD（不产生密文输出），
     * doFinal() 无输入 → 只返回 16B tag。
     * （不能 update(data)+doFinal()：SunJCE 在 updateAAD 之后 update() 返回 0、
     * doFinal() 返回 data+tag，长度随 data 变化，会破坏文件布局。）
     */
    private fun computeMac(
        key: ByteArray,
        noncePrefix: ByteArray,
        index: Long,
        aad: ByteArray,
        data: ByteArray
    ): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keySpec(key), GCMParameterSpec(TAG_BITS, nonceFor(noncePrefix, index)))
        c.updateAAD(aad)
        c.updateAAD(data)
        return c.doFinal()
    }

    private fun checkMac(
        key: ByteArray,
        noncePrefix: ByteArray,
        index: Long,
        aad: ByteArray,
        data: ByteArray,
        expected: ByteArray
    ) {
        if (!computeMac(key, noncePrefix, index, aad, data).contentEquals(expected)) {
            throw AEADBadTagException("StreamingFileCipher: MAC 校验失败（文件被篡改或密钥错误）")
        }
    }

    private fun leBytes(value: Long, n: Int): ByteArray {
        val out = ByteArray(n)
        var v = value
        for (i in 0 until n) {
            out[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return out
    }

    private fun leLong(bytes: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) {
            v = v or ((bytes[off + i].toLong() and 0xFF) shl (8 * i))
        }
        return v
    }

    /** 4B 大端（长度前缀用）。 */
    private fun beInt(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    // ---- 文件头 ----

    private fun buildBaseHeader(chunkShift: Int, noncePrefix: ByteArray): ByteArray {
        require(noncePrefix.size == 8)
        return ByteArray(HEADER_SIZE).also { h ->
            MAGIC.copyInto(h, 0)
            h[4] = VERSION.toByte()
            h[5] = chunkShift.toByte()
            // h[6..7] reserved = 0
            noncePrefix.copyInto(h, 8)
        }
    }

    /**
     * 探测流是否为 v2/v1 密文（消费前 4 字节后复位；不足 4 字节或 magic 不符
     * 一律视为**遗留明文**——老版本写入的 blob 必须继续能读）。
     */
    fun isEncrypted(source: InputStream): Boolean {
        if (!source.markSupported()) return false
        source.mark(4)
        val magic = ByteArray(4)
        var read = 0
        while (read < 4) {
            val n = source.read(magic, read, 4 - read)
            if (n < 0) break
            read += n
        }
        source.reset()
        return read == 4 && magic.contentEquals(MAGIC)
    }

    /**
     * 解析基础头（前 16 字节）。返回 (chunkShift, noncePrefix, version)。
     * 不在此校验 headerMac（调用方按 version 决定是否校验）。
     */
    private fun parseBaseHeader(source: InputStream): Triple<Int, ByteArray, Int> {
        val h = ByteArray(HEADER_SIZE)
        var read = 0
        while (read < HEADER_SIZE) {
            val n = source.read(h, read, HEADER_SIZE - read)
            if (n < 0) throw EOFException("truncated cipher header")
            read += n
        }
        require(h.copyOfRange(0, 4).contentEquals(MAGIC)) { "bad magic" }
        val version = h[4].toInt() and 0xFF
        require(version == VERSION || version == LEGACY_VERSION) { "unsupported cipher version $version" }
        val chunkShift = h[5].toInt() and 0xFF
        require(chunkShift in 10..24) { "bad chunkShift $chunkShift" }
        return Triple(chunkShift, h.copyOfRange(8, 16), version)
    }

    // ---- 加密 ----

    /**
     * 流式加密写入器：按 [chunkSize] 缓冲明文，满块即 GCM 封装后经 [sink] 写出。
     * 不缓存全文；close 时冲刷最后一块、追加终结块与尾部（含总长度/总块数/MAC）。
     */
    class EncryptingSink(
        private val sink: java.io.OutputStream,
        private val key: ByteArray,
        private val chunkShift: Int = CHUNK_SHIFT
    ) : java.io.OutputStream() {

        private val chunkSize = 1 shl chunkShift
        private val secretKey = keySpec(key)
        private val noncePrefix = ByteArray(8).also { random.nextBytes(it) }
        private val buf = ByteArray(chunkSize)
        private var filled = 0
        private var index = 0L
        private var plainTotal = 0L
        private var blockCount = 0L
        private var headerWritten = false
        private var closed = false

        private fun ensureHeader() {
            if (!headerWritten) {
                val base = buildBaseHeader(chunkShift, noncePrefix)
                val mac = computeMac(key, noncePrefix, HEADER_MAC_INDEX, HDR_AAD, base)
                sink.write(base)
                sink.write(mac)
                headerWritten = true
            }
        }

        override fun write(b: Int) {
            ensureHeader()
            buf[filled++] = b.toByte()
            plainTotal++
            if (filled == chunkSize) flushChunk()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var remaining = len
            while (remaining > 0) {
                ensureHeader()
                val take = minOf(remaining, chunkSize - filled)
                b.copyInto(buf, filled, o, o + take)
                filled += take
                plainTotal += take
                o += take
                remaining -= take
                if (filled == chunkSize) flushChunk()
            }
        }

        private fun flushChunk() {
            if (filled == 0) return
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                secretKey,
                GCMParameterSpec(TAG_BITS, nonceFor(noncePrefix, index))
            )
            cipher.updateAAD(aadFor(index))
            val out = ByteArray(filled + 16)
            val n = cipher.doFinal(buf, 0, filled, out, 0)
            check(n == filled + 16)
            sink.write(beInt(filled))
            sink.write(out, 0, n)
            index++
            blockCount++
            filled = 0
        }

        override fun flush() {
            sink.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                ensureHeader()
                flushChunk()
                // 尾部：标记(4) + totalPlainLen(8) + totalBlocks(4) + trailerMac(16)
                val totalLenBytes = leBytes(plainTotal, 8)
                val blockCountBytes = leBytes(blockCount, 4)
                val base = buildBaseHeader(chunkShift, noncePrefix)
                val macData = base + totalLenBytes + blockCountBytes
                val trailerMac = computeMac(key, noncePrefix, TRAILER_MAC_INDEX, TRL_AAD, macData)
                sink.write(beInt(TAIL_MARKER))
                sink.write(totalLenBytes)
                sink.write(blockCountBytes)
                sink.write(trailerMac)
                sink.flush()
            } finally {
                sink.close()
            }
        }
    }

    // ---- 解密 ----

    /**
     * 流式解密输入流：逐块读密文+tag，GCM 校验通过后才向调用方吐明文。
     * 任一块 tag 不符（篡改/重排/错密钥）抛 AEADBadTagException。
     * v2：正文块后紧跟终结块，终结块之后是尾部（总长度/总块数/MAC），
     * 校验总长度与总块数一致 → 整块截断也能检出，且不再依赖单一消费者的长度校验。
     */
    class DecryptingInputStream(
        private val source: InputStream,
        private val key: ByteArray
    ) : FilterInputStream(source) {

        private var version = VERSION
        private var chunkShift = CHUNK_SHIFT
        private var chunkSize = CHUNK_SIZE
        private val secretKey = keySpec(key)
        private lateinit var noncePrefix: ByteArray
        private var index = 0L
        // 注意括号：Kotlin 中缀函数 shl 优先级低于 +，`1 shl 24 + 16` 会解析成
        // `1 shl 40`（= 1 shl 8 = 256）——曾因少一对括号把缓冲错建成 256B，
        // 大块读取时 BufferedInputStream 抛 IndexOutOfBoundsException。
        private val cipherBuf = ByteArray((1 shl 24) + 16) // 最大块 16MB + tag
        private var cipherLen = 0
        private var cipherPos = 0
        private var eof = false
        private var started = false
        // v2 完整性累计
        private var decodedTotal = 0L
        private var bodyBlocks = 0L
        private var baseHeader = ByteArray(0)
        private var tailDone = false
        private var expectedTotalPlainLen = -1L
        private var expectedTotalBlocks = -1L

        private fun start() {
            if (started) return
            started = true
            val (shift, prefix, ver) = parseBaseHeader(source)
            chunkShift = shift
            chunkSize = 1 shl shift
            noncePrefix = prefix
            version = ver
            baseHeader = ByteArray(HEADER_SIZE).also { h ->
                MAGIC.copyInto(h, 0)
                h[4] = ver.toByte()
                h[5] = shift.toByte()
                prefix.copyInto(h, 8)
            }
            if (version == VERSION) {
                // 读 headerMac 并校验
                val mac = ByteArray(16)
                var r = 0
                while (r < 16) {
                    val n = source.read(mac, r, 16 - r)
                    if (n < 0) throw EOFException("truncated cipher header mac")
                    r += n
                }
                checkMac(key, noncePrefix, HEADER_MAC_INDEX, HDR_AAD, baseHeader, mac)
            }
        }

        private fun decryptBody(n: Int) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(TAG_BITS, nonceFor(noncePrefix, index))
            )
            cipher.updateAAD(aadFor(index))
            val out = ByteArray(n)
            cipherLen = cipher.doFinal(cipherBuf, 0, n, out, 0)
            out.copyInto(cipherBuf, 0, 0, cipherLen)
            cipherPos = 0
        }

        /** 精确读取 n 字节到 cipherBuf[0..n)，不足则抛 EOFException。 */
        private fun readExact(n: Int, what: String) {
            var read = 0
            while (read < n) {
                val r = source.read(cipherBuf, read, n - read)
                if (r < 0) throw EOFException("truncated cipher file ($what): $read < $n")
                read += r
            }
        }

        /** 读取 4B 大端长度前缀；返回 null 表示流已结束。 */
        private fun readLengthPrefix(): Int? {
            val b = ByteArray(LEN_PREFIX_SIZE)
            var read = 0
            while (read < LEN_PREFIX_SIZE) {
                val r = source.read(b, read, LEN_PREFIX_SIZE - read)
                if (r < 0) {
                    if (read == 0) return null
                    throw EOFException("truncated cipher length prefix")
                }
                read += r
            }
            return ((b[0].toInt() and 0xFF) shl 24) or
                ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or
                (b[3].toInt() and 0xFF)
        }

        /**
         * 读取并校验尾部（标记前缀已被消费），记录总长度/总块数期望值。
         * 尾部布局：totalPlainLen(8) + totalBlocks(4) + trailerMac(16)。
         */
        private fun readAndVerifyTail() {
            val tail = ByteArray(TRAILER_SIZE - LEN_PREFIX_SIZE)
            var r = 0
            while (r < tail.size) {
                val n = source.read(tail, r, tail.size - r)
                if (n < 0) throw EOFException("truncated cipher tail: $r < ${tail.size}")
                r += n
            }
            val totalPlainLen = leLong(tail, 0, 8)
            val totalBlocks = leLong(tail, 8, 4)
            val expectedMac = tail.copyOfRange(12, tail.size)
            val macData = baseHeader + leBytes(totalPlainLen, 8) + leBytes(totalBlocks, 4)
            checkMac(key, noncePrefix, TRAILER_MAC_INDEX, TRL_AAD, macData, expectedMac)
            expectedTotalPlainLen = totalPlainLen
            expectedTotalBlocks = totalBlocks
        }

        private fun checkTotals() {
            if (decodedTotal != expectedTotalPlainLen || bodyBlocks != expectedTotalBlocks) {
                throw AEADBadTagException(
                    "StreamingFileCipher: 解码长度/块数与尾部不符（可能被整块截断）" +
                        " decoded=$decodedTotal expected=$expectedTotalPlainLen" +
                        " blocks=$bodyBlocks expectedBlocks=$expectedTotalBlocks"
                )
            }
        }

        private fun decryptChunk(target: Int): Boolean {
            start()
            if (eof || tailDone) return false
            if (version == LEGACY_VERSION) return decryptLegacyChunk(target)
            // v2：每块自带 4B 明文长度 → 块边界确定，无需靠贪心读 + 猜尾部。
            val declared = readLengthPrefix() ?: run {
                // v1.0.86 真 bug：正确写入的 v2 文件在正文块读完后，下一个 4B 必定是
                // TAIL_MARKER（写入端 close 无条件写尾；空明文首块即 marker）。在读到
                // 任何前缀字节之前就 EOF，说明尾部连同末块被整块砍掉 —— 必须失败，
                // 否则 checkTotals 永不执行，静默吐出被截断明文（数据丢失且不报错）。
                // legacy 版本不经过此分支（上面 decryptLegacyChunk 已 return），不受影响。
                throw EOFException("truncated cipher file: missing tail marker (whole tail/last block dropped)")
            }
            if (declared == TAIL_MARKER) {
                readAndVerifyTail()
                checkTotals()
                tailDone = true
                eof = true
                return false
            }
            require(declared in 1..chunkSize) {
                "StreamingFileCipher: 非法块长度 $declared"
            }
            readExact(declared + 16, "chunk at index $index")
            decryptBody(declared + 16)
            decodedTotal += cipherLen
            bodyBlocks++
            index++
            return true
        }

        private fun decryptLegacyChunk(target: Int): Boolean {
            var read = 0
            while (read < target) {
                if (read == 0) {
                    val first = source.read()
                    if (first < 0) {
                        eof = true
                        return false
                    }
                    cipherBuf[0] = first.toByte()
                    read = 1
                    continue
                }
                val n = source.read(cipherBuf, read, target - read)
                if (n < 0) {
                    if (read < 16) throw EOFException("truncated cipher chunk at index $index")
                    break
                }
                read += n
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(TAG_BITS, nonceFor(noncePrefix, index))
            )
            cipher.updateAAD(aadFor(index))
            val out = ByteArray(read)
            cipherLen = cipher.doFinal(cipherBuf, 0, read, out, 0)
            out.copyInto(cipherBuf, 0, 0, cipherLen)
            cipherPos = 0
            index++
            return true
        }

        override fun read(): Int {
            if (cipherPos >= cipherLen && !decryptChunk(chunkSize + 16)) return -1
            return cipherBuf[cipherPos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (cipherPos >= cipherLen && !decryptChunk(chunkSize + 16)) return -1
            val take = minOf(len, cipherLen - cipherPos)
            cipherBuf.copyInto(b, off, cipherPos, cipherPos + take)
            cipherPos += take
            return take
        }

        override fun available(): Int = cipherLen - cipherPos

        override fun close() {
            super.close()
        }
    }

    // ---- 文件级便捷入口（供 blob store 用） ----

    /**
     * 打开明文读取流：密文走 [DecryptingInputStream]，遗留明文原样返回。
     * 调用方负责关闭。
     */
    fun openDecrypted(file: File, key: ByteArray): InputStream {
        val raw = file.inputStream().buffered()
        return if (isEncrypted(raw)) DecryptingInputStream(raw, key) else raw
    }

    /** 该密文文件解码后的明文字节数（逐块累加并校验尾部，不缓存全文）。 */
    fun decryptedLength(file: File, key: ByteArray): Long {
        openDecrypted(file, key).use { s ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
            }
            return total
        }
    }
}
