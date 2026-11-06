/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 上下文「下一词」预测的 Android 适配层（v1.0.72）。
 *
 * 与 UserChoiceStore 的分工：
 *  - UserChoiceStore / ChoiceRanker：用户**选过**什么 -> 该键硬层冠军（last-commit-wins）。
 *  - 本模块：用户**还没选**、但刚上屏的词让下一个词大概率可猜 -> 预测冠军。
 * 二者都写 order=1，但本模块严格让位：任一键已有用户硬层历史 / 该词已被用户
 * 选过 / 该条目已手动置顶，一律不写。因此「选择 >= 1 次即第一」的规则完全不被
 * 削弱，只是补上了「零样本也能预判」的场景。
 *
 * 生效链路：commitText -> SmartDictManager.recordUsage 更新本地 bigram/trigram
 * -> 本模块 onCommit 纯内存计算 -> 60ms 去抖写 CustomPhraseManager
 * -> RecallReloader.reloadNow()。全链路本机、无网络、无预置训练数据。
 *
 * 预测是「最近一次上屏的下一步」快照：每次上屏都**全量替换**本模块托管行
 * （先按精确 key=value 删旧行再写新行），所以不会堆积、不会永久占首位。
 *
 * 档位安全：本模块只写 order=1。其它自动模块的 isOwnEntry 区间为
 * AppPhraseImporter {2,8} / RecallWordStore {3,9} / PersonalPhraseImporter 3..7，
 * 均不含 1，故它们的 rebuild 不会把预测行当「自己的旧条目」误删（v1.0.70/71
 * 已就 order=1 论证过同一结论）。
 *
 * 防御（与 v1.0.66~71 一致）：全局写锁、精确托管集合、落盘成功才提交托管、
 * 失败有限重试、跳过手动置顶、隐私模式/学习开关过滤、60ms + reloadNow。
 */
