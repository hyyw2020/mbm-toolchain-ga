/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 */
package org.fcitx.fcitx5.android.data.smartdict

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.data.crypto.AppKeyManager
import org.fcitx.fcitx5.android.data.crypto.StreamingFileCipher
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.data.rules.RuleImporter
import org.fcitx.fcitx5.android.data.table.TableManager
import org.fcitx.fcitx5.android.input.clipboard.WordTokenizer
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 实验性模式 —— 离线智能词库系统。
 *
 * 全部在本地完成：
 *  - 汇总各引擎已安装的词典（Rime / fcitx5 拼音 / fcitx5 码表）
 *  - 提供单选、全选、自定义（多选）选择、删除能力
 *  - 基于本地使用统计生成「智能候选词」，不联网、不上传
 */
object SmartDictManager {

    enum class Kind(val display: String) {
        RIME("Rime 方案 / 词典"),
        PINYIN("拼音词库"),
        TABLE("码表输入法")
    }

    data class DictEntry(
        val name: String,
        val kind: Kind,
        val file: File,
        val sizeBytes: Long,
        val removable: Boolean
    )

    fun listAll(): List<DictEntry> {
        val result = mutableListOf<DictEntry>()

        // Rime 用户数据
        RuleImporter.rimeUserDir().listFiles()?.forEach {
            if (it.isFile && (it.name.endsWith(".yaml") || it.name.endsWith(".yml"))) {
                result += DictEntry(it.name, Kind.RIME, it, it.length(), true)
            }
        }

        // fcitx5 拼音词库
        PinyinDictManager.listDictionaries().forEach {
            result += DictEntry(it.name, Kind.PINYIN, it.file, it.file.length(), true)
        }

        // fcitx5 码表输入法
        runCatching {
            TableManager.inputMethods().forEach {
                result += DictEntry(it.name, Kind.TABLE, it.file, it.file.length(), true)
            }
        }.onFailure { Timber.w(it, "list table ims failed") }

        return result.sortedWith(compareBy({ it.kind.ordinal }, { it.name }))
    }

    fun delete(entries: List<DictEntry>): Int {
        var count = 0
        entries.forEach { entry ->
            if (!entry.removable) return@forEach
            runCatching {
                if (entry.file.isDirectory) entry.file.deleteRecursively() else entry.file.delete()
                count++
            }.onFailure { Timber.w(it, "delete ${'$'}{entry.file} failed") }
        }
        return count
    }

    // ---------- 本地使用统计（智能候选词）----------

    @Serializable
    private data class UsageMap(val counts: Map<String, Int> = emptyMap())

    private val storeFile: File by lazy {
        File(appContext.filesDir, "smart_dict_usage.json")
    }
    private val json = Json { prettyPrint = true }

    /** 写操作互斥锁：recordUsage/removeWord/clearUsage 均为「读-改-写」，
     *  commitText 每次提交都会在 Dispatchers.IO 并发调用，无锁会互相覆盖丢统计，
     * 且并发写同一文件可能产生损坏 JSON（随后 load 失败→save 清空，数据全丢）。 */
    private val writeMutex = Mutex()

    /**
     * N24：进程内缓存。旧实现每次上屏都是「读全文 JSON → 改一项 → 写全文 JSON」，
     * 连续输入时每次提交都要全量反序列化 + 全量序列化一遍，越用越卡。
     * 现在上屏只改内存计数，落盘交给 [scheduleFlush] 延迟合并，
     * 窗口内多次上屏只写一次盘。
     * 用并发 Map：[topWords] 可能被主线程在锁外并发读。
     */
    private val cache = ConcurrentHashMap<String, Int>()

    @Volatile
    private var cacheLoaded = false

    /** N24：合并写的延迟窗口；落盘统一放到共享 IO 线程。 */
    private const val FLUSH_DELAY_MS = 5000L
    private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: Job? = null

    // ---------- 个人学习（预测闭环）----------

    /**
     * 本地统计自上次同步到候选后是否有新增。IME 周期性调用
     * [org.fcitx.fcitx5.android.data.pinyin.customphrase.PersonalPhraseImporter.syncIfDirty]
     * 时据此判断是否需要重新注入候选，避免无谓的 reload。
     */
    @Volatile
    private var dirty = false

    /** 读取并清除 dirty 标记；返回 true 表示自上次同步后有新统计或变更 */
    fun consumeDirty(): Boolean {
        val d = dirty
        dirty = false
        return d
    }

    private fun prefs() =
        appContext.getSharedPreferences("mbm_smartdict", android.content.Context.MODE_PRIVATE)

    /**
     * 个人学习总开关（默认开启）。关闭后高频个人词不再自动进候选，
     * 但本地统计照常保留（开关重新打开即可立即生效，无需重新积累）。
     */
    fun isLearningEnabled(): Boolean = prefs().getBoolean("learning_enabled", true)

    fun setLearningEnabled(value: Boolean) {
        prefs().edit().putBoolean("learning_enabled", value).apply()
        dirty = true
    }

    /** 纯标点/数字串不计入个人词（与旧 recordUsage 的过滤口径一致） */
    private const val PUNCT = "!@#\$%^&*()-_=+[]{};:,.<>?/|\\\"'~`"

    /**
     * N23：统计文件里是用户真实输入过的词，此前以**明文**落在 filesDir，
     * 与「剪贴板数据已 AES-256-GCM 加密」不一致。这里复用项目既有能力：
     * 写入走 [StreamingFileCipher.EncryptingSink]，读取走
     * [StreamingFileCipher.openDecrypted]（自动识别遗留明文 → 升级不丢数据）。
     * 拿不到密钥时**不退回明文写盘**（那正是本 bug）。
     */
    private fun cipherKey(): ByteArray? = runCatching { AppKeyManager.rawKey(appContext) }
        .onFailure { Timber.w(it, "smart dict: 获取加密密钥失败") }
        .getOrNull()

