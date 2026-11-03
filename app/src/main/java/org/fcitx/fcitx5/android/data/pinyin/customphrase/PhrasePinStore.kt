/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 自定义词组的「置顶（最高优先级）」状态存储（本地 SharedPreferences）。
 * 置顶的词组 order 被强制设为 [PIN_ORDER]，稳定压过所有自动导入词组与应用名，
 * 输入对应拼音即出现在候选首位；取消置顶后恢复原 order。
 * 以 `key|value` 为键（与 PhraseLockStore 同策略），不依赖 order 正负，
 * 因此启用/停用（order 符号变化）不会让置顶状态丢失。
 */
package org.fcitx.fcitx5.android.data.pinyin.customphrase

import android.content.Context
import android.content.SharedPreferences

object PhrasePinStore {

    private const val PREFS = "mbm_phrase_pin"
    private const val KEY = "pinned" // key|value -> 原 order（取消置顶时恢复）

    private val cache = mutableMapOf<String, Int>()
    private var initialized = false

    private fun ensure(ctx: Context) {
        if (initialized) return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.all.forEach { (k, v) ->
            if (v is Int) cache[k] = v
        }
        initialized = true
    }

    /** 规范化键：去掉 order 影响，只认 key 与 value */
    private fun keyOf(p: PinyinCustomPhrase): String = "${p.key}|${p.value}"

    fun isPinned(ctx: Context, phrase: PinyinCustomPhrase): Boolean {
        ensure(ctx)
        return cache.containsKey(keyOf(phrase))
    }

    /** 置顶时记录原 order，便于取消时恢复 */
    fun pin(ctx: Context, phrase: PinyinCustomPhrase, originalOrder: Int) {
        ensure(ctx)
        cache[keyOf(phrase)] = originalOrder
        persist(ctx)
    }

    fun unpin(ctx: Context, phrase: PinyinCustomPhrase) {
        ensure(ctx)
        cache.remove(keyOf(phrase))
        persist(ctx)
    }

    /** 取回置顶前记录的原 order；未记录时返回默认 5 */
    fun getOriginalOrder(ctx: Context, phrase: PinyinCustomPhrase, fallback: Int = 5): Int {
        ensure(ctx)
        return cache[keyOf(phrase)] ?: fallback
    }

    private fun persist(ctx: Context) {
        val sp: SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = sp.edit()
        edit.clear()
        cache.forEach { (k, v) -> edit.putInt(k, v) }
        edit.apply()
    }
}
