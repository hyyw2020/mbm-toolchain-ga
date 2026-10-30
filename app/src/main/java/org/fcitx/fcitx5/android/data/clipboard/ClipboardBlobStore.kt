/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 *
 * MBM v1.0.31：剪贴板大文本外置存储（blob store）。
 *
 * 背景：`ClipboardEntry.text` 是 Room 单列 String，受 Android **CursorWindow 2MB**
 * 硬限制——单条文本超过约 1MB 时，任何查询返回该行都会
 * "Row too big to fit into CursorWindow" 崩溃。100MB 级文本不可能内联。
 *
 * 设计（详见 docs/design/unlimited-clipboard-design.md §3.1）：
 *  - ≥ [INLINE_THRESHOLD] 的文本写入 filesDir/clipblobs/<sha256>.bin；
 *  - 流式写入：边读边算 SHA-256，先写 .tmp 再原子 rename（崩溃不留半成品）；
 *  - DB 行只存元数据（sha256 / byteLen / preview），text 列存 preview ——
 *    列表 UI（excerptText）零改动即可显示摘要；
 *  - 内容寻址：相同内容天然去重（同 sha = 同文件）；
 *  - 无任何容量上限：文件数量/大小只受设备存储限制。
 *
 * MBM v1.0.34（R3 第一期）：blob 文件**静态加密**。
 *  - 写入走 StreamingFileCipher.EncryptingSink（AES-256-GCM 分块封装）；
 *  - 读取自动识别：密文走 DecryptingInputStream，v1.0.33 及以前的**遗留明文**
 *    原样可读（升级不丢数据）；dedup 命中遗留明文时顺手改写为密文（惰性迁移）；
 *  - sha256 仍按**明文**计算（内容寻址语义不变），文件长度 = 密文长度
 *    （比明文多 16B 头 + 16B/256KB 块，约 0.006%）；
 *  - [key] 为 null 时退化为不加密（单测 / 尚未初始化密钥时）。
 *
 * 可测性（MBM v1.0.31）：写入/读取/GC 核心都是「显式传 dir」的 internal 纯 JVM 函数，
 * 公开方法只是 appContext.filesDir 的一层委托——单测传临时目录即可全覆盖。
 */
package org.fcitx.fcitx5.android.data.clipboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.crypto.AppKeyManager
import org.fcitx.fcitx5.android.data.crypto.StreamingFileCipher
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

object ClipboardBlobStore {

    /** 内联阈值：UTF-8 字节数 ≤ 此值走 text 列（快路径）；超过则外置 blob 文件。 */
    const val INLINE_THRESHOLD = 256 * 1024

    /** 预览字符数：大文本条目的 text 列存前 N 个字符（列表/搜索摘要用）。 */
    const val PREVIEW_CHARS = 256

    /** 预览取样字节数：按 UTF-8 取前 1KB 再截到 PREVIEW_CHARS 个字符。 */
    private const val PREVIEW_SAMPLE_BYTES = 1024

    private const val DIR_NAME = "clipblobs"

    /** 粘贴分块大小：Binder 事务缓冲约 1MB，256KB/块留足安全余量。 */
    const val PASTE_CHUNK_BYTES = 256 * 1024

    private val dir: File
        get() = File(appContext.filesDir, DIR_NAME)

    private fun blobFile(sha256: String) = File(dir, "$sha256.bin")

    /** blob 文件句柄（校验/直接读场景用；一般读取走 [open]）。
     *  注意 v1.0.34 起文件内容可能是密文，需要明文请走 [open]。 */
    fun file(sha256: String): File = blobFile(sha256)

    class Handle(val sha256: String, val byteLen: Long, val preview: String)

