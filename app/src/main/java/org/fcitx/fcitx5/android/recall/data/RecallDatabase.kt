/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * Recall 本地加密存储：SQLCipher + Room。
 * 数据库口令由 Android Keystore 中的 AES-GCM 密钥包裹后存在私有目录，
 * 参考 Urik 的做法：个人词典与学习数据均以 SQLCipher 加密存储在本地。
 */
package org.fcitx.fcitx5.android.recall.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.migration.Migration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import kotlinx.coroutines.flow.Flow
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * N77：补齐索引。此前表上**没有任何索引**，而写路径每次捕获都要跑
 * `duplicates` / `duplicatesBySha`（按 packageName + text / sha256 做 COUNT），
 * 捕获最快 120ms 一次 → 行数到十万级后每次捕获都是一次全表扫描，越用越卡。
 * 三个索引对应关系：
 *  - packageName+text   → [RecallDao.duplicates]
 *  - packageName+sha256 → [RecallDao.duplicatesBySha]
 *  - timestamp+id       → keyset 分页 [RecallDao.page]、过期清理 [RecallDao.deleteBefore]
 *                         与 [RecallDao.blobShasOfAllBefore]
 */
@Entity(
    tableName = "recall",
    indices = [
        Index(name = "index_recall_packageName_text", value = ["packageName", "text"]),
        Index(name = "index_recall_packageName_sha256", value = ["packageName", "sha256"]),
        Index(name = "index_recall_timestamp_id", value = ["timestamp", "id"])
    ]
)
data class RecallRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appLabel: String,
    val text: String,
    val source: String,      // NODE / OCR / IMPORT
    val timestamp: Long,
    /**
     * MBM v1.0.31 大文本外置（无上限）：[text] 列对 blob 条目只存 preview，
     * 全文在 filesDir/recall_blobs/<sha256>.bin，由 [blobRef] 指向。
     * null = 小文本，全文就在 [text] 列。
     */
    val blobRef: String? = null,
    /** 全文 UTF-8 字节数。 */
    @ColumnInfo(defaultValue = "0")
    val byteLen: Long = 0L,
    /** 全文 SHA-256（大文本去重走它；小文本也计算，备统一检索）。 */
    @ColumnInfo(defaultValue = "\"\"")
    val sha256: String = "",
    /** 0=内联；1=blob 文件。 */
    @ColumnInfo(defaultValue = "0")
    val codec: Int = 0
) {
    val isBlob: Boolean get() = blobRef != null
}

@Dao
interface RecallDao {

    @Insert
    suspend fun insert(record: RecallRecord): Long

    /**
     * P1-4 修复：查重 + 插入收进同一事务，消灭「两条并发 record 同时 duplicated==0
     * 然后各插一行」的竞态（此前去重与插入之间无事务保护）。
     * @return 新插入行的 id；重复时返回 -1。
     */
    @Transaction
    suspend fun insertIfAbsent(record: RecallRecord): Long {
        val dup = if (record.isBlob) {
            duplicatesBySha(record.packageName, record.sha256, 0L)
        } else {
            duplicates(record.packageName, record.text, 0L)
        }
        return if (dup > 0) -1L else insert(record)
    }

    // MBM v1.0.23：应用户要求记忆召回为无上限，移除 LIMIT 300；
    // UI 侧改为分块渲染（RecallActivity#render），数千条也不会 ANR。
    // MBM v1.0.31：分页查询（P1）见 page()；all() 保留给一次性导出/统计。
    @Query("SELECT * FROM recall ORDER BY timestamp DESC")
    fun all(): Flow<List<RecallRecord>>

    /** MBM v1.0.31：keyset 分页（历史无上限：不再整表进内存）。
     *  游标 = (timestamp, id) 二元组，防同毫秒插入的行被跳过。 */
    @Query(
        """SELECT * FROM recall 
           WHERE timestamp < :before OR (timestamp = :before AND id < :idBefore)
           ORDER BY timestamp DESC, id DESC LIMIT :n"""
    )
    suspend fun page(before: Long, idBefore: Long, n: Int): List<RecallRecord>

