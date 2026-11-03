/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 词库层（冻结方案 B2：无上限 + 冷热分层 + auto + 衰减）。
 *
 * 与 IME 同进程，直接操作 fcitx 自定义词组（native 一等公民，输入流同步出候选、零延迟）：
 *  - 写词 API：CustomPhraseManager.saveCached(Array<PinyinCustomPhrase>)（落盘 + 同步进程内缓存）
 *  - 拼音键派生：PinyinUtils.initials/fullPinyin（复用 AppPhraseImporter.keysForName）
 *  - 合并范式：loadCached → 剔除旧 managed 条目 → 合并新条目 → saveCached → 写 managed 集合
 *    （与 AppPhraseImporter.rebuildLocked 同源，保证不与用户自造词组互相覆盖）
 *
 * 冷热分层：
 *  - 最近活跃（命中热 TTL）的词条 order = HOT_ORDER（候选靠前），输入拼音首字母即出；
 *  - 超过 TTL 未再出现的词条降级为 COLD_ORDER（不打扰常用词）；
 *  - 敏感词条（模式级正则命中）强制 COLD_ORDER 并打标，不出候选首位（E2/E3 降权）。
 *  - 托管条目数超过 MANAGED_HARD_CAP 时按「最久未出现」LRU 淘汰最冷者（有界，防内存/落盘膨胀）。
 */
package org.fcitx.fcitx5.android.recall.store

