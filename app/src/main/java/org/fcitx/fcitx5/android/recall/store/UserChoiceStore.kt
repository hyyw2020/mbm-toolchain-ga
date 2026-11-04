/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 用户选择驱动的个性化优先级（预判 / 联想增强层）。
 *
 * 捕获点：FcitxInputMethodService.commitText（用户上屏中文 = 手动输入 + 主动选择的
 * 最强信号）。对每个上屏中文词，复用 AppPhraseImporter.keysForName 反推拼音首字母键。
 *
 * v1.0.67 语义：**last-commit-wins，一次选择即置顶**。
 *   - 每个键只记「用户最近一次主动选择上屏的词」(winner)；下次输入该码，winner 无条件
 *     以 order=1（第一候选）写入 fcitx 自定义词组。选一次就生效，不需要「输好几遍」。
 *   - 废除 v1.0.63~66 的「按 score=ln(1+count)×decay×ctx 排序取 Top-1」：同键下反复
 *     被默认上屏的高频词（如 aq 的「爱情」）count 一直领先，用户主动选一次的「安全」
 *     score 远低于它，永远抢不到 order=1 —— 这正是用户反馈「选了好几遍才提高」的根因。
 *     分数体系现仅用于历史统计与容量护栏，不再决定置顶归属。
 *   - 新选择直接覆盖旧 winner：flush 会精确移除该键名下本模块写过的旧行，再写新词。
 *     原第一候选（爱情）不再被本模块写 order=1，交还 libime 按词频顺延（约第 2）。
 *
 * v1.0.66 关键修正：native 自定义词组的 order 是「候选位」而非「权重」
 * （pinyin.cpp: CandidateOrder{order-1,...}；order<=0 被跳过；normalizeData 按 order 升序）。
 * 旧档位 [4,6,7] 把选中的词钉死在第 4 位以后，无论选多少次都压不过原生第一候选。
 *
 * 归属判定（v1.0.66 重做）：不再靠 order 区间（会与 PersonalPhrase 的 3..7、置顶的 1 冲突互删），
 * 改为持久化「精确 key=value 托管集合」；flush 只删自己写过的行，并跳过用户手动置顶条目
 *（保证「手动置顶 > 自动学习」），绝不触碰其它模块条目。
 *
 * 纯离线：数据仅存本机 SharedPreferences，不联网、不写输入原文日志（仅可记「本次记录 N 词」计数，
 * 不含任何候选词明文），隐私模型与 Trime「全部输入逻辑本地执行、断网可完整工作」一致。
 */
package org.fcitx.fcitx5.android.recall.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.pinyin.customphrase.AppPhraseImporter
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PhrasePinStore
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.fcitx.fcitx5.android.recall.RecallConfig
import org.fcitx.fcitx5.android.utils.appContext
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.exp
import kotlin.math.ln

object UserChoiceStore {

    private const val PREFS = "mbm_choice_boost"
    private const val KEY_RECORDS = "records_v2"
    private const val KEY_ENABLED = "enabled"
    /**
     * v1.0.66：本模块写过的精确条目 `key=value` 集合（持久化到 SP）。
     * 旧版用 order 区间 {4,6,7} 判定归属，但 order 现改为 1（与手动置顶同值），
     * 且 PersonalPhraseImporter 用 3..7 —— 靠区间识别会互相误删。改为精确 key=value 托管，
     * 只删自己写过的行，绝不碰用户手动置顶 / 其它模块条目。
     */
    private const val KEY_MANAGED = "managed_keys_v3"
    /** v1.0.66 遗留清理标记：清掉 v1.0.63~65 以旧档位 4/6/7 写入的 boost 残留。 */
    private const val KEY_MIGRATED_V3 = "migrated_v3"

    /** v1.0.67：每个键「最近一次主动选择上屏的词」(key -> word)，一次选择即置顶。 */
    private const val KEY_WINNER = "winner_v4"
    /** v1.0.67 迁移标记：从历史 records 按最近上屏重建 winner，升级后历史记忆立即生效。 */
    private const val KEY_MIGRATED_V4 = "migrated_v4"

    private val lock = ReentrantLock()

    /** (键=>词) -> 统计记录。内存镜像，进程重启从 SP 恢复；现仅用于历史统计与容量护栏。 */
    private val records = loadRecords().toMutableMap()

    /**
     * v1.0.67 核心：key -> 最近一次主动选择上屏的 2 字词（last-commit-wins）。
     * 2 字词首字母键只有 26×26 种，天然有界（≤676），无需衰减淘汰；一直有效直到被新选择覆盖。
     */
    private val winner: MutableMap<String, String> = loadWinner()