    private fun writeEncrypted(file: File, payload: ByteArray, key: ByteArray) {
        FileOutputStream(file).use { fos ->
            StreamingFileCipher.EncryptingSink(fos, key).use { it.write(payload) }
        }
    }

    private fun load(): MutableMap<String, Int> = runCatching {
        if (!storeFile.exists()) return mutableMapOf()
        val key = cipherKey()
        val text = (key?.let { StreamingFileCipher.openDecrypted(storeFile, it) }
            ?: storeFile.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        json.decodeFromString<UsageMap>(text).counts.toMutableMap()
    }.getOrElse { e ->
        // 解析失败（损坏/半写文件）：保留现场备份，绝不把损坏文件当空表直接覆写
        if (storeFile.length() > 0) {
            val backup = File(storeFile.parentFile, "${storeFile.name}.corrupt")
            runCatching { storeFile.copyTo(backup, overwrite = true) }
                .onFailure { Timber.w(it, "backup corrupt usage file failed") }
            Timber.w(e, "smart dict usage file unreadable, backed up to ${backup.name}")
        }
        mutableMapOf()
    }

    private fun save(map: Map<String, Int>) = runCatching {
        val payload = json.encodeToString(UsageMap(map)).toByteArray(Charsets.UTF_8)
        val key = cipherKey()
        if (key == null) {
            Timber.w("smart dict usage 未落盘：加密密钥不可用（绝不退回明文写入）")
            return@runCatching
        }
        // 先写临时文件再原子重命名：避免写一半崩溃/被杀留下半文件，
        // 也避免与并发读者交错（rename 在同一文件系统上是原子的）
        val tmp = File(storeFile.parentFile, "${storeFile.name}.tmp")
        writeEncrypted(tmp, payload, key)
        if (!tmp.renameTo(storeFile)) {
            writeEncrypted(storeFile, payload, key) // 退化为直接写（仍优于不写）
            tmp.delete()
        }
    }.onFailure { Timber.w(it, "save smart dict usage failed") }

    /** 首次访问时把文件读进内存；之后所有读写都走 [cache]。 */
    private suspend fun ensureLoaded(): ConcurrentHashMap<String, Int> {
        if (!cacheLoaded) {
            val loaded = withContext(Dispatchers.IO) { load() }
            cache.clear()
            cache.putAll(loaded)
            cacheLoaded = true
        }
        return cache
    }

    /** 把内存快照落盘一次（调用方须持有 [writeMutex]）。 */
    private suspend fun flushLocked() {
        if (!cacheLoaded) return
        val snapshot = cache.toMap()
        withContext(Dispatchers.IO) { save(snapshot) }
    }

    /**
     * N24：延迟合并写。已有待落盘任务时直接把本次变更合并进那一次，
     * 否则延后 [FLUSH_DELAY_MS] 写一次 —— 连续上屏的 N 次写入合并成一次。
     */
    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = flushScope.launch {
            delay(FLUSH_DELAY_MS)
            writeMutex.withLock { flushLocked() }
        }
    }

    /**
     * 由输入法提交文本时调用；隐私模式下不调用。
     *
     * MBM v1.0.46：从「整段记一个词」升级为「按词切分逐词计频」——用户输入的整句/
     * 词组落屏时，先用 [WordTokenizer] 切出真实词块再分别累加，学到的是「词」而非
     * 偶发的整句拼接；敲对应拼音时这些高频个人词才会被 [PersonalPhraseImporter] 抬到
     * 候选前列，真正构成「越用越懂你」的预测闭环。
     */
    suspend fun recordUsage(text: String) {
        if (text.isBlank()) return
        // 单次上屏最多取前 500 个词块计频：超大段（粘贴整篇）只采头部，避免词表被一次粘贴撑爆
        val tokens = WordTokenizer.tokenize(text).tokens
            .asSequence().take(500)
            .map { text.substring(it.start, it.end) }
            .toList()
        if (tokens.isEmpty()) return
        writeMutex.withLock {
            val map = ensureLoaded()
            for (raw in tokens) {
                val w = raw.trim()
                if (w.length !in 2..12) continue
                if (w.all { it.isDigit() || it in PUNCT }) continue
                map.merge(w, 1) { _, v -> v + 1 }
            }
            dirty = true
            scheduleFlush()
        }
    }

    /** 默认返回全部（无上限）；UI 如确需截断可显式传 limit */
    fun topWords(limit: Int = Int.MAX_VALUE): List<Pair<String, Int>> {
        // N24：命中缓存时纯内存（旧实现每次都在调用方线程——含主线程——读全文 JSON）
        val source: Map<String, Int> = if (cacheLoaded) cache else load()
        return source.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
    }

    suspend fun clearUsage() {
        writeMutex.withLock {
            cache.clear()
            cacheLoaded = true
            dirty = true
            // 用户主动清空：立即落盘，不等合并窗口
            withContext(Dispatchers.IO) { save(emptyMap()) }
        }
    }

    /** 从本地统计中移除某条候选词（用户「忽略」时调用），返回是否确有删除 */
    suspend fun removeWord(word: String): Boolean {
        writeMutex.withLock {
            val map = ensureLoaded()
            if (!map.containsKey(word)) return false
            map.remove(word)
            dirty = true
            // 同上：用户操作立即落盘
            val snapshot = map.toMap()
            withContext(Dispatchers.IO) { save(snapshot) }
            return true
        }
    }
}
