/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 */
package org.fcitx.fcitx5.android.recall.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.utils.appContext

/**
 * Recall 数据仓库：写入去重、过期清理、查询。
 *
 * MBM v1.0.31：**捕获文本无字数上限**。
 *  - 删除旧 `MAX_TEXT_LENGTH=4000` 静默截断（RecallService 不再 take）；
 *  - 超过内联阈值的文本外置 blob（见 [RecallBlobStore]），DB 行只存元数据；
 *  - 去重：小文本按 text，大文本按 sha256。
 */
object RecallRepository {

    private const val MIN_TEXT_LENGTH = 2

    private val writeMutex = Mutex()

    private fun db(context: Context) = RecallDatabase.get(context)

    suspend fun record(
        context: Context,
        packageName: String,
        appLabel: String,
        text: String,
        source: String
    ): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < MIN_TEXT_LENGTH) return false
        val dao = db(context).dao()
        return writeMutex.withLock {
            val bytes = trimmed.toByteArray()
            val record = if (bytes.size <= RecallBlobStore.INLINE_THRESHOLD) {
                RecallRecord(
                    packageName = packageName,
                    appLabel = appLabel,
                    text = trimmed,
                    source = source,
                    timestamp = System.currentTimeMillis(),
                    blobRef = null,
                    byteLen = bytes.size.toLong(),
                    sha256 = sha256(trimmed),
                    codec = 0
                )
            } else {
                // 大文本：外置 blob，text 列存 preview（列表 UI 零改动）
                val handle = RecallBlobStore.write(trimmed.byteInputStream())
                RecallRecord(
                    packageName = packageName,
                    appLabel = appLabel,
                    text = handle.preview,
                    source = source,
                    timestamp = System.currentTimeMillis(),
                    blobRef = handle.sha256,
                    byteLen = handle.byteLen,
                    sha256 = handle.sha256,
                    codec = 1
                )
            }
            // MBM: 同一应用的相同内容只记录一次（全历史去重，无时间窗）。
            // 之前是 30 秒窗口，用户真机反馈同一屏内容隔几分钟又被重复记录。
            val duplicated = if (record.isBlob) {
                dao.duplicatesBySha(packageName, record.sha256, 0L)
            } else {
                dao.duplicates(packageName, trimmed, 0L)
            }
            if (duplicated > 0) return@withLock false
            dao.insert(record)
            true
        }
    }

    fun all(context: Context) = db(context).dao().all()

    suspend fun page(
        context: Context,
        before: Long,
        idBefore: Long,
        n: Int
    ): List<RecallRecord> = withContext(Dispatchers.IO) {
        db(context).dao().page(before, idBefore, n)
    }

    fun search(context: Context, q: String, limit: Int = 2000) = db(context).dao().search(q, limit)

    suspend fun delete(context: Context, ids: List<Long>) = withContext(Dispatchers.IO) {
        val dao = db(context).dao()
        // 先 GC blob 文件再删行
        runCatching { dao.blobShasOf(ids).forEach { RecallBlobStore.delete(it) } }
        dao.delete(ids)
    }

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        val dao = db(context).dao()
        runCatching { dao.allBlobShas().forEach { RecallBlobStore.delete(it) } }
        dao.clear()
    }

    /**
     * MBM v1.0.31：过期清理**默认关闭**（keepDays <= 0 = 永久保留）。
     * 旧代码硬编码 trim(…, 30)，应用户「历史无上限」要求移除。
     * 清理写审计日志（调用方），禁止静默删除。
     */
    suspend fun trim(context: Context, keepDays: Int): Int = withContext(Dispatchers.IO) {
        if (keepDays <= 0) return@withContext 0
        val before = System.currentTimeMillis() - keepDays * 24 * 3600 * 1000L
        val dao = db(context).dao()
        val shas = runCatching { dao.blobShasOfAllBefore(before) }.getOrDefault(emptyList())
        val n = dao.deleteBefore(before)
        shas.forEach { RecallBlobStore.delete(it) }
        n
    }

    suspend fun count(): Int = withContext(Dispatchers.IO) {
        db(appContext).dao().count()
    }

    /** blob 存储占用字节（UI 统计）。 */
    fun blobBytes(): Long = RecallBlobStore.totalBytes()

    /** 启动时清理：崩溃残留 .tmp + 孤儿 blob。 */
    suspend fun gcBlobs(context: Context): Int = withContext(Dispatchers.IO) {
        RecallBlobStore.cleanupTmpFiles()
        val known = runCatching { db(context).dao().allBlobShas() }.getOrDefault(emptyList()).toSet()
        RecallBlobStore.deleteOrphans(known)
    }

    private fun sha256(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
