/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 个人学习 → 候选预测闭环（MBM v1.0.46）。
 *
 * 痛点：此前 [org.fcitx.fcitx5.android.data.smartdict.SmartDictManager] 只把用户上屏的
 * 高频词记进本地统计文件（smart_dict_usage.json），仅供「离线智能词库」页面展示——
 * 统计从不回流候选，用户最常用的词反而从不出候选，等于没有「预测」。
 *
 * 方案：把统计里的高频个人词写入 pinyin 自定义词组（与 AppPhraseImporter / RecallWordStore
 * 同源的 native 一等公民通道，输入流同步出候选、零延迟）：
 *  - key = 个人词的拼音键（首字母/全拼，复用 AppPhraseImporter.keysForName）；
 *  - value = 个人词本身；
 *  - order 按词频分层：高频词 order=3（仅次于屏幕记忆热词 2、应用搜索 1，敲拼音即靠前），
 *    低频词 order=7（靠后不打扰常用词）。
 *
 * order 取值刻意避开其它模块（应用名 1/8、Recall 热 2/冷 9、遗留冷 1），合并词库时
 * 靠 order 区分归属，互不越界清理（见 isOwnEntry）。
 *
 * 全程在 CustomPhraseManager 全局写锁内做「读-改-写」，与应用名/屏幕记忆的写词互斥，
 * 落盘后复用 RecallReloader 去抖 reload 通知 fcitx 重新解析自定义词组。
 */
package org.fcitx.fcitx5.android.data.pinyin.customphrase

import android.content.Context
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.smartdict.SmartDictManager
import org.fcitx.fcitx5.android.recall.store.RecallReloader
import timber.log.Timber

object PersonalPhraseImporter {

    private const val PREFS = "mbm_personal_phrase"
    private const val KEY_MANAGED = "managed_keys"

    /** 高频个人词顺序：候选第 3 位（首屏可见、敲拼音即靠前）。 */
    private const val PERSONAL_HOT_ORDER = 3

    /** 低频个人词顺序：第 7 位（靠后，不打扰常用词，也不占首屏）。 */
    private const val PERSONAL_COLD_ORDER = 7

    /** 单次同步注入候选的条数上限（按词频取前 N），保证托管集合有界、reload 可控。 */
    private const val SYNC_LIMIT = 800

    /** 上下文预测：取最近提交词的高频后继词个数（抬到候选前列的数量）。 */
    private const val CONTEXT_FOLLOWERS = 5

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 托管条目集合（我们写入、也负责清理的词条 key=value 集合）。 */
    private fun loadManaged(ctx: Context): MutableSet<String> =
        prefs(ctx).getStringSet(KEY_MANAGED, emptySet()).orEmpty().toMutableSet()

    private fun persistManaged(ctx: Context, set: MutableSet<String>) {
        prefs(ctx).edit().putStringSet(KEY_MANAGED, set).apply()
    }

    /** 本模块写过的条目判定（order ∈ {3, 7}）。 */
    private fun isOwnEntry(p: PinyinCustomPhrase): Boolean =
        p.order == PERSONAL_HOT_ORDER || p.order == PERSONAL_COLD_ORDER

    private fun managedKeyOf(p: PinyinCustomPhrase): String = "${p.key}=${p.value}"

    /**
     * 把本地统计的高频个人词写入自定义词组。关闭学习开关或统计为空时直接返回。
     * 全程在全局写锁内读-改-写，与 AppPhraseImporter / RecallWordStore 互斥。
     *
     * MBM v1.0.48：候选顺序从「高频=3 / 低频=7」两档升级为**按词频+新鲜度的连续分级**
     * （order 落在 3..7 之间，越常用的词越靠前）；并叠加**上下文预测**——把最近提交词的高频
     * 后继词整体抬到候选前列（order 封顶为 3），实现「刚打完 X，Y 就排在前面」。
     */
    fun syncFromUsage(ctx: Context) {
        if (!SmartDictManager.isLearningEnabled()) return
        val top = SmartDictManager.topWords(SYNC_LIMIT)
        if (top.isEmpty()) return
        // 上下文预测：取最近提交词的高频后继词，构成「应被前置」的候选集合
        val contextBoost = mutableSetOf<String>()
        for (cw in SmartDictManager.recentContextWords(2)) {
            contextBoost += SmartDictManager.predictNext(cw, CONTEXT_FOLLOWERS)
        }
        val now = System.currentTimeMillis()
        // 按词频+新鲜度算出每个词的候选顺序（落在 3..7），上下文命中者封顶到最高档
        val entries = ArrayList<PinyinCustomPhrase>(top.size * 3)
        top.forEach { (word, count) ->
            var order = gradedOrder(count, SmartDictManager.wordRecency(word), now)
            if (word in contextBoost) order = order.coerceAtMost(PERSONAL_HOT_ORDER)
            AppPhraseImporter.keysForName(word).forEach { key ->
                entries += PinyinCustomPhrase(key, order, word)
            }
        }
        val managedKeys = entries.map { managedKeyOf(it) }.toSet()
        val managed = loadManaged(ctx)
        CustomPhraseManager.withWriteLock {
            val existing = runCatching { CustomPhraseManager.loadCached() }.getOrNull() ?: emptyArray()
            // 只剔除本模块自己写过的旧条目，应用名/屏幕记忆/用户自造词组一概保留
            val kept = existing.filterNot { isOwnEntry(it) && managed.contains(managedKeyOf(it)) }
            val merged = (kept + entries).toTypedArray()
            runCatching { CustomPhraseManager.saveCached(merged) }
                .onFailure { Timber.w(it, "MBM: save personal phrase failed") }
        }
        persistManaged(ctx, managedKeys.toMutableSet())
        Timber.d("MBM: personal phrase synced words=${top.size} entries=${entries.size} contextBoost=${contextBoost.size}")
        // 复用 Recall 的去抖 reload 通道，通知 fcitx 重新解析自定义词组（候选即时生效）
        RecallReloader.scheduleReload()
    }