    /** 本模块写过的精确 `key=value` 集合（与 order 无关，v1.0.66）。 */
    @Volatile
    private var managedKeys: MutableSet<String> = loadManagedKeys()

    /** 上一条上屏中文词（用于历史 bigram 统计，score 仅用于容量护栏）。 */
    @Volatile
    private var lastWord: String = ""

    /** 脏键累积（debounce 窗口内合并多次提交的 key，flush 一次性重算）。 */
    private val dirtyKeys = mutableSetOf<String>()

    private val handler = Handler(Looper.getMainLooper())
    private var saveTask: Runnable? = null
    /**
     * v1.0.67：400ms → 60ms。用户硬要求「选择后下一次输入即第一」（0.1 秒级识别语义）；
     * 配合 flush 末尾的 RecallReloader.reloadNow() 立即重载，选完抬手即生效。
     */
    private const val SAVE_DEBOUNCE_MS = 60L

    /** 提取连续汉字「串」（≥2 字）作为原始上屏片段；真正 boost 词条由 [decomposeRun] 切成 2 字词。 */
    private val HANZI_RUN_RE = Regex("[\\u4e00-\\u9fff]{2,}")

    /**
     * 单条 boost 词条最大字数。固定 2：只提升「用户选过的两字词」，键长恒为 2（如 安全→aq），
     * 不可能因键前缀命中而冒出长拼接词；更长的上屏串切成相邻 2 字窗口（安全爱情安庆→安全/爱情/安庆）
     * 学到可复用短词，而非一个 aqaqaq 长键垃圾（v1.0.63 已知 bug）。
     */
    private const val CHOICE_BOOST_MAX_LEN = 2

    /** 把一段连续汉字切成 boost 词条：≤2 字整段保留；>2 字切相邻 2 字窗口并去重。 */
    private fun decomposeRun(run: String): List<String> {
        val n = run.length
        if (n <= CHOICE_BOOST_MAX_LEN) return listOf(run)
        return (0..n - CHOICE_BOOST_MAX_LEN).map { run.substring(it, it + CHOICE_BOOST_MAX_LEN) }.distinct()
    }

    private data class ChoiceRecord(
        val count: Int,
        val lastAt: Long,
        val prevWord: String
    )

    // ---------- 开关（同步读，IME 主线程可直接用；默认开启）----------