    /**
     * 流式写入。调用方负责关闭 [input]。
     * @return 成功时返回内容句柄；已存在同内容文件时跳过写入（内容寻址去重）。
     */
    suspend fun write(input: InputStream): Handle = withContext(Dispatchers.IO) {
        // N28：写侧必须传密钥，去掉「默认不加密」的明文漏洞（PRIVACY.txt 承诺 AES-256-GCM 静态加密）
        writeTo(dir, input, AppKeyManager.rawKey(appContext))
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
        val tmp = File.createTempFile("clbblob", ".tmp", dir)
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
                // 遗留明文同内容文件（或损坏的残file）：惰性迁移/覆写为密文
                else -> {
                    if (target.exists()) target.delete()
                    if (!tmp.renameTo(target)) {
                        throw IOException("rename blob tmp -> $sha failed")
                    }
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
        openIn(dir, sha256, AppKeyManager.rawKey(appContext))
    }.getOrElse {
        Timber.w(it, "open blob $sha256 failed (key store unavailable?)")
        null
    }

    /** 读取核心（纯 JVM，可单测）。[key] 为 null 时按遗留明文处理。 */
    internal fun openIn(dir: File, sha256: String, key: ByteArray? = null): InputStream? {
        val f = File(dir, "$sha256.bin")
        if (!f.exists()) return null
        return runCatching {
            if (key != null) StreamingFileCipher.openDecrypted(f, key) else f.inputStream()
        }.getOrElse {
            Timber.w(it, "open blob $sha256 failed")
            null
        }
    }

    fun exists(sha256: String): Boolean = existsIn(dir, sha256)

    internal fun existsIn(dir: File, sha256: String): Boolean = File(dir, "$sha256.bin").exists()

    /** blob 条目数（UI 存储统计）。 */
    fun byteLen(sha256: String): Long = byteLenIn(dir, sha256)

    /**
     * 落盘字节数。注意 v1.0.34 起密文比明文多约 0.006%（16B 头 + 16B/256KB 块）；
     * UI 展示的「文本大小」以 DB 行的 byteLen（明文）为准，不用这个。
     */
    internal fun byteLenIn(dir: File, sha256: String): Long =
        File(dir, "$sha256.bin").let { if (it.exists()) it.length() else 0L }

    fun delete(sha256: String): Boolean = deleteIn(dir, sha256)

    internal fun deleteIn(dir: File, sha256: String): Boolean = File(dir, "$sha256.bin").delete()

    /** 全部 blob 占用字节数（UI 存储统计；密文长度，含约 0.006% 封装开销）。 */
    fun totalBytes(): Long = totalBytesIn(dir)

    internal fun totalBytesIn(dir: File): Long =
        dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }
            ?.sumOf { it.length() } ?: 0L

    /** blob 条目数（UI 存储统计）。 */
    fun blobCount(): Int = blobCountIn(dir)

    internal fun blobCountIn(dir: File): Int =
        dir.listFiles()?.count { it.isFile && !it.name.endsWith(".tmp") } ?: 0

    /**
     * 清理写一半的 .tmp（进程崩溃残留）。启动时调用一次。
     * 注意：createTempFile 产生的临时文件名与 [tmpFile] 模式不同，这里按后缀兜底清理。
     */
    fun cleanupTmpFiles(): Int = cleanupTmpFilesIn(dir)

    internal fun cleanupTmpFilesIn(dir: File): Int {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") } ?: return 0
        var n = 0
        files.forEach { if (it.delete()) n++ }
        if (n > 0) Timber.i("ClipboardBlobStore: cleaned %d stale .tmp", n)
        return n
    }

    /**
     * 回收孤儿 blob：DB 中已无引用（不在 [knownShas]）的文件。
     * 删除记审计（调用方负责 RecallAuditLog/Toast），返回删除数量。
     */
    fun deleteOrphans(knownShas: Set<String>): Int = deleteOrphansIn(dir, knownShas)

    internal fun deleteOrphansIn(dir: File, knownShas: Set<String>): Int {
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") } ?: return 0
        var n = 0
        files.forEach { f ->
            val sha = f.name.removeSuffix(".bin")
            if (sha !in knownShas) {
                if (f.delete()) n++
            }
        }
        return n
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