    /**
     * v1.0.48：把词频与新鲜度合成为候选顺序。
     *  - 词频项：ln(count+1)，常用词分高；
     *  - 新鲜度项：最近上屏的词额外加权，权重按指数衰减（约 3 天半衰期），让「当前话题」的词优先。
     * 合成分映射到 [PERSONAL_HOT_ORDER=3, PERSONAL_COLD_ORDER=7]，常用且新鲜的词最靠前。
     */
    private fun gradedOrder(count: Int, lastSeen: Long?, now: Long): Int {
        val freqScore = ln((count + 1).toDouble())
        val recencyScore = if (lastSeen != null) {
            val ageDays = (now - lastSeen) / (24.0 * 3600 * 1000)
            exp(-ageDays / 3.0)
        } else 0.0
        val score = freqScore + recencyScore
        val t = (score / 6.0).coerceIn(0.0, 1.0)
        val step = ((1 - t) * (PERSONAL_COLD_ORDER - PERSONAL_HOT_ORDER)).roundToInt()
        return PERSONAL_HOT_ORDER + step
    }

    /**
     * 仅当本地统计自上次同步后有新增才同步（IME 周期性调用），避免无谓的 reload 抖动。
     */
    fun syncIfDirty(ctx: Context) {
        if (!SmartDictManager.isLearningEnabled()) return
        if (!SmartDictManager.consumeDirty()) return
        syncFromUsage(ctx)
    }

    /**
     * v1.0.48：上屏后即时触发「上下文预测」重排——把最近提交词的高频后继词抬到候选前列。
     * 走与 [syncFromUsage] 相同的自定义词组通道（输入流同步出候选、零延迟），经 RecallReloader
     * 去抖 reload 即时生效。带双重节流（全局最短 1.5s、同一上下文 5s 内不重复）避免高频提交下
     * 反复重写词库；上下文无变化或学习关闭时直接跳过。
     */
    @Volatile
    private var lastRerankMs = 0L

    @Volatile
    private var lastRerankCtx = ""

    fun requestContextRerank(ctx: Context) {
        if (!SmartDictManager.isLearningEnabled()) return
        val ctxKey = SmartDictManager.recentContextWords(2).joinToString("|")
        val now = System.currentTimeMillis()
        // 同一上下文 5s 内不重复重排（已在前词基础上排好）
        if (ctxKey == lastRerankCtx && now - lastRerankMs < 5000) return
        // 全局最短间隔 1.5s，避免连续提交（如快速选词）频繁重写词库
        if (now - lastRerankMs < 1500) return
        lastRerankMs = now
        lastRerankCtx = ctxKey
        SmartDictManager.markDirty()
        syncFromUsage(ctx)
        // 本次已全量同步（含上下文），清掉脏标记避免周期性同步重复劳动
        SmartDictManager.consumeDirty()
    }

    /** IME 启动后立即做一次，让历史高频词立即可作为候选（首屏即用）。 */
    fun initialSync(ctx: Context) = syncFromUsage(ctx)

    /** 关闭学习或清空统计时，清掉已注入的个人候选，避免残留。 */
    fun clearAll(ctx: Context) {
        val managed = loadManaged(ctx)
        CustomPhraseManager.withWriteLock {
            val existing = runCatching { CustomPhraseManager.loadCached() }.getOrNull() ?: emptyArray()
            val kept = existing.filterNot { isOwnEntry(it) && managed.contains(managedKeyOf(it)) }
            runCatching { CustomPhraseManager.saveCached(kept.toTypedArray()) }
        }
        persistManaged(ctx, mutableSetOf())
        RecallReloader.scheduleReload()
    }
}