import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.pinyin.customphrase.AppPhraseImporter
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.fcitx.fcitx5.android.recall.RecallConfig
import org.fcitx.fcitx5.android.recall.segment.CandidateSource
import org.fcitx.fcitx5.android.recall.segment.RecallCandidate
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object RecallWordStore {

    private const val PREFS = "mbm_recall_phrase"
    private const val KEY_MANAGED = "managed"

    /** 托管词条集合（我们写入、也负责清理的词条 value 集合）。 */
    @Volatile
    private var managed: MutableSet<String> = loadManaged()

    /** 词条 → 最近一次出现时间戳（内存态，进程重启后从热态重新衰减，足够 P0）。 */
    private val seen = mutableMapOf<String, Long>()

    /** seen 表软上限（内存护栏）：超过则按「最久未出现」清理，保证内存有界。 */
    private const val SEEN_SOFT_CAP = RecallConfig.MANAGED_HARD_CAP * 2

    private val lock = ReentrantLock()

    /**
     * 本模块写过的条目判定（order ∈ {HOT_ORDER, COLD_ORDER}）。
     *
     * 与 AppPhraseImporter 对称：两边都只删自己写过的条目，互不越界。
     * **关键约束：本判定绝不能包含 order=1。** AppPhraseImporter 的「应用搜索场景置顶」
     * 条目正是 order=1（ORDER_APP_SEARCH），旧实现曾误带 LEGACY_COLD_ORDER=1 与之冲突：
     * Recall 火力全开必然捕获应用名（如桌面「搜索应用」时看到的 WorkBuddy），其 commit()
     * 合并自定义词组时会把 AppPhraseImporter 的 order=1 置顶条目当成「自己的旧冷词」误删，
     * 导致「搜索应用」场景应用名不再置顶（降级到 order=2/8）。
     * Recall 当前只写入 {HOT_ORDER=2, COLD_ORDER=9}，与应用名的 {1, 8} 完全错开，互不越界。
     */
    private fun isOwnEntry(p: PinyinCustomPhrase): Boolean =
        p.order == RecallConfig.HOT_ORDER || p.order == RecallConfig.COLD_ORDER

    private fun loadManaged(): MutableSet<String> =
        appContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getStringSet(KEY_MANAGED, emptySet())
            .orEmpty().toMutableSet()

    private fun persistManaged() {
        appContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_MANAGED, managed).apply()
    }

    /**
     * 提交一批候选：派生拼音键 → 合并落盘 → 触发批量 reload。
     * 有界：单批超过 [RecallConfig.CANDIDATE_BATCH_CAP] 只取最前（丢弃最旧，不堆队列）。
     */
    fun commit(candidates: List<RecallCandidate>) {
        if (candidates.isEmpty()) return
        val batch = if (candidates.size > RecallConfig.CANDIDATE_BATCH_CAP)
            candidates.take(RecallConfig.CANDIDATE_BATCH_CAP) else candidates
        lock.withLock {
            val now = System.currentTimeMillis()
            // 冷热分层修复：order 必须基于「本次出现之前」的 seen 计算。
            // 旧实现先把 seen[p]=now 再算 order，于是 now-last 恒为 0，
            // 所有词条都被打成 HOT_ORDER —— 冷热分层与「长期不用降权」完全失效。
            val orders = HashMap<String, Int>(batch.size * 2)
            batch.forEach { cand ->
                if (!orders.containsKey(cand.phrase)) {
                    orders[cand.phrase] = orderFor(cand, now)
                }
            }
            val freshPhrases = batch.map { it.phrase }.distinct()
            for (p in freshPhrases) seen[p] = now

            val freshEntries = batch.flatMap { cand ->
                val keys = AppPhraseImporter.keysForName(cand.phrase)
                val order = orders[cand.phrase] ?: RecallConfig.COLD_ORDER
                keys.map { PinyinCustomPhrase(it, order, cand.phrase) }
            }

            // 「读 → 合并 → 落盘」整段置于全局写锁内，与 AppPhraseImporter 场景重建互斥，
            // 避免两者并发读-改-写互相抹掉对方刚写入的词条（v1.0.41）。
            val nextManaged = CustomPhraseManager.withWriteLock {
                val existing = runCatching { CustomPhraseManager.loadCached() }
                    .getOrNull() ?: emptyArray()
                // 只剔除本模块写过的旧条目：应用名条目（order ∈ {1,8}）与用户自造词组保留
                val kept = existing.filterNot { isOwnEntry(it) && it.value in managed }

                var merged = (kept + freshEntries).toMutableList()
                val managed2 = (managed + freshPhrases).toMutableSet()

                // 超过硬上限 → 按最久未出现 LRU 淘汰最冷
                val overflow = managed2.size - RecallConfig.MANAGED_HARD_CAP
                if (overflow > 0) {
                    val coldest = managed2
                        .sortedBy { seen[it] ?: 0L }
                        .take(overflow)
                        .toSet()
                    managed2 -= coldest
                    merged = merged.filterNot { isOwnEntry(it) && it.value in coldest }
                        .toMutableList()
                    // 内存有界：被淘汰的词条同步移出 seen。旧实现只裁 managed 不裁 seen，
                    // seen 随历史词组只增不减（词库无上限 ⇒ 内存无上限），违背冻结方案内存护栏。
                    coldest.forEach { seen.remove(it) }
                }

                runCatching { CustomPhraseManager.saveCached(merged.toTypedArray()) }
                    .onFailure { Timber.w(it, "MBM Recall: save custom phrases failed") }
                managed2
            }
            // seen 兜底上限：即便走不到淘汰分支（如 MANAGED_HARD_CAP 调大），
            // 也保证「最近出现时间」表不无限膨胀，超出按最久未出现清理。
            trimSeenLocked()
            managed = nextManaged
            persistManaged()
        }
        RecallReloader.scheduleReload()
    }

    /**
     * 顺序决策：敏感 → 冷；否则按最近活跃度（热 TTL 内为热，否则冷）。
     * 调用方必须在更新 seen **之前**调用，否则恒为热（见 commit 内注释）。
     */
    private fun orderFor(cand: RecallCandidate, now: Long): Int {
        if (cand.sensitive) return RecallConfig.COLD_ORDER
        val last = seen[cand.phrase] ?: now
        return if (now - last <= RecallConfig.HOT_TTL_MS) RecallConfig.HOT_ORDER
        else RecallConfig.COLD_ORDER
    }

    /** seen 兜底裁剪（锁内调用）：超出软上限时清理最久未出现的条目。 */
    private fun trimSeenLocked() {
        val overflow = seen.size - SEEN_SOFT_CAP
        if (overflow <= 0) return
        seen.entries
            .sortedBy { it.value }
            .take(overflow)
            .map { it.key }
            .forEach { seen.remove(it) }
    }

    /** 已托管词条数（调试 / 验收用）。 */
    fun managedCount(): Int = managed.size

    /** 清空全部托管词条（设置页「清空屏幕记忆词组」）。 */
    fun clearAll() {
        lock.withLock {
            CustomPhraseManager.withWriteLock {
                val existing = runCatching { CustomPhraseManager.loadCached() }
                    .getOrNull() ?: emptyArray()
                // 同样只清本模块写过的条目，保留应用名条目与用户自造词组
                val kept = existing.filterNot { isOwnEntry(it) && it.value in managed }
                runCatching { CustomPhraseManager.saveCached(kept.toTypedArray()) }
            }
            managed = mutableSetOf()
            seen.clear()
            persistManaged()
        }
        RecallReloader.scheduleReload()
    }
}
