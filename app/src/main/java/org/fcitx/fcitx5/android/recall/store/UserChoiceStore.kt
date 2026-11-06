/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 用户选择驱动的个性化优先级 —— Android 适配层（持久化 / 去抖 / 写锁 / reload / 迁移）。
 *
 * v1.0.69 架构调整：**排序决策全部委托给纯算法层 [ChoiceRanker]**。
 *   - 硬层：每键有序 Top-K 栈，冠军(最近一次主动选择)无条件写 order=1，
 *     其余历史词写 order=2..K（用户示例「爱情第1、安全第2」由此得到确定性保证）。
 *   - 软层：ln(1+n)·decay·streak·ctx − penalty 只用于次位重排 / 衰减退役 / 淘汰，
 *     永不推翻冠军。评分公式、参数、单测均在 rank/ChoiceRanker.kt（零 Android 依赖，
 *     19 个 JVM 单测覆盖核心示例/衰减/冷启动/连续/误选/清空/多键隔离等）。
 *   - 为什么不用「同拼音全候选 score 降序」：它与核心规则「>=1 次即提高、
 *     最近一次必为第 1」冲突，且本仓库 v1.0.63~66 已实测证伪；详见
 *     /workspace/candidate-priority-design.md。
 *
 * v1.0.70 覆盖扩展：硬层不再局限于「2 字词 + 2 字母键」。单次上屏为单一连续
 * 汉字段时把整段视为一个完整词（覆盖「哔哩哔哩」等 4 字应用名 / 多字 OCR 词），
 * 键放宽到 2..12 字母。这些词此前只能吃 OCR/应用名自动模块的 order=2/3，
 * 用户选多少次都排第 2；现在选一次即写 order=1 压过之。持久化层同步放宽
 * length==2 硬编码，避免重启丢状态。所有防御（隐私/敏感/粘贴/跨词切片/
 * 单字母键/写锁/失败重试/迁移/60ms+reloadNow）完整保留。
 *
 * 保留 v1.0.66~68 的全部防御性修复（不得因本次重构丢失）：
 *  1. native order 是绝对候选插入位，不是权重（order=1 才是第一候选）。
 *  2. 归属判定用「持久化精确 key=value 托管集合」，不用 order 区间（避免与
 *     PersonalPhrase 3..7 / 置顶 1 / Recall 3,9 / 应用名 2,8 互相误删）。
 *  3. flush 只对「有历史的脏键」做剔除 + 重写，其它键已学置顶原样保留。
 *  4. 落盘失败不提交 managedKeys（否则词库旧行脱离托管成孤儿），
 *     且保留 dirtyKeys 并补最多 3 次 1s 重试。
 *  5. scheduleSave 加 @Synchronized（IME 主线程与 fcitx runOnReady 线程并发）。
 *  6. 状态持久化与词库落盘解耦：无论成败都持久化 ranker 状态，进程被杀不丢学习。
 *  7. 隐私模式 / 敏感串 / 粘贴长文本 / 跨词窗口切片 / 单字母非法键过滤（算法层内建）。
 *  8. 跳过用户手动置顶条目，保证「手动置顶 > 自动学习」。
 *  9. 60ms 去抖落盘 + RecallReloader.reloadNow() 立即生效（0.1 秒级）。
 *
 * 纯离线：状态仅存本机 SharedPreferences，不联网、不写输入原文日志。
 */