    @Query(
        """SELECT * FROM recall WHERE text LIKE '%' || :q || '%' 
           ORDER BY timestamp DESC LIMIT :limit"""
    )
    fun search(q: String, limit: Int = 2000): Flow<List<RecallRecord>>

    @Query("SELECT * FROM recall WHERE packageName = :pkg ORDER BY timestamp DESC")
    fun byPackage(pkg: String): Flow<List<RecallRecord>>

    @Query("DELETE FROM recall WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>): Int

    @Query("DELETE FROM recall")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM recall")
    suspend fun count(): Int

    /** 同一应用短时间内重复捕获同一段文本时不重复记录 */
    @Query(
        """SELECT COUNT(*) FROM recall 
           WHERE packageName = :pkg AND text = :text AND timestamp > :since"""
    )
    suspend fun duplicates(pkg: String, text: String, since: Long): Int

    /** MBM v1.0.31：大文本（blob）条目按内容哈希去重（text 列只是 preview，不能按它比）。 */
    @Query(
        """SELECT COUNT(*) FROM recall 
           WHERE packageName = :pkg AND sha256 = :sha AND timestamp > :since"""
    )
    suspend fun duplicatesBySha(pkg: String, sha: String, since: Long): Int

    /** 这些条目引用的 blob sha（删除前 GC 文件用）。 */
    @Query("SELECT sha256 FROM recall WHERE blobRef IS NOT NULL AND id IN (:ids)")
    suspend fun blobShasOf(ids: List<Long>): List<String>

    /** 全部 blob 条目的 sha（孤儿 blob 巡检用）。 */
    @Query("SELECT sha256 FROM recall WHERE blobRef IS NOT NULL")
    suspend fun allBlobShas(): List<String>

    @Query("DELETE FROM recall WHERE timestamp < :before")
    suspend fun deleteBefore(before: Long): Int

    /** 将被过期清理的条目引用的 blob sha（trim 前 GC 用）。 */
    @Query("SELECT sha256 FROM recall WHERE blobRef IS NOT NULL AND timestamp < :before")
    suspend fun blobShasOfAllBefore(before: Long): List<String>
}

@Database(entities = [RecallRecord::class], version = 3, exportSchema = true
)
    abstract class RecallDatabase : RoomDatabase() {

        abstract fun dao(): RecallDao

        companion object {
            /**
             * MBM v1.0.31 手动迁移 1→2（v1 schema 从未导出，无法走 AutoMigration）：
             * 新增 blob 外置四列（大文本无上限）。默认值与 RecallRecord 注解一致，
             * 旧行（全部内联）迁移后 codec=0/blobRef=null，语义不变。
             */
            private val MIGRATE_1_2 = object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE `recall` ADD COLUMN `blobRef` TEXT")
                    db.execSQL("ALTER TABLE `recall` ADD COLUMN `byteLen` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `recall` ADD COLUMN `sha256` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE `recall` ADD COLUMN `codec` INTEGER NOT NULL DEFAULT 0")
                }
            }

