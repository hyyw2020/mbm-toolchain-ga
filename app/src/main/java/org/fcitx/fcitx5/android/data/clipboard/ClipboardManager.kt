/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.annotation.Keep
import androidx.room.Room
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDao
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardDatabase
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.crypto.AppKeyManager
import org.fcitx.fcitx5.android.data.emoji.ClipboardStickerExtractor
import org.fcitx.fcitx5.android.data.emoji.CustomEmojiRepository
import org.fcitx.fcitx5.android.data.password.PrivacyController
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.utils.WeakHashSet
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.clipboardManager
import org.fcitx.fcitx5.android.utils.imeExceptionHandler
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

object ClipboardManager : ClipboardManager.OnPrimaryClipChangedListener,
    CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.Default + imeExceptionHandler) {
    private lateinit var clbDb: ClipboardDatabase
    private lateinit var clbDao: ClipboardDao

    private const val DB_NAME = "clbdb"
    private const val DB_MIGRATION_PREFS = "mbm_clip_db_migration"
    private const val KEY_DB_ENCRYPTED_V1 = "encrypted_v1"

    /** 迁移分批行数：内联文本 ≤256KB/行，50 行最坏 ~12MB 内存。 */
    private const val DUMP_BATCH = 50
    // N34：单条 SQL 的 IN(...) 占位符上限（SQLite 默认 SQLITE_MAX_VARIABLE_NUMBER=999），分批避免崩溃
    private const val DELETE_CHUNK = 500

    fun interface OnClipboardUpdateListener {
        fun onUpdate(entry: ClipboardEntry)
    }

    private val clipboardManager = appContext.clipboardManager

    private val mutex = Mutex()

    var itemCount: Int = 0
        private set

    private suspend fun updateItemCount() {
        itemCount = clbDao.itemCount()
    }

    private val onUpdateListeners = WeakHashSet<OnClipboardUpdateListener>()

    var transformer: ((String) -> String)? = null

    fun addOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.add(listener)
    }

    fun removeOnUpdateListener(listener: OnClipboardUpdateListener) {
        onUpdateListeners.remove(listener)
    }

    private val enabledPref = AppPrefs.getInstance().clipboard.clipboardListening

    @Keep
    private val enabledListener = ManagedPreference.OnChangeListener<Boolean> { _, value ->
        if (value) {
            clipboardManager.addPrimaryClipChangedListener(this)
        } else {
            clipboardManager.removePrimaryClipChangedListener(this)
        }
    }

    private val limitPref = AppPrefs.getInstance().clipboard.clipboardHistoryLimit

    @Keep
    private val limitListener = ManagedPreference.OnChangeListener<Int> { _, _ ->
        launch {
            removeOutdated()
            // N100：上限改小后必须重新计数并清掉已被软删的 lastEntry。
            // 只 removeOutdated() 的话 itemCount 仍停在旧值（「列表为空」判断失真），
            // 候选栏/提示条还会继续显示那条已被软删的条目。
            // Room 的 PagingSource 由 UPDATE 自动失效 → 候选列表随之重新拉取。
            updateItemCount()
            val last = lastEntry ?: return@launch
            // 已被上限清理（软删）：置空，避免候选栏仍拿它上屏
            if (clbDao.get(last.id) == null) lastEntry = null
        }
    }

    var lastEntry: ClipboardEntry? = null

    private fun updateLastEntry(entry: ClipboardEntry) {
        lastEntry = entry
        onUpdateListeners.forEach { it.onUpdate(entry) }
    }

    fun init(context: Context) {
        // MBM v1.0.34（R3）：clbdb 落入 SQLCipher 加密存储（raw key 由 Keystore
        // 信封加密，见 AppKeyManager）。升级用户首次启动时把旧明文库回灌进
        // 密文库（后台一次性，失败则还原明文库保可用）。
        val key = AppKeyManager.rawKey(context)
        val marker = context.getSharedPreferences(DB_MIGRATION_PREFS, Context.MODE_PRIVATE)
        val marked = marker.getBoolean(KEY_DB_ENCRYPTED_V1, false)
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!marked && dbFile.exists()) {
            // 先把明文库改名备份，释放 DB_NAME 给密文库；主文件备份失败则不冒险加密，退回明文
            val bak = backupLegacyDb(dbFile)
            if (bak == null || !bak.exists()) {
                Timber.w("backupLegacyDb failed; keep plaintext clbdb (skip encryption)")
                clbDb = buildPlainDb(context)
                clbDao = clbDb.clipboardDao()
            } else {
                // N53：密文库打开失败（密钥不匹配等）必须有兜底，否则 init 抛异常致输入法启动崩溃
                clbDb = runCatching { buildEncryptedDb(context, key) }.getOrElse {
                    Timber.e(it, "buildEncryptedDb failed; falling back to plaintext clbdb")
                    buildPlainDb(context)
                }
                clbDao = clbDb.clipboardDao()
                launch { backfillLegacyDb(context, bak) }
            }
        } else {
            clbDb = runCatching { buildEncryptedDb(context, key) }.getOrElse {
                Timber.e(it, "buildEncryptedDb failed; falling back to plaintext clbdb")
                buildPlainDb(context)
            }
            clbDao = clbDb.clipboardDao()
            if (!marked) markDbEncrypted(context)
        }
        enabledListener.onChange(enabledPref.key, enabledPref.getValue())
        enabledPref.registerOnChangeListener(enabledListener)
        limitListener.onChange(limitPref.key, limitPref.getValue())
        limitPref.registerOnChangeListener(limitListener)
        launch { updateItemCount() }
        // MBM v1.0.31：清理崩溃残留的 .tmp，并回收孤儿 blob
        launch {
            runCatching {
                ClipboardBlobStore.cleanupTmpFiles()
                val known = clbDao.allBlobShas().toSet()
                val orphans = ClipboardBlobStore.deleteOrphans(known)
                if (orphans > 0) Timber.i("ClipboardManager: GC %d orphan blobs", orphans)
            }.onFailure { Timber.w(it, "blob GC failed") }
        }
    }

    private fun buildEncryptedDb(context: Context, key: ByteArray): ClipboardDatabase =
        Room.databaseBuilder(context, ClipboardDatabase::class.java, DB_NAME)
            .openHelperFactory(SupportOpenHelperFactory(key))
            // allow wipe the database instead of crashing when downgrade
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    /** 明文兜底库：密文库打开失败（密钥不匹配等）时退回，保证输入法可启动（N53）。 */
    private fun buildPlainDb(context: Context): ClipboardDatabase =
        Room.databaseBuilder(context, ClipboardDatabase::class.java, DB_NAME)
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    /** 旧明文 clbdb（含 -wal/-shm）改名备份；主文件 rename 失败返回 null（N65）。 */
    private fun backupLegacyDb(dbFile: File): File? {
        val bak = File(dbFile.parentFile, "$DB_NAME.legacy.bak")
        // N65：renameTo 返回值必须检查；失败时明文库仍在原地，不能继续加密流程（否则密钥匙开明文库崩溃）
        if (dbFile.exists() && !dbFile.renameTo(bak)) return null
        listOf("-wal", "-shm").forEach { suffix ->
            val f = File(dbFile.path + suffix)
            if (f.exists() && !f.renameTo(File(bak.path + suffix))) {
                Timber.w("backupLegacyDb: failed to backup $suffix (non-fatal)")
            }
        }
        return bak
    }

    /**
     * 把备份的明文库回灌进密文库（一次性，后台执行）。
     * 步骤：明文 Room 打开 .bak（触发 autoMigration 到当前 schema）→
     * 按 id 升序分批 [DUMP_BATCH] 行读入 → [ClipboardDao.insertAll] 写入
     * 密文库（Room 事务机制，失效追踪正常触发，UI 自动刷新）→ 删备份、写标记。
     * 任一步失败：删掉半成品密文库、还原 .bak、退回明文 DB（不写标记，
     * 下次启动重试）——升级路径任何环节都不能让用户丢历史。
     */
    private suspend fun backfillLegacyDb(context: Context, bak: File) {
        if (!bak.exists()) {
            markDbEncrypted(context)
            return
        }
        runCatching {
            // 1. 明文 Room 打开备份：触发 autoMigration 到当前 schema
            val plainDb = Room.databaseBuilder(context, ClipboardDatabase::class.java, bak.name)
                .build()
            var lastId = -1
            var copied = 0L
            try {
                val plainDao = plainDb.clipboardDao()
                while (true) {
                    val batch = plainDao.dumpRange(lastId, DUMP_BATCH)
                    if (batch.isEmpty()) break
                    // N53：回灌不携带原 id（置 0 让 Room 自增），避免与回灌期间用户新复制的条目
                    // 发生 UNIQUE 冲突 → 触发旧的「删密文库还原明文」破坏性路径丢数据
                    clbDao.insertAll(batch.map { it.copy(id = 0) })
                    lastId = batch.last().id
                    copied += batch.size
                }
            } finally {
                plainDb.close()
            }
            // 2. 收尾：删备份（含 wal/shm），写标记
            listOf(bak, File(bak.path + "-wal"), File(bak.path + "-shm")).forEach { it.delete() }
            markDbEncrypted(context)
            Timber.i("ClipboardManager: legacy clbdb backfilled ($copied rows) into encrypted db")
        }.onFailure { e ->
            Timber.w(e, "clbdb backfill failed; restoring legacy plain db")
            runCatching {
                // 删半成品密文库，还原明文库，退回明文 DB（本次启动仍可用）
                clbDb.close()
                listOf(
                    context.getDatabasePath(DB_NAME),
                    File(context.getDatabasePath(DB_NAME).path + "-wal"),
                    File(context.getDatabasePath(DB_NAME).path + "-shm")
                ).forEach { it.delete() }
                listOf(bak, File(bak.path + "-wal"), File(bak.path + "-shm")).forEachIndexed { i, f ->
                    if (f.exists()) {
                        val target = if (i == 0) context.getDatabasePath(DB_NAME)
                        else File(context.getDatabasePath(DB_NAME).path + f.name.substring(bak.name.length))
                        f.renameTo(target)
                    }
                }
                clbDb = Room.databaseBuilder(context, ClipboardDatabase::class.java, DB_NAME)
                    .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                    .build()
                clbDao = clbDb.clipboardDao()
            }.onFailure { Timber.w(it, "legacy db restore failed") }
        }
    }

    private fun markDbEncrypted(context: Context) {
        context.getSharedPreferences(DB_MIGRATION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DB_ENCRYPTED_V1, true)
            .commit()
    }

    suspend fun get(id: Int) = clbDao.get(id)

    suspend fun haveUnpinned() = clbDao.haveUnpinned()

    fun allEntries() = clbDao.allEntries()

    suspend fun pin(id: Int) = clbDao.updatePinStatus(id, true)

    suspend fun unpin(id: Int) = clbDao.updatePinStatus(id, false)

    suspend fun updateText(id: Int, text: String) {
        // MBM v1.0.31：编辑后按大小重新分流——大文本重写 blob，缩小的删 blob
        val entry = clbDao.get(id) ?: return
        // v1.0.77：trim 只用于「是否全空白」判定，不再参与落库——旧实现把用户
        // 编辑/复制内容首尾的空白与换行静默 trim 掉，破坏逐字保真。
        if (ClipboardTextFidelity.shouldDrop(text)) return
        val stored = ClipboardTextFidelity.stored(text)
        // U2 护栏①：内容未变则不改动——避免大文本条目在「未编辑即保存」时被降级为内联预览并删除 blob
        if (ClipboardTextFidelity.sha256(stored) == entry.sha256) return
        // U2 护栏②：blob 条目且编辑框文本仍等于预览（全文加载失败退化为预览、用户未实质修改）
        // → 不降级为内联、不删除 blob，保住 100MB 原文
        if (entry.isBlob && stored == entry.text) return
        val bytes = stored.toByteArray()
        val oldRef = entry.blobRef
        val newRef: String?
        if (bytes.size <= ClipboardBlobStore.INLINE_THRESHOLD) {
            newRef = null
            clbDao.updateTextAndMeta(id, stored, bytes.size.toLong(), ClipboardTextFidelity.sha256(stored), null, ClipboardEntry.CODEC_INLINE)
        } else {
            val handle = ClipboardBlobStore.write(stored.byteInputStream())
            newRef = handle.sha256
            clbDao.updateTextAndMeta(id, handle.preview, handle.byteLen, handle.sha256, handle.sha256, ClipboardEntry.CODEC_BLOB)
        }
        // v1.0.81 真 bug 修复：blob 是内容寻址共享的，旧实现在更新元数据前就
        // 直接 ClipboardBlobStore.delete(旧 blobRef)，会永久删除其它仍引用同一
        // sha 的条目全文（不同 sensitive 的重复行、软删可 undo 的行等）。
        // 现在先完成元数据更新（上面），再按引用计数条件回收，与
        // delete()/realDelete() 的 N01/N02 机制一致；不改任何用户可见功能。
        if (ClipboardBlobRefPolicy.shouldRecycle(
                oldRef, newRef, clbDao.allBlobShas().toSet()
            )
        ) {
            oldRef?.let { ClipboardBlobStore.delete(it) }
        }
        lastEntry?.let {
            if (id == it.id) updateLastEntry(clbDao.get(id) ?: it)
        }
    }

    suspend fun delete(id: Int) {
        // N01/N02：软删除不立即物理删除 blob——可被 undo 恢复，且共享同一 blob 的其它条目不受影响。
        // blob 的真正回收推迟到 realDelete() 的孤儿 GC（gcOrphanBlobs）。
        clbDao.markAsDeleted(id)
        updateItemCount()
    }

    suspend fun deleteAll(skipPinned: Boolean = true): IntArray {
        val ids = if (skipPinned) {
            clbDao.findUnpinnedIds()
        } else {
            clbDao.findAllIds()
        }
        // N34：IN(...) 超上限（SQLite 默认变量数上限 ~999）会抛异常致进程崩溃，分批提交
        ids.toList().chunked(DELETE_CHUNK).forEach { chunk ->
            val arr = chunk.toIntArray()
            clbDao.markAsDeleted(*arr)
        }
        updateItemCount()
        return ids
    }

    suspend fun undoDelete(vararg ids: Int) {
        // N34：同上，分批避免 IN(...) 超上限
        ids.toList().chunked(DELETE_CHUNK).forEach { chunk ->
            clbDao.undoDelete(*chunk.toIntArray())
        }
        updateItemCount()
    }

    suspend fun realDelete() {
        clbDao.realDelete()        // 先移除软删除行
        gcOrphanBlobs()           // N01/N02：再回收不再被任何存活条目引用的 blob（引用计数式 GC）
    }

    suspend fun nukeTable() {
        gcAllBlobs()
        withContext(coroutineContext) {
            clbDb.clearAllTables()
            updateItemCount()
        }
    }

    /** 删除这些条目引用的 blob 文件（先查后删，DB 不持有外键）。 */
    /**
     * N01/N02：引用计数式 blob GC——删除不再被任何存活条目（deleted=0）引用的 blob 文件。
     * 软删除不立即回收，故删除/撤销都不会误删共享 blob 或丢失可恢复的内容。
     */
    private suspend fun gcOrphanBlobs() {
        runCatching {
            val live = clbDao.allBlobShas().toSet()
            ClipboardBlobStore.deleteOrphans(live)
        }.onFailure { Timber.w(it, "gcOrphanBlobs failed") }
    }

    /** 删除 store 中的全部 blob 文件（nukeTable 全量清空用）。 */
    private suspend fun gcAllBlobs() {
        runCatching {
            ClipboardBlobStore.deleteOrphans(emptySet())
        }.onFailure { Timber.w(it, "gcAllBlobs failed") }
    }

    /**
     * 由 MBM 的 Recall 功能调用：把一段文本作为剪贴板条目导入，
     * 这样用户在使用输入法时即可在剪贴板候选里看到并一键上屏。
     *
     * @return true=本次新增（去重命中则视为已存在返回 false，但会刷新时间/置顶）
     */
    suspend fun addText(text: String, pinned: Boolean = false): Boolean =
        insertEntry(text, pinned, sensitive = false, refreshLast = true)

    /**
     * 由 MBM 的 Recall 捕获后自动调用：把捕获文本写入剪贴板库，
     * 使其在输入法剪贴板候选里可见、可一键上屏。
     *
     * ⚠️ 与 [addText]（用户手动「导入到输入法」）和 [onPrimaryClipChanged]（用户自己复制）
     * 的关键差异 —— 专为「不刷屏」设计：
     *  1. **不写系统剪贴板**：既不覆盖用户刚复制的内容，也不会触发 [onPrimaryClipChanged]
     *     导致同一段文本被重复记录两次；
     *  2. **不更新 [lastEntry]**：不刷新「最近一条」，候选栏不会因捕获而跳动；
     *  3. 默认未置顶（pinned=false），可被正常的条数上限清理，不挤占用户复制的条目。
     *
     * MBM v1.0.31：**长度不再有上限**——超过内联阈值的文本自动外置 blob
     * （见 [ClipboardBlobStore]），条数上限由用户偏好控制（0 = 无上限）。
     *
     * @return true=本次新增；false=已存在（此时仅刷新时间，不置顶、不刷新最近一条）
     */
    suspend fun addRecallText(text: String, pinned: Boolean = false): Boolean =
        insertEntry(text, pinned, sensitive = false, refreshLast = false)

    /**
     * MBM v1.0.31：统一插入路径。按大小分流：
     *  - ≤ INLINE_THRESHOLD：内联 text 列（快路径）；
     *  - > INLINE_THRESHOLD：外置 blob 文件，text 列存 preview。
     * 去重：小文本按 text，大文本按 sha256（内容寻址）。
     */
    private suspend fun insertEntry(
        text: String,
        pinned: Boolean,
        sensitive: Boolean,
        refreshLast: Boolean
    ): Boolean {
        // v1.0.77：存储原文，不 trim。旧实现把首尾空白/换行静默吃掉，用户复制
        // 「  abc\n」历史里变成「abc」，逐字保真失效。语义固化在纯 JVM 策略
        // ClipboardTextFidelity（全空白才丢弃 / 原文落库 / 原文哈希），可回归单测。
        if (ClipboardTextFidelity.shouldDrop(text)) return false
        val stored = ClipboardTextFidelity.stored(text)
        val bytes = stored.toByteArray()
        val entry = if (bytes.size <= ClipboardBlobStore.INLINE_THRESHOLD) {
            ClipboardEntry(
                text = stored,
                pinned = pinned,
                timestamp = System.currentTimeMillis(),
                sensitive = sensitive,
                blobRef = null,
                byteLen = bytes.size.toLong(),
                sha256 = ClipboardTextFidelity.sha256(stored),
                codec = ClipboardEntry.CODEC_INLINE
            )
        } else {
            val handle = ClipboardBlobStore.write(stored.byteInputStream())
            ClipboardEntry(
                // 大文本条目的 text 列存 preview：列表 UI 零改动即显示摘要
                text = handle.preview,
                pinned = pinned,
                timestamp = System.currentTimeMillis(),
                sensitive = sensitive,
                blobRef = handle.sha256,
                byteLen = handle.byteLen,
                sha256 = handle.sha256,
                codec = ClipboardEntry.CODEC_BLOB
            )
        }
        return mutex.withLock {
            val existing = if (entry.isBlob) clbDao.findBySha(entry.sha256, sensitive)
            else clbDao.find(entry.text, sensitive)
            if (existing != null) {
                if (pinned && !existing.pinned) clbDao.updatePinStatus(existing.id, true)
                clbDao.updateTime(existing.id, System.currentTimeMillis())
                if (refreshLast) updateLastEntry(existing.copy(timestamp = System.currentTimeMillis()))
                return@withLock false
            }
            clbDb.withTransaction {
                clbDao.insert(entry)
                removeOutdated()
            }
            updateItemCount()
            if (refreshLast) updateLastEntry(entry)
            true
        }
    }

    /**
     * MBM v1.0.31：从内容 URI 流式导入文本文件（无大小上限）。
     * 由 ClipboardImportActivity 调用。导入即写入剪贴板历史（内联或 blob 分流）。
     *
     * @return 成功返回字节数；失败返回 null
     */
    suspend fun importFromUri(uri: Uri): Long? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = appContext.contentResolver
            val input: InputStream = resolver.openInputStream(uri)
                ?: throw IllegalStateException("cannot open $uri")
            input.use { stream ->
                val handle = ClipboardBlobStore.write(stream)
                val existing = mutex.withLock { clbDao.findBySha(handle.sha256, false) }
                if (existing != null) {
                    mutex.withLock { clbDao.updateTime(existing.id, System.currentTimeMillis()) }
                    return@use handle.byteLen
                }
                val entry = ClipboardEntry(
                    text = handle.preview,
                    pinned = false,
                    timestamp = System.currentTimeMillis(),
                    blobRef = handle.sha256,
                    byteLen = handle.byteLen,
                    sha256 = handle.sha256,
                    codec = ClipboardEntry.CODEC_BLOB
                )
                mutex.withLock {
                    clbDb.withTransaction {
                        clbDao.insert(entry)
                        removeOutdated()
                    }
                    updateItemCount()
                    updateLastEntry(entry)
                }
                handle.byteLen
            }
        }.onFailure { Timber.w(it, "importFromUri($uri) failed") }.getOrNull()
    }

    /**
     * MBM v1.0.31：读取条目全文（流式）。
     * 小文本 = 内存流；大文本 = blob 文件流。**绝不把大文本整体读进 String。**
     */
    fun readFullText(entry: ClipboardEntry): InputStream? {
        if (!entry.isBlob) return ByteArrayInputStream(entry.text.toByteArray())
        return ClipboardBlobStore.open(entry.blobRef!!)
    }

    /** 条目全文的 UTF-8 字节数（UI 展示「X MB」用，不读全文）。 */
    fun fullTextBytes(entry: ClipboardEntry): Long = entry.byteLen

    /** 存储统计：blob 总字节 + DB 条数（UI 展示）。 */
    suspend fun storageStats(): Pair<Long, Int> =
        ClipboardBlobStore.totalBytes() to clbDao.itemCount()

    private var lastClipTimestamp = -1L
    private var lastClipHash = 0

    override fun onPrimaryClipChanged() {
        val clip = clipboardManager.primaryClip ?: return
        /**
         * skip duplicate ClipData
         * https://developer.android.com/reference/android/content/ClipboardManager.OnPrimaryClipChangedListener#onPrimaryClipChanged()
         */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timestamp = clip.description.timestamp
            if (timestamp == lastClipTimestamp) return
            lastClipTimestamp = timestamp
        } else {
            val timestamp = System.currentTimeMillis()
            val hash = clip.hashCode()
            if (timestamp - lastClipTimestamp < 100L && hash == lastClipHash) return
            lastClipTimestamp = timestamp
            lastClipHash = hash
        }
        launch {
            // N04：隐私模式（密码框等）下，系统剪贴板内容不进历史，避免密码/令牌被记录
            if (PrivacyController.inPrivacyMode) return@launch
            // v1.0.79：本应用把图像写回系统剪贴板（富媒体不支持时的通用输出）
            // 会触发本回调。旧实现会从该 content URI 派生出一条私有路径文本
            // 塞进历史，形成用户看不懂的垃圾条目；这里整条跳过自我回环。
            if (isSelfFileProviderClip(clip)) return@launch
            // v1.0.73：复制侧富媒体通道。系统剪贴板携带 image 资产（ClipData.Item.uri）
            // 时，按用户偏好复制进自定义表情容器，使其在表情面板内可见、可再次提交。
            // v1.0.79：返回本次成功导入的全部 sticker token（不再只导入第一张）。
            val tokens = runCatching { captureImageSticker(clip) }
                .onFailure { Timber.w(it, "Failed to capture clipboard image sticker") }
                .getOrDefault(emptyList())
            val encodedTokens = ClipboardMediaEntryPolicy.encodeTokens(tokens)
            val entry = ClipboardEntry.fromClipData(clip, transformer)
            if (entry == null) {
                // 只有图像 URI、连派生文本都取不到时，仍要记录这次复制的图像
                if (encodedTokens != null) {
                    runCatching {
                        insertEntry(encodedTokens, pinned = false, sensitive = false, refreshLast = true)
                    }.onFailure {
                        Timber.w(it, "Failed to insert image clipboard entry from primary clip")
                    }
                }
                return@launch
            }
            if (entry.text.isBlank()) return@launch
            // v1.0.79：本应用自己写回系统剪贴板的图像 token 不再重复录入（防回环）。
            if (ClipboardMediaEntryPolicy.parseTokens(entry.text) != null) return@launch
            // v1.0.79 真 bug 修复：宿主应用复制自定义表情时，ClipData.Item.text 常是
            // 应用私有编码串（控制字符 + emoji + Latin 扩展乱码），旧实现把它当作
            // 可粘贴正文原样落库，底部建议条 text.take(42) 显示乱码、点击 commitText
            // 又输出同一串乱码。仅当本次确实捕获到图像、且该文本被判为私有编码 /
            // URI 派生文本时，改为记录可解析、可富媒体上屏的图像 token 条目。
            // 普通可读文本复制完全不经过此分支，行为零变化。
            val imageEntry = if (encodedTokens != null &&
                ClipboardMediaEntryPolicy.shouldUseImageEntry(entry.text, true)
            ) {
                encodedTokens
            } else null
            val textToStore = imageEntry ?: entry.text
            // N51：统一走 insertEntry 分流（大文本外置 blob），避免全文塞 text 列触发
            // Row too big to fit into CursorWindow 崩溃。分流只有这一处。
            runCatching {
                insertEntry(
                    textToStore,
                    pinned = false,
                    sensitive = if (imageEntry != null) false else entry.sensitive,
                    refreshLast = true
                )
            }.onFailure {
                Timber.w(it, "Failed to insert clipboard entry from primary clip")
            }
        }
    }

    /**
     * v1.0.75：复制侧富媒体捕获（放宽多形态入口）。
     *
     * 旧实现只认「剪贴板声明 image 类 MIME + Item.uri」这一种形态，但宿主
     * 应用复制表情时几乎都不是这样：常见的是 content URI 不带 image 声明、
     * URI 藏在纯文本 / URI 列表 / HTML img 标签里，或只给 Intent 型 ClipData。
     * 旧实现第一步就 return，导致「复制表情后输入法毫无反应、完全失效」。
     *
     * 现在枚举所有 ClipData.Item 的 uri / text / htmlText / intent.data，
     * 用纯函数提取 content URI 候选，逐个交给仓库：由仓库读文件头魔数嗅探
     * 真实位图格式，非图像（短码、乱码、HTML）自动丢弃。这样「复制表情」
     * 只要系统剪贴板里能读到图像字节，就一定能落进表情面板可用。
     *
     * 边界：遵守隐私模式、用户开关、体积/条数上限；忽略本应用 FileProvider
     * 自身产生的 URI，避免提交→复制→再导入的回环；任何失败只跳过，绝不
     * 影响文本剪贴板历史。纯本地、不联网、不解析任何网络链接。
     */
    private fun captureImageSticker(clip: android.content.ClipData): List<String> {
        val enabled = runCatching {
            AppPrefs.getInstance().symbols.customEmojiClipboardCapture.getValue()
        }.getOrDefault(true)
        if (!enabled) return emptyList()

        val selfAuthority = appContext.packageName + ".fileprovider"
        val candidates = LinkedHashSet<Uri>()
        for (i in 0 until clip.itemCount) {
            val item = runCatching { clip.getItemAt(i) }.getOrNull() ?: continue
            runCatching { item.uri }.getOrNull()?.let { candidates.add(it) }
            runCatching { item.intent?.data }.getOrNull()?.let { candidates.add(it) }
            val text = runCatching { item.text?.toString() }.getOrNull()
            val html = runCatching { item.htmlText }.getOrNull()
            for (u in ClipboardStickerExtractor.extractContentUris(text) +
                ClipboardStickerExtractor.extractContentUris(html)) {
                runCatching { candidates.add(Uri.parse(u)) }
            }
        }
        if (candidates.isEmpty()) return emptyList()
        // v1.0.79 真 bug 修复：旧实现「第一个成功就 return」，用户一次复制一串
        // 表情时只入库第一张（用户实测只剩一个青蛙），其余被静默丢弃。现在遍历
        // 全部候选（仍受 MAX_CANDIDATES 与内容摘要去重约束），返回全部 token，
        // 让表情面板与剪贴板历史都能看到这一次复制的完整内容。
        val tokens = LinkedHashSet<String>()
        for (uri in candidates.take(ClipboardStickerExtractor.MAX_CANDIDATES)) {
            // v1.0.76：extractContentUris 用 ignoreCase 接受 "CONTENT://"，
            // 这里必须同样忽略大小写，否则大写 scheme 的候选会被「提取出来又过滤掉」。
            if (!"content".equals(uri.scheme, ignoreCase = true)) continue
            if (selfAuthority.equals(uri.authority, ignoreCase = true)) continue // 防自我复制回环
            val token = runCatching {
                CustomEmojiRepository.importClipboardImage(appContext, uri)
            }.onFailure { Timber.w(it, "importClipboardImage failed") }.getOrNull()
            if (token != null) tokens.add(token)
        }
        if (tokens.isNotEmpty()) onClipboardStickerCaptured?.invoke()
        return tokens.toList()
    }

    /**
     * v1.0.79：判断本次 ClipData 是否只是本应用 FileProvider 写回的图像。
     * EmojiRichContentCommitter 在目标编辑器不支持富媒体时会把图像 content URI
     * 写入系统剪贴板，这会再次触发 onPrimaryClipChanged；旧实现从该 URI 派生
     * 出一条应用私有路径文本塞进历史，形成看不懂的垃圾条目。
     *
     * 只在「含本应用 URI 且所有 Item 都没有用户文本」时认定为回环，避免误伤
     * 用户同时复制了图片与文字的场景。
     */
    private fun isSelfFileProviderClip(clip: android.content.ClipData): Boolean {
        val selfAuthority = appContext.packageName + ".fileprovider"
        var sawSelfUri = false
        for (i in 0 until clip.itemCount) {
            val item = runCatching { clip.getItemAt(i) }.getOrNull() ?: continue
            val uri = runCatching { item.uri }.getOrNull()
            if (uri != null && selfAuthority.equals(uri.authority, ignoreCase = true)) {
                sawSelfUri = true
            }
            val text = runCatching { item.text?.toString() }.getOrNull()
            if (!text.isNullOrBlank()) return false
        }
        return sawSelfUri
    }

    /**
     * v1.0.75：复制侧成功入库后的即时反馈钩子。由输入法服务注册，用一条
     * 轻量提示告诉用户「复制的图像表情已在表情面板可用、可一键上屏」，
     * 修复旧版静默无反馈、用户误以为功能完全失效的问题。未注册时为空操作。
     */
    @Volatile
    var onClipboardStickerCaptured: (() -> Unit)? = null

    /**
     * MBM v1.0.31：条数上限清理（纯 SQL，不再全量拉内存）。
     *
     * **语义重定义**：`clipboardHistoryLimit = 0` 现在表示**无上限**（不清理）。
     * 旧语义是 0 = 「删全部未置顶」（见 git 历史），迁移时首次启动会提示用户。
     */
    private suspend fun removeOutdated() {
        val limit = limitPref.getValue()
        if (limit <= 0) return
        clbDao.markUnpinnedOverLimit(limit)
    }

    // v1.0.77：sha256 口径统一由 ClipboardTextFidelity 按原文计算，
    // 避免「trim 后哈希」与「原文落库」不一致（旧实现的保真缺陷）。
}