    fun isEnabled(): Boolean =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(v: Boolean) =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, v).apply()

    // ---------- 捕获入口 ----------

    /**
     * 记录一次上屏文本中的中文候选选择。幂等、线程安全；高频调用由 SAVE_DEBOUNCE_MS 合并落盘。
     *
     * v1.0.67：每个键直接把本次上屏的词设为 winner（last-commit-wins），不做任何分数比较。
     * 用户选一次「安全」，aq 的 winner 立刻变成安全，flush 即写 aq,1=安全 —— 下一次输入 aq 就是第一。
     */
    fun recordCommit(text: String) {
        if (!isEnabled()) return
        // 敏感串（卡号/身份证/验证码）整体跳过：不学习、不写库（隐私 E2/E3 降权原则）
        if (RecallConfig.SENSITIVE_PATTERNS.any { it.containsMatchIn(text) }) return
        // 先切出连续汉字串，再切成 2 字词（见 [decomposeRun]：杜绝长键前缀污染候选）
        val words = HANZI_RUN_RE.findAll(text).flatMap { decomposeRun(it.value) }.toList()
        if (words.isEmpty()) return
        val ctxWord = lastWord
        val now = System.currentTimeMillis()
        val affected = mutableSetOf<String>()
        lock.withLock {
            for (w in words) {
                val keys = AppPhraseImporter.keysForName(w)
                for (k in keys) {
                    // 只接受 2 字纯字母首字母键：杜绝单字母键 / 长键把垃圾带进 winner 表
                    if (k.length != CHOICE_BOOST_MAX_LEN || !k.all { it in 'a'..'z' }) continue
                    val id = "$k=>$w"
                    val prev = records[id]
                    records[id] = ChoiceRecord(
                        count = (prev?.count ?: 0) + 1,
                        lastAt = now,
                        prevWord = ctxWord
                    )
                    // 一次主动选择即成为该键唯一置顶词，直接覆盖旧 winner（无需累积次数反超）
                    winner[k] = w
                    affected.add(k)
                }
                lastWord = w
            }
            dirtyKeys.addAll(affected)
            trimLocked()
        }
        scheduleSave()
    }

    // ---------- 历史统计（v1.0.67 起仅用于容量护栏，不参与置顶决策）----------

    /**
     * 历史分数，仅供 [trimLocked] 在超出 CHOICE_RECORD_CAP 时淘汰最冷记录。
     * 次线性 log(1+count) + 时间衰减 + 上下文加权；flush 已不再用它决定 order=1 归属。
     */
    private fun score(r: ChoiceRecord, ctxWord: String, now: Long): Double {
        val decay = exp(-ln(2.0) * (now - r.lastAt) / RecallConfig.CHOICE_DECAY_HALFLIFE_MS)
        val ctx = if (r.prevWord.isNotEmpty() && r.prevWord == ctxWord) RecallConfig.CHOICE_CTX_BOOST else 1.0
        return ln(1.0 + r.count) * decay * ctx
    }

    /** 内存护栏：超出硬上限按最弱分数 LRU 淘汰（有界，防 SP 与内存无限膨胀）。 */
    private fun trimLocked() {
        if (records.size <= RecallConfig.CHOICE_RECORD_CAP) return
        val ctx = lastWord
        val now = System.currentTimeMillis()
        val weakest = records.entries
            .sortedBy { score(it.value, ctx, now) }
            .take(records.size - RecallConfig.CHOICE_RECORD_CAP)
            .map { it.key }
        weakest.forEach { records.remove(it) }
    }

    // ---------- 落盘（debounce 合并）----------

    private fun scheduleSave() {
        saveTask?.let { handler.removeCallbacks(it) }
        saveTask = Runnable { flush() }
        handler.postDelayed(saveTask!!, SAVE_DEBOUNCE_MS)
    }

    /**
     * 把脏键的 winner 无条件以 **order=1（第一候选）** 写入自定义词组。
     *
     * v1.0.67：
     *  - 不再按分数排序 / 过滤 MIN_SCORE / 取 TOP_N —— 选一次即置顶，无「输好几遍」；
     *  - 只移除「脏键名下」本模块旧托管行；其它键已学到的置顶原样保留。
     *    v1.0.66 的 bug：kept 用全量 managedKeys 剔除，fresh 却只有脏键，导致每次 flush
     *    都把非脏键的置顶删掉又不加回（选了 bj 的词，aq 的置顶就掉了），用户只得反复重选；
     *  - 跳过用户手动置顶的 key=value，保证「手动置顶 > 自动学习」。
     */
    private fun flush() {
        lock.withLock {
            if (dirtyKeys.isEmpty()) return
            val keys = dirtyKeys.toSet()
            dirtyKeys.clear()
            val fresh = mutableListOf<PinyinCustomPhrase>()
            // 只剔除脏键名下的旧托管条目，其余键的托管关系原样保留
            val newManaged = managedKeys
                .filterNot { mk -> keys.any { mk.startsWith("$it=") } }
                .toMutableSet()
            for (k in keys) {
                val word = winner[k] ?: continue
                // order=1 才是第一候选（native 语义）；CHOICE_ORDER_TIERS 恒为 [1]
                val order = RecallConfig.CHOICE_ORDER_TIERS.first()
                val phrase = PinyinCustomPhrase(k, order, word)
                // 用户手动置顶优先：已置顶的 key=value 不再由本模块自动改写
                if (PhrasePinStore.isPinned(appContext, phrase)) continue
                fresh.add(phrase)
                newManaged.add(entryKeyOf(phrase))
            }
            CustomPhraseManager.withWriteLock {
                val existing = runCatching { CustomPhraseManager.loadCached() }.getOrNull()
                    ?: emptyArray()
                // 精确剔除「脏键名下」本模块写过的行（不看 order），保留应用名/用户置顶/
                // Recall/个人短语等其它来源条目，以及非脏键的本模块既有置顶；
                // 用户手动置顶的条目即便曾由本模块写过也不误删。
                val kept = existing.filterNot { p ->
                    entryKeyOf(p) in managedKeys &&
                        keys.any { k -> entryKeyOf(p).startsWith("$k=") } &&
                        !PhrasePinStore.isPinned(appContext, p)
                }
                val merged = (kept + fresh)
                    .groupBy { "${it.key}=${it.value}" }
                    .map { g -> g.value.minByOrNull { it.order }!! }
                    .toTypedArray()
                runCatching { CustomPhraseManager.saveCached(merged) }
                    .onFailure { Timber.w(it, "MBM Choice: save custom phrases failed") }
            }
            managedKeys = newManaged
            persistManagedKeys()
            persistWinner()
            persistRecords()
        }
        // v1.0.67：用户主动选择是低频强信号，立即重载（绕过 250ms 最小间隔），
        // 保证「选完抬手，下一次输入该码即第一」。
        RecallReloader.reloadNow()
    }

    private fun entryKeyOf(p: PinyinCustomPhrase): String = "${p.key}=${p.value}"

    private fun loadManagedKeys(): MutableSet<String> =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_MANAGED, emptySet())
            .orEmpty().toMutableSet()

    private fun persistManagedKeys() {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_MANAGED, managedKeys.toSet()).apply()
    }

    // ---------- 持久化（SP，仅本机；不含原文）----------

    /** winner 表：扁平 JSON { "aq": "安全", ... }，只接受 2 字词 + 2 字母键，防御历史脏数据。 */
    private fun loadWinner(): MutableMap<String, String> {
        val json = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_WINNER, null) ?: return mutableMapOf()
        return runCatching {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, String>()
            obj.keys().forEach { k ->
                val w = obj.optString(k, "")
                if (k.length == CHOICE_BOOST_MAX_LEN && k.all { it in 'a'..'z' } &&
                    w.length == CHOICE_BOOST_MAX_LEN) {
                    map[k] = w
                }
            }
            map
        }.getOrDefault(mutableMapOf())
    }

    private fun persistWinner() {
        val obj = JSONObject()
        for ((k, w) in winner) obj.put(k, w)
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_WINNER, obj.toString()).apply()
    }

    private fun loadRecords(): Map<String, ChoiceRecord> {
        val json = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECORDS, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, ChoiceRecord>()
            obj.keys().forEach { k ->
                // 防御：丢弃超长词条（v1.0.63 长键拼接垃圾或异常数据，键长>2 会前缀污染候选）
                if (k.substringAfter("=>").length > CHOICE_BOOST_MAX_LEN) return@forEach
                val r = obj.getJSONObject(k)
                map[k] = ChoiceRecord(r.getInt("c"), r.getLong("t"), r.optString("p", ""))
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun persistRecords() {
        val obj = JSONObject()
        for ((k, v) in records) {
            obj.put(
                k,
                JSONObject().apply {
                    put("c", v.count)
                    put("t", v.lastAt)
                    put("p", v.prevWord)
                }
            )
        }
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_RECORDS, obj.toString()).apply()
    }

    /**
     * 一次性遗留清理。
     *  - v3（v1.0.66）：清掉 v1.0.63~65 以旧档位 order ∈ {4,6,7} 写入的全部 boost 条目；
     *  - v4（v1.0.67）：从历史 records 按「最近上屏时间 lastAt」为每个键重建 winner，
     *    并把全部键标脏触发一次 flush —— 升级后用户之前选过的「安全」等词立即按
     *    last-commit-wins 写 order=1，无需重新输入若干遍。
     * 用 SP 标记保证各执行一次；幂等、失败不抛。
     */
    fun migrateLegacy() {
        val sp = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!sp.getBoolean(KEY_MIGRATED_V3, false)) {
            runCatching {
                // 与 flush() 保持一致的锁顺序：两把锁分开获取，绝不嵌套（避免死锁）
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
                lock.withLock {
                    managedKeys = mutableSetOf()
                    persistManagedKeys()
                    dirtyKeys.addAll(records.keys.map { it.substringBefore("=>") })
                }
                scheduleSave()
            }.onFailure { Timber.w(it, "MBM Choice: migrateLegacy v3 failed") }
            sp.edit().putBoolean(KEY_MIGRATED_V3, true).apply()
        }
        if (!sp.getBoolean(KEY_MIGRATED_V4, false)) {
            runCatching {
                lock.withLock {
                    // 每个键取「最近一次上屏」的词作为初始 winner（历史记忆直接继承）
                    for ((id, r) in records) {
                        val k = id.substringBefore("=>")
                        val w = id.substringAfter("=>")
                        if (k.length != CHOICE_BOOST_MAX_LEN || !k.all { it in 'a'..'z' }) continue
                        if (w.length != CHOICE_BOOST_MAX_LEN) continue
                        val cur = winner[k]
                        val curRec = cur?.let { records["$k=>$it"] }
                        if (curRec == null || r.lastAt > curRec.lastAt) winner[k] = w
                    }
                    persistWinner()
                    // 全量标脏：一次 flush 把所有历史 winner 以 order=1 重写
                    dirtyKeys.addAll(winner.keys)
                }
                scheduleSave()
            }.onFailure { Timber.w(it, "MBM Choice: migrateLegacy v4 failed") }
            sp.edit().putBoolean(KEY_MIGRATED_V4, true).apply()
        }
    }

    /** 已学习记录数（调试 / 验收用）。 */
    fun recordCount(): Int = records.size

    /** 清空全部个性化记录（设置页「清空选择记忆」）。 */
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
            records.clear()
            winner.clear()
            dirtyKeys.clear()
            managedKeys = mutableSetOf()
            persistManagedKeys()
            persistWinner()
            persistRecords()
        }
        RecallReloader.scheduleReload()
    }
}
