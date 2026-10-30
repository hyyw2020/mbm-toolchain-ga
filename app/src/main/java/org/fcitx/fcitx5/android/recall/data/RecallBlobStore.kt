/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * MBM v1.0.31：Recall 历史大文本外置存储（blob store）。
 *
 * 与主输入法 ClipboardBlobStore 同构（两个 APK 独立分发，不能共用代码）：
 *  - `RecallRecord.text` 单列 String 受 Android CursorWindow 2MB 硬限制；
 *  - 超过内联阈值的捕获文本写入 filesDir/recall_blobs/<sha256>.bin，
 *    DB 行只存元数据（sha256/byteLen/preview），text 列存 preview；
 *  - 流式写入（边写边算 SHA-256，.tmp 原子 rename），无容量上限。
 *
 * MBM v1.0.34（R3 第一期）：blob 文件**静态加密**（与主输入法同款）。
 *  - 写入走 [StreamingFileCipher.EncryptingSink]（AES-256-GCM 分块封装）；
 *  - 读取自动识别：密文走 [StreamingFileCipher.openDecrypted]，
 *    v1.0.33 及以前的**遗留明文**原样可读（升级不丢数据）；
 *    dedup 命中遗留明文时顺手改写为密文（惰性迁移）；
 *  - sha256 仍按**明文**计算（内容寻址语义不变），文件长度 = 密文长度
 *    （比明文多 16B 头 + 16B/256KB 块，约 0.006%）；
 *  - [key] 为 null 时退化为不加密（单测 / 密钥不可用时）。
 *
 * 可测性：核心是「显式传 dir」的 internal 纯 JVM 函数，单测传临时目录即可全覆盖。
 */
package org.fcitx.fcitx5.android.recall.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

object RecallBlobStore {

    /** 内联阈值：≤ 此值走 text 列；超过外置 blob 文件。 */
    const val INLINE_THRESHOLD = 256 * 1024

    /** 大文本条目的 text 列存前 N 个字符（列表摘要）。 */
    const val PREVIEW_CHARS = 256

    private const val PREVIEW_SAMPLE_BYTES = 1024

    private const val DIR_NAME = "recall_blobs"

    private val dir: File
        get() = File(appContext.filesDir, DIR_NAME)

    class Handle(val sha256: String, val byteLen: Long, val preview: String)

    /** 流式写入；已存在同内容文件时跳过（内容寻址去重）。 */
    suspend fun write(input: InputStream): Handle = withContext(Dispatchers.IO) {
        // N52：写侧传密钥（与 N28 同源修复），Recall 屏幕文本不再明文落盘
        writeTo(dir, input, RecallKeyManager.rawKey(appContext))
    }

    /**
     * 写入核心（纯 JVM，可单测）：[dir] 由调用方提供，[key] 为 null 时不加密。
     * 单循环流式读入：digest 按**明文**累计（内容寻址语义不变），
     * 落盘内容经 [key] 加密（密文路径）或原样（遗留/测试路径）；
     * .tmp 原子 rename + 内容寻址去重，无容量上限。
     */
    internal fun writeTo(dir: File, input: InputStream, key: ByteArray?): Handle {
        dir.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val previewBuf = ByteArray(PREVIEW_SAMPLE_BYTES)
        var previewLen = 0
        val tmp = File.createTempFile("recallblob", ".tmp", dir)
        try {
            FileOutputStream(tmp).use { fos ->
                val out: OutputStream = if (key != null) {
                    StreamingFileCipher.EncryptingSink(fos, key)
                } else {
                    fos
                }
                out.use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        o.write(buf, 0, n)
                        total += n
                        if (previewLen < previewBuf.size) {
                            val take = minOf(n, previewBuf.size - previewLen)
                            buf.copyInto(previewBuf, previewLen, 0, take)
                            previewLen += take
                        }
                    }
                    o.flush()
                }
            }
            val sha = digest.digest().toHex()
            val target = File(dir, "$sha.bin")
            val expectedEncLen = StreamingFileCipher.encodedLength(total)
            when {
                // 同内容密文已存在：内容寻址，丢弃本次临时文件
                target.exists() && target.length() == expectedEncLen -> tmp.delete()
                // 遗留明文同内容文件（或损坏的残 file）：惰性迁移/覆写为密文
                else -> {
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) throw IOException("rename blob tmp -> $sha failed")
                }
            }
            val preview = String(previewBuf, 0, previewLen, Charsets.UTF_8).take(PREVIEW_CHARS)
            return Handle(sha, total, preview)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** 流式读取全文；文件不存在返回 null。密文自动解密，遗留明文原样返回。 */
    fun open(sha256: String): InputStream? = runCatching {
        openIn(dir, sha256, RecallKeyManager.rawKey(appContext))
    }.getOrElse {
        Timber.w(it, "open recall blob $sha256 failed (key store unavailable?)")
        null
    }

    /** 读取核心（纯 JVM，可单测）。[key] 为 null 时按遗留明文处理。 */
    internal fun openIn(dir: File, sha256: String, key: ByteArray? = null): InputStream? {
        val f = File(dir, "$sha256.bin")
        if (!f.exists()) return null
        return runCatching {
            if (key != null) StreamingFileCipher.openDecrypted(f, key) else f.inputStream()
        }.getOrElse {
            Timber.w(it, "open recall blob $sha256 failed")
            null
        }
    }

    fun file(sha256: String): File = File(dir, "$sha256.bin")

    fun delete(sha256: String): Boolean = deleteIn(dir, sha256)

    internal fun deleteIn(dir: File, sha256: String): Boolean = File(dir, "$sha256.bin").delete()

    fun totalBytes(): Long = totalBytesIn(dir)

    internal fun totalBytesIn(dir: File): Long =
        dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sumOf { it.length() } ?: 0L

    fun cleanupTmpFiles(): Int = cleanupTmpFilesIn(dir)

    internal fun cleanupTmpFilesIn(dir: File): Int {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") } ?: return 0
        var n = 0
        files.forEach { if (it.delete()) n++ }
        return n
    }

    fun deleteOrphans(knownShas: Set<String>): Int = deleteOrphansIn(dir, knownShas)

    internal fun deleteOrphansIn(dir: File, knownShas: Set<String>): Int {
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") } ?: return 0
        var n = 0
        files.forEach { f ->
            if (f.name.removeSuffix(".bin") !in knownShas) {
                if (f.delete()) n++
            }
        }
        return n
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