            /**
             * N77：手动迁移 2→3（schema 变更只加索引，不能走 AutoMigration 的默认实现）。
             * 建索引是纯加速操作，失败只影响查询性能，不丢数据。
             */
            private val MIGRATE_2_3 = object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_recall_packageName_text` " +
                            "ON `recall` (`packageName`, `text`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_recall_packageName_sha256` " +
                            "ON `recall` (`packageName`, `sha256`)"
                    )
                    db.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_recall_timestamp_id` " +
                            "ON `recall` (`timestamp`, `id`)"
                    )
                }
            }

            @Volatile
            private var instance: RecallDatabase? = null

            fun get(context: Context): RecallDatabase =
                instance ?: synchronized(this) {
                    instance ?: build(context).also { instance = it }
                }

            private fun build(context: Context): RecallDatabase {
            // P1-5：raw key（32B 随机）直传 → SupportOpenHelperFactory 内部执行
            // PRAGMA key = "x'<hex>'"，绕过 PBKDF2 KDF；
            // raw key 本身由 Keystore AES-256-GCM（StrongBox 优先）信封加密，
            // 密文落 EncryptedSharedPreferences（见文件末尾 RecallKeyManager）。
            val factory: SupportSQLiteOpenHelper.Factory =
                SupportOpenHelperFactory(RecallKeyManager.rawKey(context))
            return Room.databaseBuilder(context, RecallDatabase::class.java, "recall.db")
                .openHelperFactory(factory)
                .addMigrations(MIGRATE_1_2, MIGRATE_2_3)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}

/**
 * 数据库 raw key 的 Keystore 信封加密（MBM v1.0.26 / P1-5）。
 *
 * 唯一密钥链路（裁决「只认这一条」）：
 *  1. raw key = 32 字节 SecureRandom 随机数（非口令派生）；
 *     SQLCipher 侧 [SupportOpenHelperFactory] 传 ByteArray → 内部执行
 *     `PRAGMA key = "x'<hex>'"`，即 raw key 直用，绕过 PBKDF2 KDF。
 *  2. raw key 由 Keystore AES-256-GCM 密钥（StrongBox 优先，不可降级到 TEE 时回退）
 *     信封加密后，密文存 **EncryptedSharedPreferences**（MasterKey 本身就在 Keystore 里）。
 *  3. Keystore 密钥 `setUserAuthenticationRequired(false)` 保证 DB 后台可开。
 *     （v1.0.39：原「查看前设备凭证 UI 门」已整体移除——属虚假安全，只挡机主；
 *     静态安全由本加密链路独立承担。）
 *
 * 效果：文件被提取也解不开（密钥材料在 TEE 不可导出）；
 * 时间线打开即可查看，无需任何验证步骤（v1.0.39 裁决）。
 *
 * MBM v1.0.34：internal 化——RecallBlobStore 的 blob 文件静态加密复用同一
 * raw key（同一信任域，避免第二条密钥链路）。
 */
internal object RecallKeyManager {

    private const val KEY_ALIAS = "mbm_recall_db_key"
    private const val ENCRYPTED_PREFS = "mbm_recall_secure_prefs"
    private const val KEY_WRAPPED = "wrapped_raw_key"
    private const val KEY_IV = "wrapped_iv"
    private const val KEY_SIZE = 32

    @Volatile
    private var cachedRawKey: ByteArray? = null

    /** raw key（32B）。内存中只保留一份；进程被杀则由 Keystore 重新解封。 */
    fun rawKey(context: Context): ByteArray {
        cachedRawKey?.let { return it }
        return synchronized(this) {
            cachedRawKey ?: loadOrCreate(context, encryptedPrefs(context)).also {
                cachedRawKey = it
            }
        }
    }

    /** 供 PRAGMA key = "x'..'" 直接消费的 hex 形式 */
    fun rawKeyHex(context: Context): String =
        rawKey(context).joinToString("") { "%02x".format(it) }

    private fun encryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setUserAuthenticationRequired(false)   // 后台可开 DB；UI 门另行控制
            .setRequestStrongBoxBacked(true)         // 有 StrongBox 优先（异常则回退）
            .let { builder ->
                runCatching { builder.build() }.getOrElse {
                    // 设备无 StrongBox：回退 TEE
                    MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .setUserAuthenticationRequired(false)
                        .build()
                }
            }
        return EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun loadOrCreate(context: Context, prefs: SharedPreferences): ByteArray {
        // 已有密文 → 解封失败（例如 Keystore 被清）时重建并让调用方按新库处理
        val wrappedB64 = prefs.getString(KEY_WRAPPED, null)
        val ivB64 = prefs.getString(KEY_IV, null)
        if (wrappedB64 != null && ivB64 != null) {
            val raw = runCatching {
                val iv = Base64.decode(ivB64, Base64.NO_WRAP)
                val data = Base64.decode(wrappedB64, Base64.NO_WRAP)
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
                    doFinal(data)
                }
            }.getOrNull()
            if (raw != null && raw.size == KEY_SIZE) return raw
        }
        // 首次：生成 raw key 并信封加密落盘
        val raw = ByteArray(KEY_SIZE).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, secretKey())
        }
        prefs.edit()
            .putString(KEY_WRAPPED, Base64.encodeToString(cipher.doFinal(raw), Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
        return raw
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // 明文见上面的 [encryptedPrefs]：不需要用户认证即可开库
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
