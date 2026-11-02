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
import kotlin.collections.ArrayDeque
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
    private data class UsageMap(
        val counts: Map<String, Int> = emptyMap(),
        // MBM v1.0.48：词级「最近一次上屏时间」，用于按新鲜度给候选加权（当前话题的词优先）
        val recency: Map<String, Long> = emptyMap(),
        // MBM v1.0.48：二元接续（前词→后词）共现计数，支撑「上下文预测」
        val bigrams: Map<String, Map<String, Int>> = emptyMap(),
        // 上一次提交的最后一词，用于跨次提交的「X 之后 Y」接续学习
        val prevToken: String? = null,
        // MBM v1.0.50：字符级 3/4 字固定短语计数（独立于 ICU 分词，捕捉「不忘初心」、
        // 人名、地址、签名等整短语，弥补单字频/bi-gram 只记相邻词对、丢整短语的盲区）
        val phrases: Map<String, Int> = emptyMap(),
        // MBM v1.0.51：三连词（前二词→第三词）共现计数，键为「前词\u0000后词」，支撑三词整短语联想
        val trigrams: Map<String, Map<String, Int>> = emptyMap()
    )

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

    /** v1.0.48：词级最近上屏时间戳（与 [cache] 同源，随 [ensureLoaded] 一并加载）。 */
    private val recencyCache = ConcurrentHashMap<String, Long>()

    /** v1.0.48：二元接续「前词→{后词→计数}」，支撑上下文预测。 */
    private val bigramCache = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    /** v1.0.51：三连词「前二词→{第三词→计数}」，键为「前词\u0000后词」，支撑三词整短语联想。 */
    private val trigramCache = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    /** 三连词键分隔符（不会出现在中文词内），用于把 (前词, 后词) 拼成一个稳定键。 */
    private fun trigramKey(a: String, b: String) = "$a\u0000$b"

    /** v1.0.50：字符级 3/4 字固定短语计数（独立于 ICU 分词），支撑整短语预测。 */
    private val ngramCache = ConcurrentHashMap<String, Int>()

    /** v1.0.48：上一次提交的最后一词（内存态，进程重启后从首词重新累计，无伤大雅）。 */
    @Volatile
    private var prevToken: String? = null

    /** v1.0.48：最近提交词栈（最多保留 2 个，构成「上下文」供预测后继词）。 */
    private val recentDeque = ArrayDeque<String>()

    /** v1.0.51：最近两词窗口（跨次提交链三连词用；纯内存、不落盘，重启即清空，计数已落盘无损）。 */
    private val recentTwoDeque = ArrayDeque<String>()

    /** 最近上下文快照（读路径无锁读取，写路径在 [writeMutex] 内更新）。 */
    @Volatile
    private var recentContextSnapshot: List<String> = emptyList()

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

    private fun load(): UsageMap = runCatching {
        if (!storeFile.exists()) return UsageMap()
        val key = cipherKey()
        val text = (key?.let { StreamingFileCipher.openDecrypted(storeFile, it) }
            ?: storeFile.inputStream()).use { it.readBytes().toString(Charsets.UTF_8) }
        json.decodeFromString<UsageMap>(text)
    }.getOrElse { e ->
        // 解析失败（损坏/半写文件）：保留现场备份，绝不把损坏文件当空表直接覆写
        if (storeFile.length() > 0) {
            val backup = File(storeFile.parentFile, "${storeFile.name}.corrupt")
            runCatching { storeFile.copyTo(backup, overwrite = true) }
                .onFailure { Timber.w(it, "backup corrupt usage file failed") }
            Timber.w(e, "smart dict usage file unreadable, backed up to ${backup.name}")
        }
        UsageMap()
    }

    /**
     * v1.0.48：落盘全量快照（词频 + 新鲜度 + 二元接续 + 前词）。
     * 仅保留仍在词频表内的词，避免 recency/bigrams 随使用无界膨胀。
     */
    private fun save() = runCatching {
        val key = cipherKey()
        if (key == null) {
            Timber.w("smart dict usage 未落盘：加密密钥不可用（绝不退回明文写入）")
            return@runCatching
        }
        val rec = recencyCache.filterKeys { cache.containsKey(it) }
        val bg = bigramCache.filterKeys { cache.containsKey(it) }
            .mapValues { (_, m) -> m.filterKeys { k -> cache.containsKey(k) } }
        val ph = ngramCache.filterKeys { it.length in 3..4 }
        val tri = trigramCache.filterKeys { k ->
            val (a, b) = k.split('\u0000', limit = 2)
            cache.containsKey(a) && cache.containsKey(b)
        }.mapValues { (_, m) -> m.filterKeys { cache.containsKey(it) } }
        val payload = json.encodeToString(
            UsageMap(cache.toMap(), rec, bg, prevToken, ph, tri)
        ).toByteArray(Charsets.UTF_8)
        // 先写临时文件再原子重命名：避免写一半崩溃/被杀留下半文件，
        // 也避免与并发读者交错（rename 在同一文件系统上是原子的）
        val tmp = File(storeFile.parentFile, "${storeFile.name}.tmp")
        writeEncrypted(tmp, payload, key)
        if (!tmp.renameTo(storeFile)) {
            writeEncrypted(storeFile, payload, key) // 退化为直接写（仍优于不写）
            tmp.delete()
        }
    }.onFailure { Timber.w(it, "save smart dict usage failed") }

    /** 首次访问时把文件读进内存；之后所有读写都走各缓存。 */
    private suspend fun ensureLoaded(): ConcurrentHashMap<String, Int> {
        if (!cacheLoaded) {
            val loaded = withContext(Dispatchers.IO) { load() }
            cache.clear(); cache.putAll(loaded.counts)
            recencyCache.clear(); recencyCache.putAll(loaded.recency)
            bigramCache.clear()
            loaded.bigrams.forEach { (k, v) -> bigramCache[k] = ConcurrentHashMap(v.toMap()) }
            ngramCache.clear()
            loaded.phrases.forEach { (k, v) -> ngramCache[k] = v }
            trigramCache.clear()
            loaded.trigrams.forEach { (k, v) -> trigramCache[k] = ConcurrentHashMap(v.toMap()) }
            prevToken = loaded.prevToken
            cacheLoaded = true
        }
        return cache
    }

    /** 把内存快照落盘一次（调用方须持有 [writeMutex]）。 */
    private suspend fun flushLocked() {
        if (!cacheLoaded) return
        withContext(Dispatchers.IO) { save() }
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
     *
     * MBM v1.0.48：在逐词计频基础上新增三件事——
     *  - **新鲜度**：记录每个词最近一次上屏时间，预测时按新鲜度加权（当前话题的词优先）；
     *  - **二元接续**：记录「前词→后词」共现，支撑「刚打完 X 就预测 Y」的上下文联想；
     *  - **最近上下文**：保留最近提交词栈，供 [PersonalPhraseImporter] 把后继词抬到候选前列。
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
            val now = System.currentTimeMillis()
            // 过滤出「有效词」：2..12 字、且非纯数字/标点串（与旧口径一致）
            val words = tokens.filter { w ->
                w.length in 2..12 && !(w.all { c -> c.isDigit() || c in PUNCT })
            }
            if (words.isEmpty()) return@withLock
            for (w in words) {
                map.merge(w, 1) { _, v -> v + 1 }
                recencyCache[w] = now
            }
            // 仅对多字词（2..8 字）建立接续，避免单字噪声污染「前词→后词」
            val seq = words.filter { it.length in 2..8 }
            if (seq.isNotEmpty()) {
                // 跨次提交接续：上一次提交的最后一词 → 本次首个词
                val prev = prevToken
                if (prev != null && map.containsKey(prev) && seq[0].length in 2..8) {
                    bumpBigram(prev, seq[0])
                }
                for (i in 0 until seq.size - 1) {
                    bumpBigram(seq[i], seq[i + 1])
                }
                // 跨次提交三连词：最近两词 → 本次首词（捕捉分三次提交的中文词链，如 中国/人民/共和国）
                if (recentTwoDeque.size == 2 && seq[0].length in 2..8) {
                    val a = recentTwoDeque[0]; val b = recentTwoDeque[1]
                    if (map.containsKey(a) && map.containsKey(b)) bumpTrigram(a, b, seq[0])
                }
                for (i in 0 until seq.size - 2) {
                    bumpTrigram(seq[i], seq[i + 1], seq[i + 2])
                }
                prevToken = seq.last()
                // 维护最近上下文栈（最多 2 个），供上下文预测取「前词」
                recentDeque.addLast(seq.last())
                while (recentDeque.size > 2) recentDeque.removeFirst()
                recentContextSnapshot = recentDeque.toList()
                // 维护最近两词窗口供三连词跨次学习
                for (t in seq) {
                    recentTwoDeque.addLast(t)
                    while (recentTwoDeque.size > 2) recentTwoDeque.removeFirst()
                }
            }
            // v1.0.50：字符级 3/4 字固定短语挖掘（独立于 ICU 分词，捕捉整短语）
            bumpPhrases(text.take(2000))
            dirty = true
            scheduleFlush()
        }
    }

    /** v1.0.50：连续汉字串内的 3/4 字固定短语计数。 */
    private val CJK_RUN = Regex("[一-鿿]+")

    private fun bumpPhrases(src: String) {
        for (run in CJK_RUN.findAll(src)) {
            val s = run.value
            if (s.length < 3) continue
            val maxLen = minOf(s.length, 4)
            for (len in 3..maxLen) {
                for (i in 0..s.length - len) {
                    val sub = s.substring(i, i + len)
                    if (sub.length != len) continue
                    if (ngramCache.size > 150_000 && !ngramCache.containsKey(sub)) continue
                    ngramCache.merge(sub, 1) { _, v -> v + 1 }
                }
            }
        }
    }

    /** v1.0.48：二元接续计数（内存态，进程内高效累加），带软上限防极端膨胀。 */
    private fun bumpBigram(a: String, b: String) {
        if (a == b) return
        if (bigramCache.size > 100_000 && !bigramCache.containsKey(a)) return
        val inner = bigramCache.getOrPut(a) { ConcurrentHashMap() }
        if (inner.size > 20_000) return
        inner.merge(b, 1) { _, v -> v + 1 }
    }

    /** v1.0.51：三连词计数（内存态），带软上限防极端膨胀。 */
    private fun bumpTrigram(a: String, b: String, c: String) {
        if (a == b || b == c || a == c) return
        val key = trigramKey(a, b)
        if (trigramCache.size > 100_000 && !trigramCache.containsKey(key)) return
        val inner = trigramCache.getOrPut(key) { ConcurrentHashMap() }
        if (inner.size > 20_000) return
        inner.merge(c, 1) { _, v -> v + 1 }
    }

    /** v1.0.48：给定前词，返回按共现频次排序的后继词（上下文预测核心）。 */
    fun predictNext(prev: String, limit: Int = 5): List<String> {
        val m: Map<String, Int>? = if (cacheLoaded) {
            bigramCache[prev]?.toMap()
        } else {
            load().bigrams[prev]
        }
        return m?.entries
            ?.sortedByDescending { it.value }
            ?.take(limit)
            ?.map { it.key } ?: emptyList()
    }

    /**
     * v1.0.49：短语接续预测的数据底座。
     * 给定前词，返回其高置信度后继词及共现计数（计数 ≥ [minCount]，按计数降序、最多 [limit] 个）。
     * 调用方把「前词+后继词」拼成整词挂到**前词的拼音**下，即可实现「敲前词拼音直接给出整短语」
     * 的短语级预测（区别于 v1.0.48 把后继词挂到自身拼音的弱联想）。
     */
    fun predictContinuationCounts(prev: String, limit: Int = 3, minCount: Int = 2): List<Pair<String, Int>> {
        val m: Map<String, Int>? = if (cacheLoaded) {
            bigramCache[prev]?.toMap()
        } else {
            load().bigrams[prev]
        }
        return m?.entries
            ?.filter { it.value >= minCount }
            ?.sortedByDescending { it.value }
            ?.take(limit)
            ?.map { it.key to it.value } ?: emptyList()
    }

    /**
     * v1.0.51：三连词联想的数据底座。
     * 给定前二词，返回其高置信度第三词及共现计数（计数 ≥ [minCount]，按计数降序、最多 [limit] 个）。
     * 调用方把「前词+后词+第三词」拼成三词整短语挂到**前词**的拼音下，即可实现「敲前词拼音直接给出
     * 三词整短语」（如敲「zhongguo」出「中华人民共和国」），把 v1.0.49 的二词接续扩展到三词链。
     */
    fun predictTriContinuation(first: String, second: String, limit: Int = 1, minCount: Int = 2): List<Pair<String, Int>> {
        val m: Map<String, Int>? = if (cacheLoaded) {
            trigramCache[trigramKey(first, second)]?.toMap()
        } else {
            load().trigrams[trigramKey(first, second)]
        }
        return m?.entries
            ?.filter { it.value >= minCount }
            ?.sortedByDescending { it.value }
            ?.take(limit)
            ?.map { it.key to it.value } ?: emptyList()
    }

    /**
     * v1.0.50：高频固定短语候选（个人短语预测核心）。
     * 返回反复出现（计数 ≥ 2）的 3/4 字短语，按「计数降序、同计数新鲜度降序」排序、最多 [limit] 个。
     * 这些短语独立于 ICU 分词，捕捉「不忘初心」、人名、地址、签名等整短语。
     */
    fun phraseCandidates(limit: Int = 200): List<Pair<String, Int>> {
        val src: Map<String, Int> = if (cacheLoaded) ngramCache else load().phrases
        return src.entries
            .filter { it.key.length in 3..4 && it.value >= 2 }
            .map { it.key to it.value }
            .sortedWith(
                compareByDescending<Pair<String, Int>> { it.second }
                    .thenByDescending { wordRecency(it.first) ?: 0L }
            )
            .take(limit)
    }

    /** v1.0.48：最近提交词栈（最多 [limit] 个，最新者排在末位）。 */
    fun recentContextWords(limit: Int = 2): List<String> = recentContextSnapshot.takeLast(limit)

    /** v1.0.48：某词最近上屏时间戳（毫秒），未记录返回 null。 */
    fun wordRecency(word: String): Long? =
        if (cacheLoaded) recencyCache[word] else load().recency[word]

    /** 标记本地统计有新增（供预测闭环周期性同步判定）。 */
    fun markDirty() {
        dirty = true
    }

    /** 默认返回全部（无上限）；UI 如确需截断可显式传 limit */
    fun topWords(limit: Int = Int.MAX_VALUE): List<Pair<String, Int>> {
        // N24：命中缓存时纯内存（旧实现每次都在调用方线程——含主线程——读全文 JSON）
        val source: Map<String, Int> = if (cacheLoaded) cache else load().counts
        return source.entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
    }

    suspend fun clearUsage() {
        writeMutex.withLock {
            cache.clear()
            recencyCache.clear()
            bigramCache.clear()
            ngramCache.clear()
            trigramCache.clear()
            prevToken = null
            recentDeque.clear()
            recentTwoDeque.clear()
            recentContextSnapshot = emptyList()
            cacheLoaded = true
            dirty = true
            // 用户主动清空：立即落盘，不等合并窗口
            withContext(Dispatchers.IO) { save() }
        }
    }

    /** 从本地统计中移除某条候选词（用户「忽略」时调用），返回是否确有删除 */
    suspend fun removeWord(word: String): Boolean {
        writeMutex.withLock {
            val map = ensureLoaded()
            if (!map.containsKey(word)) return false
            map.remove(word)
            recencyCache.remove(word)
            bigramCache.remove(word)
            ngramCache.remove(word)
            // 一并清掉其它词对它的后继计数
            bigramCache.forEach { it.value.remove(word) }
            // 清掉以该词为前两元的 trigram、以及该词作为第三元的计数
            val triIt = trigramCache.iterator()
            while (triIt.hasNext()) {
                val (k, m) = triIt.next()
                val (a, b) = k.split('\u0000', limit = 2)
                if (a == word || b == word) triIt.remove() else m.remove(word)
            }
            dirty = true
            // 同上：用户操作立即落盘
            withContext(Dispatchers.IO) { save() }
            return true
        }
    }
}