package org.fcitx.fcitx5.android.recall.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.fcitx.fcitx5.android.data.password.PrivacyController
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.pinyin.customphrase.AppPhraseImporter
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PhraseKeys
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PhrasePinStore
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.fcitx.fcitx5.android.recall.RecallConfig
import org.fcitx.fcitx5.android.recall.rank.ChoiceConfig
import org.fcitx.fcitx5.android.recall.rank.ChoiceRanker
import org.fcitx.fcitx5.android.recall.rank.ChoiceRecord
import org.fcitx.fcitx5.android.recall.rank.ChoiceState
import org.fcitx.fcitx5.android.utils.appContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object UserChoiceStore {

    private const val PREFS = "mbm_choice_boost"
    private const val KEY_ENABLED = "enabled"

    /** v1.0.69：ranker 完整状态快照（栈 + 记录 + 样本 + 上文），单键 JSON。 */
    private const val KEY_STATE_V5 = "state_v5"
    /** v1.0.66~68 遗留：迁移与兼容读取用。 */
    private const val KEY_RECORDS_LEGACY = "records_v2"
    private const val KEY_WINNER_LEGACY = "winner_v4"
    private const val KEY_MANAGED = "managed_keys_v3"
    private const val KEY_MIGRATED_V3 = "migrated_v3"
    private const val KEY_MIGRATED_V4 = "migrated_v4"
    private const val KEY_MIGRATED_V5 = "migrated_v5"

    private val lock = ReentrantLock()

    /** 纯算法层：所有排序决策的唯一来源。参数由 RecallConfig 注入。 */
    private val ranker: ChoiceRanker = ChoiceRanker(
        config = ChoiceConfig(
            topK = RecallConfig.CHOICE_TOP_K,
            halfLifeMs = RecallConfig.CHOICE_DECAY_HALFLIFE_MS_V2,
            nCap = RecallConfig.CHOICE_N_CAP,
            warmupSamples = RecallConfig.CHOICE_WARMUP_SAMPLES,
            streakCap = RecallConfig.CHOICE_STREAK_CAP,
            streakW = RecallConfig.CHOICE_STREAK_W,
            ctxW = RecallConfig.CHOICE_CTX_W,
            misclickPenalty = RecallConfig.CHOICE_MISCLICK_PENALTY,
            minScore = RecallConfig.CHOICE_MIN_SCORE_V2,
            dedupMs = RecallConfig.CHOICE_DEDUP_MS,
            recordCap = RecallConfig.CHOICE_RECORD_CAP,
            maxCommitLen = RecallConfig.CHOICE_MAX_COMMIT_LEN,
            maxRuns = RecallConfig.CHOICE_MAX_RUNS,
            misclickMs = RecallConfig.CHOICE_MISCLICK_MS,
            // v1.0.70：硬层覆盖多字词（哔哩哔哩）与多字母键（blbl），详见 RecallConfig。
            phraseLenMax = RecallConfig.CHOICE_PHRASE_LEN_MAX,
            keyLen = RecallConfig.CHOICE_KEY_LEN_MIN,
            keyLenMax = RecallConfig.CHOICE_KEY_LEN_MAX,
        )
    ).also { it.importState(loadState()) }

    /** 本模块写进词库的精确 `key=value` 集合（与 order 无关）。 */
    @Volatile
    private var managedKeys: MutableSet<String> = loadManagedKeys()

    /** 脏键累积（debounce 窗口内合并多次提交，flush 一次性重写）。 */
    private val dirtyKeys = mutableSetOf<String>()

    /** 落盘失败后的有限重试计数（仅失败分支递增，成功即清零）。 */
    private var flushRetry = 0

    private val handler = Handler(Looper.getMainLooper())
    private var saveTask: Runnable? = null

    /** 60ms：配合 flush 末尾的 reloadNow()，选完抬手即生效。 */
    private const val SAVE_DEBOUNCE_MS = 60L

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * v1.0.71：供其它自动写入模块在「剔除自己写过的旧条目」时排除本模块托管条目。
     *
     * 背景：v1.0.69 起硬层写 order=1..CHOICE_TOP_K（1/2/3），而其它模块靠 order 区间
     * 判定归属（应用名 {2,8}、Recall {3,9}、个人短语 3..7）——用户选择的次位条目
     * order=2/3 会落进它们的 isOwnEntry 区间，被当成「自己的旧条目」从词库剔除，
     * 导致用户要求的「爱情第 1、安全第 2」次位排序随机丢失。v1.0.70 只护住了 order=1
     * （这些区间都不含 1）。这里给出精确 key=value 归属，三模块剔除时让位即可。
     *
     * 只读 @Volatile 快照：不取 [lock]、不做 IO，避免与本模块 flush 的
     * lock → CustomPhraseManager.writeLock 顺序形成死锁。[managedKeys] 在 flush 中是
     * 整体替换（先构造完 newManaged 再赋值，赋值后不再原地修改），快照读取安全。
     */
    fun isOwnChoiceEntry(key: String, value: String): Boolean {
        val snapshot = managedKeys
        return "$key=$value" in snapshot
    }

    /**
     * v1.0.72：供上下文预测模块让位判定用。
     *
     * 只读 [managedKeys] 的 @Volatile 快照，不触碰 [ranker]、不做 IO，因此可以从
     * IO 线程安全调用，且与本模块 flush 的 lock -> writeLock 顺序无死锁风险。
     * 托管集合的语义正是「本模块当前写进词库的用户硬层条目」，用它判断
     * 「该键是否已有用户冠军」比遍历 ranker 栈更准确（词库是最终事实）。
     */
    fun hasHistory(key: String): Boolean {
        val snapshot = managedKeys
        return snapshot.any { it.startsWith("$key=") }
    }

    /** 该词是否已被用户主动选过（跨键让位，避免全拼与缩写下排序割裂）。 */
    fun hasUserWord(word: String): Boolean {
        val snapshot = managedKeys
        return snapshot.any { it.substringAfter('=', "") == word }
    }

    // ---------- 开关（同步读，IME 主线程可直接用；默认开启）----------

    fun isEnabled(): Boolean = prefs().getBoolean(KEY_ENABLED, true)

    fun setEnabled(v: Boolean) = prefs().edit().putBoolean(KEY_ENABLED, v).apply()

    // ---------- 捕获入口 ----------

    /**
     * 记录一次上屏文本中的中文候选选择。幂等、线程安全；由 SAVE_DEBOUNCE_MS 合并落盘。
     * 过滤（隐私/敏感/粘贴/非 2 字词/非法键）与评分排序全部在 [ranker] 内完成。
     */
    fun recordCommit(text: String) {
        if (!isEnabled()) return
        val dirty = ranker.onCommit(
            text = text,
            resolveKeys = { w -> AppPhraseImporter.keysForName(w) },
            isSensitive = { t -> RecallConfig.SENSITIVE_PATTERNS.any { it.containsMatchIn(t) } },
            // v1.0.71：纵深防御此前是假的 —— 原实现硬编码 false，注释声称「再传一次」
            // 却从未生效。commitText 调用点已有第一道 PrivacyController 过滤，这里补齐
            // 第二道，保证 recordCommit 作为 public API 被其它路径直接调用时同样不学习。
            privacyMode = PrivacyController.inPrivacyMode,
        )
        if (dirty.isEmpty()) return
        lock.withLock { dirtyKeys.addAll(dirty) }
        scheduleSave()
    }

    /**
     * 显式误选上报：IME 侧检测到「刚上屏的词被立即删除」时调用。
     * v1.0.69 默认不由「同键短时改选」自动推断，避免误伤正常连续选词（见设计文档 2.3）。
     */
    fun reportMisclick(key: String, word: String) {
        if (!isEnabled()) return
        ranker.onMisclick(key, word)
        lock.withLock { dirtyKeys.add(key) }
        scheduleSave()
    }

    // ---------- 落盘（debounce 合并）----------

    /**
     * v1.0.68：@Synchronized。本方法会从 IME 主线程（recordCommit）与 fcitx
     * runOnReady 回调线程（migrateLegacy）并发调用，saveTask 的「读-取消-赋值-post」
     * 必须原子，否则两个线程各自的 removeCallbacks 会互相取消导致某次 flush 丢失。
     * 此处只操作主线程 Handler，不持 [lock]、不调用 [flush]，无死锁风险。
     */
    @Synchronized
    private fun scheduleSave() {
        saveTask?.let { handler.removeCallbacks(it) }
        saveTask = Runnable { flush() }
        handler.postDelayed(saveTask!!, SAVE_DEBOUNCE_MS)
    }

    /**
     * 把脏键的 [ranker] 有序栈按 order=1..K 写入 fcitx5 自定义词组。
     *
     * 保留 v1.0.67/68 的关键修复：只对「有历史的脏键」做精确剔除 + 重写，
     * 其它键的托管关系原样保留；落盘成功才提交 managedKeys；失败保留 dirtyKeys 并重试。
     */
    private fun flush() {
        var saved = false
        lock.withLock {
            if (dirtyKeys.isEmpty()) return
            val keys = dirtyKeys.toSet()
            // 只对「确实有历史」的键做剔除 + 重写（v1.0.68 修复 5：
            // 无 winner 的脏键不能删旧行却不写回，否则无声删掉已学置顶）
            val effectiveKeys = keys.filter { ranker.hasHistory(it) }.toSet()

            val fresh = mutableListOf<PinyinCustomPhrase>()
            // v1.0.72：托管/剔除范围必须包含「本模块实际写入的全部键」。
            // 每个硬层条目除原有首字母键外还会挂一个全拼键（见下方 PhraseKeys），
            // 若 ownedKeys 仍只有 effectiveKeys，全拼键旧行将脱离托管、永远不被清理，
            // 用户改选后词库里会残留过期的全拼冠军行。
            val ownedKeys = effectiveKeys.toMutableSet()
            val isPinned: (String, String) -> Boolean = { k, w ->
                PhrasePinStore.isPinned(appContext, PinyinCustomPhrase(k, 1, w))
            }
            for (k in effectiveKeys) {
                for (e in ranker.buildOrder(k, isPinned)) {
                    // 双保险：手动置顶的 key=value 不由本模块自动改写
                    if (PhrasePinStore.isPinned(appContext, PinyinCustomPhrase(e.key, e.order, e.word))) continue
                    // v1.0.72 全拼键覆盖：native 用 pyBeforeCursor 精确匹配用户实际
                    // 敲入的拼音串，此前只写首字母键(aq)，全拼用户(anquan)永远吃不到
                    // 个性化排序。同一词条按相同 order 额外挂全拼键，首字母键一行不动。
                    val allKeys = (setOf(e.key) + PhraseKeys.forWord(e.word)).distinct()
                    for (pk in allKeys) {
                        if (PhrasePinStore.isPinned(appContext, PinyinCustomPhrase(pk, e.order, e.word))) continue
                        fresh.add(PinyinCustomPhrase(pk, e.order, e.word))
                        ownedKeys += pk
                    }
                }
            }
            // 托管集合在循环**之后**全量重建：先按 ownedKeys 剔除本模块所有旧行
            // （含新增的全拼键行），再加入本次 fresh，避免旧全拼行成为孤儿。
            val newManaged = managedKeys
                .filterNot { mk -> ownedKeys.any { k -> mk.startsWith("$k=") } }
                .toMutableSet()
                .apply { fresh.forEach { add("${it.key}=${it.value}") } }

            var ok = false
            CustomPhraseManager.withWriteLock {
                val existing = runCatching { CustomPhraseManager.loadCached() }.getOrNull()
                    ?: emptyArray()
                // 精确剔除「有效键名下」本模块写过的行（不看 order），保留应用名/
                // 用户置顶/Recall/个人短语等其它来源条目，以及非有效键的既有置顶。
                val kept = existing.filterNot { p ->
                    entryKeyOf(p) in managedKeys &&
                        p.key in ownedKeys &&
                        !PhrasePinStore.isPinned(appContext, p)
                }
                val merged = (kept + fresh)
                    .groupBy { "${it.key}=${it.value}" }
                    .map { g -> g.value.minByOrNull { it.order }!! }
                    .toTypedArray()
                ok = runCatching { CustomPhraseManager.saveCached(merged) }
                    .onFailure { Timber.w(it, "MBM Choice: save custom phrases failed") }
                    .isSuccess
            }

            if (ok) {
                dirtyKeys.clear()
                managedKeys = newManaged
                persistManagedKeys()
                flushRetry = 0
            } else {
                Timber.w("MBM Choice: flush failed, keep %d dirty keys for next flush", keys.size)
                if (flushRetry < 3) {
                    flushRetry++
                    handler.postDelayed({ flush() }, 1000L)
                } else {
                    flushRetry = 0
                }
            }
            // 状态存于独立 SP，与词库落盘成败解耦：无论成败都持久化，
            // 避免进程被杀丢失用户学习；下次 flush 会补写词库。
            persistState()
            saved = ok
        }
        // 落盘成功才立即重载（绕过 250ms 最小间隔），保证「选完抬手，下次输入即第一」。
        if (saved) RecallReloader.reloadNow()
    }

    private fun entryKeyOf(p: PinyinCustomPhrase): String = "${p.key}=${p.value}"

    private fun loadManagedKeys(): MutableSet<String> =
        prefs().getStringSet(KEY_MANAGED, emptySet()).orEmpty().toMutableSet()

    private fun persistManagedKeys() {
        prefs().edit().putStringSet(KEY_MANAGED, managedKeys.toSet()).apply()
    }

    // ---------- 状态持久化（SP / JSON，仅本机；防御性逐条容错）----------

    /** v1.0.68 教训：任一脏数据异常不能吞掉**整份**状态，逐条 optXxx + 跳过坏条目。 */
    private fun loadState(): ChoiceState {
        val json = prefs().getString(KEY_STATE_V5, null) ?: return ChoiceState(emptyMap(), emptyMap(), emptyMap(), "")
        return runCatching {
            val obj = JSONObject(json)
            val stacks = mutableMapOf<String, List<String>>()
            obj.optJSONObject("stacks")?.let { s ->
                s.keys().forEach { k ->
                    val arr = s.optJSONArray(k) ?: return@forEach
                    val words = mutableListOf<String>()
                    for (i in 0 until arr.length()) {
                        // v1.0.70：不再硬性只留 2 字词，否则「哔哩哔哩」等整词重启即丢
                        arr.optString(i, "")
                            .takeIf { it.length in 2..RecallConfig.CHOICE_PHRASE_LEN_MAX }
                            ?.let(words::add)
                    }
                    if (k.length in RecallConfig.CHOICE_KEY_LEN_MIN..RecallConfig.CHOICE_KEY_LEN_MAX &&
                        k.all { it in 'a'..'z' } && words.isNotEmpty()
                    ) {
                        stacks[k] = words.distinct().take(RecallConfig.CHOICE_TOP_K)
                    }
                }
            }
            val records = mutableMapOf<String, ChoiceRecord>()
            obj.optJSONObject("records")?.let { r ->
                r.keys().forEach { id ->
                    // v1.0.71：与 stacks/samples 采用同一套 id 校验。原实现只用
                    // substringAfter("=>") 查值长度，对不含 "=>" 的脏 id 会返回整个 id
                    // （校验形同虚设），且完全不校验 key 段，畸形 / v1.0.63 长键拼接
                    // 垃圾会被载入并长期占用 recordCap。逐条容错不变。
                    val sep = id.indexOf("=>")
                    if (sep < 0) return@forEach
                    val rk = id.substring(0, sep)
                    val rw = id.substring(sep + 2)
                    if (rk.length !in RecallConfig.CHOICE_KEY_LEN_MIN..RecallConfig.CHOICE_KEY_LEN_MAX ||
                        !rk.all { it in 'a'..'z' }
                    ) return@forEach
                    if (rw.length !in 2..RecallConfig.CHOICE_PHRASE_LEN_MAX) return@forEach
                    val v = r.optJSONObject(id) ?: return@forEach
                    records[id] = ChoiceRecord(
                        n = v.optInt("n", 1).coerceAtLeast(0),
                        lastAt = v.optLong("t", 0L),
                        prevWord = v.optString("p", ""),
                        streak = v.optInt("s", 0).coerceAtLeast(0),
                        penalty = v.optDouble("k", 0.0).coerceAtLeast(0.0),
                    )
                }
            }
            val samples = mutableMapOf<String, Int>()
            obj.optJSONObject("samples")?.let { m ->
                m.keys().forEach { k ->
                    if (k.length in RecallConfig.CHOICE_KEY_LEN_MIN..RecallConfig.CHOICE_KEY_LEN_MAX &&
                        k.all { it in 'a'..'z' }
                    ) {
                        samples[k] = m.optInt(k, 0).coerceAtLeast(0)
                    }
                }
            }
            ChoiceState(stacks, records, samples, obj.optString("lastWord", ""))
        }.getOrDefault(ChoiceState(emptyMap(), emptyMap(), emptyMap(), ""))
    }

    private fun persistState() {
        val st = ranker.exportState()
        runCatching {
            val obj = JSONObject()
            val stacks = JSONObject()
            for ((k, words) in st.stacks) {
                stacks.put(k, JSONArray().apply { words.forEach { put(it) } })
            }
            obj.put("stacks", stacks)
            val records = JSONObject()
            for ((id, r) in st.records) {
                records.put(id, JSONObject().apply {
                    put("n", r.n); put("t", r.lastAt); put("p", r.prevWord)
                    put("s", r.streak); put("k", r.penalty)
                })
            }
            obj.put("records", records)
            val samples = JSONObject()
            for ((k, n) in st.samples) samples.put(k, n)
            obj.put("samples", samples)
            obj.put("lastWord", st.lastWord)
            prefs().edit().putString(KEY_STATE_V5, obj.toString()).apply()
        }.onFailure { Timber.w(it, "MBM Choice: persist state failed") }
    }

    // ---------- 迁移 ----------

    /**
     * 一次性遗留清理，幂等、失败不抛：
     *  - v3（v1.0.66）：清 v1.0.63~65 以旧档位 {4,6,7} 写入的词库残留；
     *  - v4（v1.0.67）：旧标记，v5 会统一重建状态，这里只置位避免重复执行旧逻辑；
     *  - v5（v1.0.69）：从 winner_v4 / records_v2 重建 ChoiceRanker 的栈与统计，
     *    并把全部键标脏触发一次 flush —— 升级后历史记忆立即按新分层模型生效，
     *    用户无需重新输入若干遍。
     */
    fun migrateLegacy() {
        val sp = prefs()
        if (!sp.getBoolean(KEY_MIGRATED_V3, false)) {
            runCatching {
                CustomPhraseManager.withWriteLock {
                    val existing = runCatching { CustomPhraseManager.loadCached() }.getOrNull()
                        ?: emptyArray()
                    val kept = existing.filterNot {
                        it.order in RecallConfig.CHOICE_LEGACY_ORDER_TIERS &&
                            it.value.length == 2 &&
                            it.key.length == 2 && it.key.all { c -> c in 'a'..'z' } &&
                            !PhrasePinStore.isPinned(appContext, it)
                    }
                    runCatching { CustomPhraseManager.saveCached(kept.toTypedArray()) }
                }
            }.onFailure { Timber.w(it, "MBM Choice: migrateLegacy v3 failed") }
            sp.edit().putBoolean(KEY_MIGRATED_V3, true).apply()
        }
        if (!sp.getBoolean(KEY_MIGRATED_V5, false)) {
            runCatching {
                val legacy = loadLegacyState(sp)
                lock.withLock {
                    if (legacy.stacks.isNotEmpty()) ranker.importState(legacy)
                    persistState()
                    dirtyKeys.addAll(ranker.exportState().stacks.keys)
                }
                scheduleSave()
            }.onFailure { Timber.w(it, "MBM Choice: migrateLegacy v5 failed") }
            sp.edit().putBoolean(KEY_MIGRATED_V5, true).apply()
        }
        // v4 旧逻辑不再需要（由 v5 统一重建），置位保证幂等
        if (!sp.getBoolean(KEY_MIGRATED_V4, false)) {
            sp.edit().putBoolean(KEY_MIGRATED_V4, true).apply()
        }
    }

    /**
     * 从 v1.0.67/68 的 winner_v4 + records_v2 重建 ChoiceState。
     *
     * 此处保留 length==2 过滤是**有意的**：这些历史数据本身就只含 2 字词 / 2 字母键
     *（旧版硬层只认这两类），既精确还原，也不会把 v1.0.63 遗留的长键拼接垃圾捞回来。
     * v1.0.70 新学的多字词/长键存于 state_v5，由 [loadState] 按新范围读取，不经过本函数。
     */
    private fun loadLegacyState(sp: android.content.SharedPreferences): ChoiceState {
        val winner = mutableMapOf<String, String>()
        sp.getString(KEY_WINNER_LEGACY, null)?.let { json ->
            runCatching {
                val o = JSONObject(json)
                o.keys().forEach { k ->
                    val w = o.optString(k, "")
                    if (k.length == 2 && k.all { it in 'a'..'z' } && w.length == 2) winner[k] = w
                }
            }
        }
        val records = mutableMapOf<String, ChoiceRecord>()
        sp.getString(KEY_RECORDS_LEGACY, null)?.let { json ->
            runCatching {
                val o = JSONObject(json)
                o.keys().forEach { id ->
                    if (id.substringAfter("=>").length > 2) return@forEach
                    val v = o.optJSONObject(id) ?: return@forEach
                    records[id] = ChoiceRecord(
                        n = v.optInt("c", 1).coerceAtLeast(1),
                        lastAt = v.optLong("t", 0L),
                        prevWord = v.optString("p", ""),
                    )
                }
            }
        }
        // 每键：冠军 = winner_v4 的词；其余历史词按最近上屏时间补足次位（最多 K-1 个）
        val stacks = mutableMapOf<String, List<String>>()
        val keys = (winner.keys + records.keys.map { it.substringBefore("=>") }).toSet()
        for (k in keys) {
            if (k.length != 2 || !k.all { it in 'a'..'z' }) continue
            val champion = winner[k]
            val others = records.entries
                .filter { it.key.startsWith("$k=>") && it.key.substringAfter("=>") != champion }
                .sortedByDescending { it.value.lastAt }
                .map { it.key.substringAfter("=>") }
                .filter { it.length == 2 }
                .distinct()
            val stack = mutableListOf<String>()
            if (champion != null) stack.add(champion)
            stack.addAll(others.take((RecallConfig.CHOICE_TOP_K - 1).coerceAtLeast(0)))
            if (stack.isNotEmpty()) stacks[k] = stack
        }
        val samples = stacks.keys.associateWith { k ->
            records.entries.count { it.key.startsWith("$k=>") }.coerceAtLeast(1)
        }
        return ChoiceState(stacks, records, samples, "")
    }

    // ---------- 调试 / 设置页 ----------

    /** 已学习记录数（调试 / 验收用）。 */
    fun recordCount(): Int = ranker.recordCount()

    /** 清空全部个性化选择记忆（设置页「清空选择记忆」）。 */
    fun clearAll() {
        lock.withLock {
            CustomPhraseManager.withWriteLock {
                val existing = runCatching { CustomPhraseManager.loadCached() }.getOrNull()
                    ?: emptyArray()
                // 精确剔除本模块托管条目，且不误删用户手动置顶的条目
                val kept = existing.filterNot {
                    entryKeyOf(it) in managedKeys && !PhrasePinStore.isPinned(appContext, it)
                }
                runCatching { CustomPhraseManager.saveCached(kept.toTypedArray()) }
            }
            ranker.clearAll()
            dirtyKeys.clear()
            managedKeys = mutableSetOf()
            persistManagedKeys()
            persistState()
        }
        RecallReloader.scheduleReload()
    }
}