package org.fcitx.fcitx5.android.recall.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.fcitx.fcitx5.android.data.password.PrivacyController
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PhraseKeys
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PhrasePinStore
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.fcitx.fcitx5.android.data.smartdict.SmartDictManager
import org.fcitx.fcitx5.android.recall.RecallConfig
import org.fcitx.fcitx5.android.recall.rank.BoostEntry
import org.fcitx.fcitx5.android.recall.rank.ContextPredictor
import org.fcitx.fcitx5.android.recall.rank.PredictionConfig
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object PredictionStore {

    private const val PREFS = "mbm_context_pred"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MANAGED = "managed_keys_pred"

    private val lock = ReentrantLock()
    private val handler = Handler(Looper.getMainLooper())
    private var saveTask: Runnable? = null
    private var retry = 0

    /** 60ms：与 UserChoiceStore 同节奏，选完抬手即生效。 */
    private const val SAVE_DEBOUNCE_MS = 60L

    private val predictor = ContextPredictor(
        PredictionConfig(
            maxWords = RecallConfig.PREDICT_MAX_WORDS,
            maxEntries = RecallConfig.PREDICT_MAX_ENTRIES,
            minConfidence = RecallConfig.PREDICT_MIN_CONFIDENCE,
            perSeedLimit = RecallConfig.PREDICT_PER_SEED,
            keyLenMax = RecallConfig.PREDICT_KEY_LEN_MAX,
        )
    )

    /** 本模块写进词库的精确 `key=value` 集合（与 order 无关），持久化以便跨进程清理。 */
    @Volatile
    private var managedKeys: MutableSet<String> = loadManaged()

    /** 待写入的最新预测快照；null 表示「尚未计算过」，空集合表示「本次无预测」。 */
    @Volatile
    private var pending: List<BoostEntry>? = null

    private fun prefs() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs().getBoolean(KEY_ENABLED, true)

    fun setEnabled(v: Boolean) {
        prefs().edit().putBoolean(KEY_ENABLED, v).apply()
        if (!v) clearAll()
    }

    /**
     * 一次真实上屏后调用（应在 SmartDictManager.recordUsage 之后，保证上下文最新）。
     * 纯内存计算 + 排期，不阻塞调用线程；异常一律吞掉，绝不影响输入。
     */
    fun onCommit(text: String) {
        if (!isEnabled() || !SmartDictManager.isLearningEnabled()) return
        if (PrivacyController.inPrivacyMode) return
        // 粘贴/整段文本不做上下文预测（与 ChoiceRanker 的 maxCommitLen 口径一致）
        if (text.length > RecallConfig.CHOICE_MAX_COMMIT_LEN) return

        val entries = runCatching {
            val context = SmartDictManager.recentContextWords(2)
            predictor.predict(
                context = context,
                bigram = { prev, limit ->
                    SmartDictManager.predictContinuationCounts(prev, limit, RecallConfig.PREDICT_MIN_CONFIDENCE)
                },
                trigram = { a, b, limit ->
                    SmartDictManager.predictTriContinuation(a, b, limit, RecallConfig.PREDICT_MIN_CONFIDENCE)
                },
                keysFor = { w -> PhraseKeys.forWord(w) },
                hasUserHistory = { k -> UserChoiceStore.hasHistory(k) },
                hasUserWord = { w -> UserChoiceStore.hasUserWord(w) },
                isPinned = { k, w -> PhrasePinStore.isPinned(appContext, PinyinCustomPhrase(k, 1, w)) },
            )
        }.onFailure {
            Timber.w(it, "MBM Predict: compute failed")
        }.getOrDefault(emptyList())

        pending = entries
        scheduleSave()
    }

    @Synchronized
    private fun scheduleSave() {
        saveTask?.let { handler.removeCallbacks(it) }
        saveTask = Runnable { flush() }
        handler.postDelayed(saveTask!!, SAVE_DEBOUNCE_MS)
    }

    /** 把最新预测快照写入词库，先精确剔除本模块上次的托管行。 */
    private fun flush() {
        var saved = false
        lock.withLock {
            val snapshot = pending ?: return
            pending = null

            val fresh = mutableListOf<PinyinCustomPhrase>()
            val newManaged = mutableSetOf<String>()
            for (e in snapshot) {
                // 双保险：计算时已查过置顶，写入前再查一次（期间用户可能在设置页置顶）
                if (PhrasePinStore.isPinned(appContext, PinyinCustomPhrase(e.key, e.order, e.word))) continue
                fresh += PinyinCustomPhrase(e.key, e.order, e.word)
                newManaged += "${e.key}=${e.word}"
            }

            var ok = false
            CustomPhraseManager.withWriteLock {
                val existing = runCatching { CustomPhraseManager.loadCached() }.getOrNull()
                    ?: emptyArray()
                // 只删「本模块托管」的行，且保护手动置顶；其它模块/用户条目原样保留。
                val kept = existing.filterNot { p ->
                    "${p.key}=${p.value}" in managedKeys &&
                        !PhrasePinStore.isPinned(appContext, p)
                }
                val merged = (kept + fresh)
                    .groupBy { "${it.key}=${it.value}" }
                    .map { g -> g.value.minByOrNull { it.order }!! }
                    .toTypedArray()
                ok = runCatching { CustomPhraseManager.saveCached(merged) }
                    .onFailure { Timber.w(it, "MBM Predict: save custom phrases failed") }
                    .isSuccess
            }

            if (ok) {
                managedKeys = newManaged
                persistManaged()
                retry = 0
            } else {
                // 失败：把快照放回，保留旧托管集合，有限重试（不丢预测）。
                pending = snapshot
                if (retry < 3) {
                    retry++
                    handler.postDelayed({ flush() }, 1000L)
                } else {
                    retry = 0
                }
            }
            saved = ok
        }
        if (saved) RecallReloader.reloadNow()
    }

    /** 关闭学习 / 清空统计时调用：撤回全部预测行，不残留首位候选。 */
    fun clearAll() {
        lock.withLock {
            pending = emptyList()
            var ok = false
            CustomPhraseManager.withWriteLock {
                val existing = runCatching { CustomPhraseManager.loadCached() }.getOrNull()
                    ?: emptyArray()
                val kept = existing.filterNot { p ->
                    "${p.key}=${p.value}" in managedKeys &&
                        !PhrasePinStore.isPinned(appContext, p)
                }
                ok = runCatching { CustomPhraseManager.saveCached(kept.toTypedArray()) }
                    .onFailure { Timber.w(it, "MBM Predict: clear failed") }
                    .isSuccess
            }
            if (ok) {
                managedKeys = mutableSetOf()
                persistManaged()
            }
        }
        RecallReloader.reloadNow()
    }

    private fun loadManaged(): MutableSet<String> =
        prefs().getStringSet(KEY_MANAGED, emptySet()).orEmpty().toMutableSet()

    private fun persistManaged() {
        prefs().edit().putStringSet(KEY_MANAGED, managedKeys.toSet()).apply()
    }
}
