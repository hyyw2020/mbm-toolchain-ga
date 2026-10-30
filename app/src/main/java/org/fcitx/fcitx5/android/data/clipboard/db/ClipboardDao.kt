/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ClipboardDao {
    @Insert
    suspend fun insert(clipboardEntry: ClipboardEntry): Long

    /**
     * MBM v1.0.34：明文库 → SQLCipher 密文库的一次性回灌用。
     * 显式 id（迁移行 id != 0）插入，保留原主键；走 Room 事务机制，
     * 失效追踪正常触发（UI 的 PagingSource 会收到 invalidated）。
     */
    @Insert
    suspend fun insertAll(entries: List<ClipboardEntry>): List<Long>

    /**
     * MBM v1.0.34：迁移分批读取（id > :lastId 升序，内存有界，
     * 不会把整表——含 ≤256KB 的内联文本——一次性拉进内存）。
     */
    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE id > :lastId ORDER BY id ASC LIMIT :limit")
    suspend fun dumpRange(lastId: Int, limit: Int): List<ClipboardEntry>

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET pinned=:pinned WHERE id=:id")
    suspend fun updatePinStatus(id: Int, pinned: Boolean)

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET text=:text WHERE id=:id")
    suspend fun updateText(id: Int, text: String)

    /**
     * MBM v1.0.31：编辑条目时同步元数据（大小分流可能在内联 ↔ blob 间切换）。
     */
    @Query(
        """UPDATE ${ClipboardEntry.TABLE_NAME} 
           SET text=:text, byteLen=:byteLen, sha256=:sha256, blobRef=:blobRef, codec=:codec 
           WHERE id=:id"""
    )
    suspend fun updateTextAndMeta(
        id: Int,
        text: String,
        byteLen: Long,
        sha256: String,
        blobRef: String?,
        codec: Int
    )

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET timestamp=:timestamp WHERE id=:id")
    suspend fun updateTime(id: Int, timestamp: Long)

    @Query("SELECT COUNT(*) FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0")
    suspend fun itemCount(): Int

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE id=:id AND deleted=0 LIMIT 1")
    suspend fun get(id: Int): ClipboardEntry?

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE rowId=:rowId AND deleted=0 LIMIT 1")
    suspend fun get(rowId: Long): ClipboardEntry?

    @Query("SELECT EXISTS(SELECT 1 FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=0 AND deleted=0)")
    suspend fun haveUnpinned(): Boolean

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=0 AND deleted=0")
    suspend fun getAllUnpinned(): List<ClipboardEntry>

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0 ORDER BY pinned DESC, timestamp DESC")
    fun allEntries(): PagingSource<Int, ClipboardEntry>

    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE text=:text AND sensitive=:sensitive AND deleted=0 LIMIT 1")
    suspend fun find(text: String, sensitive: Boolean = false): ClipboardEntry?

    /**
     * MBM v1.0.31：大文本（blob）条目按内容哈希去重。
     * 小文本仍走 [find]（text 列比较）；大文本的 text 列只是 preview，
     * 不能按 text 比较，必须按 sha256。
     */
    @Query("SELECT * FROM ${ClipboardEntry.TABLE_NAME} WHERE sha256=:sha256 AND sensitive=:sensitive AND deleted=0 LIMIT 1")
    suspend fun findBySha(sha256: String, sensitive: Boolean = false): ClipboardEntry?

    /**
     * MBM v1.0.31：条数上限清理改为**纯 SQL**（原实现每次插入都
     * getAllUnpinned() 全量拉内存，无上限历史下是 O(n) 内存炸弹）。
     * 只保留未置顶中最新 [limit] 条，其余软删。limit <= 0 时调用方不应调用
     * （0 = 无上限，见 ClipboardManager.removeOutdated）。
     */
    @Query(
        """UPDATE ${ClipboardEntry.TABLE_NAME} SET deleted=1 
           WHERE pinned=0 AND deleted=0 AND id NOT IN (
             SELECT id FROM ${ClipboardEntry.TABLE_NAME} 
             WHERE pinned=0 AND deleted=0 
             ORDER BY timestamp DESC LIMIT :limit)"""
    )
    suspend fun markUnpinnedOverLimit(limit: Int): Int

    /** 全部 blob 条目的 sha256（孤儿 blob 巡检用）。 */
    @Query("SELECT sha256 FROM ${ClipboardEntry.TABLE_NAME} WHERE blobRef IS NOT NULL AND deleted=0")
    suspend fun allBlobShas(): List<String>

    /** 这些条目引用的 blob sha（删除前 GC 文件用）。 */
    @Query("SELECT sha256 FROM ${ClipboardEntry.TABLE_NAME} WHERE blobRef IS NOT NULL AND id IN (:ids)")
    suspend fun blobShasOf(ids: List<Int>): List<String>

    @Query("SELECT id FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=0")
    suspend fun findAllIds(): IntArray

    @Query("SELECT id FROM ${ClipboardEntry.TABLE_NAME} WHERE pinned=0 AND deleted=0")
    suspend fun findUnpinnedIds(): IntArray

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET deleted=1 WHERE id in (:ids)")
    suspend fun markAsDeleted(vararg ids: Int)

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET DELETED=1 WHERE timestamp<:timestamp AND pinned=0 AND deleted=0")
    suspend fun markUnpinnedAsDeletedEarlierThan(timestamp: Long)

    @Query("UPDATE ${ClipboardEntry.TABLE_NAME} SET deleted=0 WHERE id in (:ids) AND deleted=1")
    suspend fun undoDelete(vararg ids: Int)

    @Query("DELETE FROM ${ClipboardEntry.TABLE_NAME} WHERE deleted=1")
    suspend fun realDelete()
}