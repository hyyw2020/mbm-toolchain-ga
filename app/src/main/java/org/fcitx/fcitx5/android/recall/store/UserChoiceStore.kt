/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 用户选择驱动的个性化优先级（预判 / 联想增强层）。
 *
 * 捕获点：FcitxInputMethodService 的 CommitStringEvent（用户上屏中文 = 手动输入 + 主动选择的
 * 最强信号）。对每个上屏中文词，复用 AppPhraseImporter.keysForName 反推拼音首字母键，维护
 * (键 => 词) 的选择计数 / 最近选择时间 / 上文词，用「次线性累加 + 时间衰减 + 上下文加权」打分，
 * 把同键下分数 Top-N 的词写入 fcitx 自定义词组（order 4/6/7）。下次输入该拼音首字母即靠前出候选。
 *
 * 与 Recall(3/9)、AppPhrase(2/8)、置顶(1) 档位隔离：本模块独占 4/6/7，managed 集合独立，
 * 各模块 isOwnEntry 互不越界，合并写自定义词组时不会互相误删（见 RecallWordStore.isOwnEntry 注释）。
 * fcitx5 自定义词组为 native 一等公民，输入流同步出候选、零延迟，无需改动引擎解码器。
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
    private const val KEY_RECORDS = "records"
    private const val KEY_ENABLED = "enabled"

    private val lock = ReentrantLock()

    /** (键=>词) -> 记录。内存镜像，进程重启从 SP 恢复。 */
    private val records = loadRecords().toMutableMap()

    /** 已托管词条 value 集合（与 Recall/AppPhrase 对称，合并写时用于 isOwnEntry 判定）。 */
    @Volatile
    private var managed: MutableSet<String> = mutableSetOf()

    /** 上一条上屏中文词（用于上下文 bigram 权重，prevWord == 当前上文时 ×CHOICE_CTX_BOOST）。 */
    @Volatile
    private var lastWord: String = ""

    /** 脏键累积（debounce 窗口内合并多次提交的 key，flush 一次性重算）。 */
    private val dirtyKeys = mutableSetOf<String>()

    private val handler = Handler(Looper.getMainLooper())
    private var saveTask: Runnable? = null
    private const val SAVE_DEBOUNCE_MS = 400L

    /** 提取 2~MAX_WHOLE_PHRASE_LEN 连续汉字串（与 Recall 分词长度上限对齐）。 */
    private val HANZI_RE =
        Regex("[\\u4e00-\\u9fff]{2,${RecallConfig.MAX_WHOLE_PHRASE_LEN}}")

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
     */
    fun recordCommit(text: String) {
        if (!isEnabled()) return
        // 敏感串（卡号/身份证/验证码）整体跳过：不学习、不写库（隐私 E2/E3 降权原则）
        if (RecallConfig.SENSITIVE_PATTERNS.any { it.containsMatchIn(text) }) return
        val words = HANZI_RE.findAll(text).map { it.value }.toList()
        if (words.isEmpty()) return
        val ctxWord = lastWord
        val affected = mutableSetOf<String>()
        lock.withLock {
            for (w in words) {
                val keys = AppPhraseImporter.keysForName(w)
                for (k in keys) {
                    val id = "$k=>$w"
                    val prev = records[id]
                    records[id] = ChoiceRecord(
                        count = (prev?.count ?: 0) + 1,
                        lastAt = System.currentTimeMillis(),
                        prevWord = ctxWord
                    )
                    affected.add(k)
                }
                lastWord = w
            }
            dirtyKeys.addAll(affected)
            trimLocked()
        }
        scheduleSave()
    }

    // ---------- 打分 ----------

    /**
     * 个性化优先级分数。
     *  - 次线性：log(1+count) 防止高频词无限膨胀、压制合理候选；
     *  - 时间衰减：exp(-ln2 · Δt / 半衰期)，长期不选自然回落；
     *  - 上下文：prevWord 命中当前上文 ×CHOICE_CTX_BOOST（bigram 个性化）。
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

    /** 把脏键下 Top-N 词重算并写入自定义词组；同键多词按分数分配 order 4/6/7（选最多的最靠前）。 */
    private fun flush() {
        lock.withLock {
            if (dirtyKeys.isEmpty()) return
            val keys = dirtyKeys.toSet()
            dirtyKeys.clear()
            val now = System.currentTimeMillis()
            val ctx = lastWord
            val fresh = mutableListOf<PinyinCustomPhrase>()
            val newManaged = mutableSetOf<String>()
            for (k in keys) {
                val ranked = records.entries
                    .filter {
                        it.key.startsWith("$k=>") &&
                            score(it.value, ctx, now) >= RecallConfig.CHOICE_MIN_SCORE
                    }
                    .sortedByDescending { score(it.value, ctx, now) }
                    .take(RecallConfig.CHOICE_TOP_N)
                ranked.forEachIndexed { i, e ->
                    val word = e.key.substringAfter("=>")
                    val order = RecallConfig.CHOICE_ORDER_TIERS.getOrElse(i) {
                        RecallConfig.CHOICE_ORDER_TIERS.last()
                    }
                    fresh.add(PinyinCustomPhrase(k, order, word))
                    newManaged.add(word)
                }
            }
            val mergedAll = CustomPhraseManager.withWriteLock {
                val existing = runCatching { CustomPhraseManager.loadCached() }.getOrNull()
                    ?: emptyArray()
                // 只剔除本模块写过的旧条目（order ∈ 4/6/7 且 value 在 managed 内），
                // 保留应用名/用户置顶/Recall 等其它来源条目（与 RecallWordStore 对称范式）
                val kept = existing.filterNot { isOwnEntry(it) && it.value in managed }
                val merged = (kept + fresh)
                    .groupBy { "${it.key}=${it.value}" }
                    .map { g -> g.value.minByOrNull { it.order }!! }
                    .toTypedArray()
                runCatching { CustomPhraseManager.saveCached(merged) }
                    .onFailure { Timber.w(it, "MBM Choice: save custom phrases failed") }
                merged
            }
            managed = mergedAll.filter { isOwnEntry(it) }.map { it.value }.toMutableSet()
            persistRecords()
        }
        RecallReloader.scheduleReload()
    }

    private fun isOwnEntry(p: PinyinCustomPhrase): Boolean = p.order in RecallConfig.CHOICE_ORDER_TIERS

    // ---------- 持久化（SP，仅本机；不含原文）----------

    private fun loadRecords(): Map<String, ChoiceRecord> {
        val json = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECORDS, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, ChoiceRecord>()
            obj.keys().forEach { k ->
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

    /** 已学习记录数（调试 / 验收用）。 */
    fun recordCount(): Int = records.size

    /** 清空全部个性化记录（设置页「清空选择记忆」）。 */
    fun clearAll() {
        lock.withLock {
            CustomPhraseManager.withWriteLock {
                val existing = runCatching { CustomPhraseManager.loadCached() }.getOrNull()
                    ?: emptyArray()
                val kept = existing.filterNot { isOwnEntry(it) && it.value in managed }
                runCatching { CustomPhraseManager.saveCached(kept.toTypedArray()) }
            }
            records.clear()
            dirtyKeys.clear()
            managed = mutableSetOf()
            persistRecords()
        }
        RecallReloader.scheduleReload()
    }
}
