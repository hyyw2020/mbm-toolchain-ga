/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 自定义词组的「锁定」状态存储（本地 SharedPreferences）。
 * 锁定后的词组在「全选删除（未锁定）」和列表多选删除中都会被跳过，
 * 避免误删常用的固定词条。锁定以 `key|value` 为键，不直接依赖 order 正负，
 * 因此启用/停用（order 符号变化）不会让锁定状态丢失。
 */
package org.fcitx.fcitx5.android.data.pinyin.customphrase

import android.content.Context
import android.content.SharedPreferences

object PhraseLockStore {

    private const val PREFS = "mbm_phrase_lock"
    private const val KEY = "locked"

    private val cache = mutableSetOf<String>()
    private var initialized = false

    private fun ensure(ctx: Context) {
        if (initialized) return
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cache.addAll(sp.getStringSet(KEY, emptySet()) ?: emptySet())
        initialized = true
    }

    /** 规范化键：去掉 order 正负的影响，只认 key 与 value */
    private fun keyOf(p: PinyinCustomPhrase): String = "${p.key}|${p.value}"

    fun isLocked(ctx: Context, phrase: PinyinCustomPhrase): Boolean {
        ensure(ctx)
        return cache.contains(keyOf(phrase))
    }

    fun setLocked(ctx: Context, phrase: PinyinCustomPhrase, locked: Boolean) {
        ensure(ctx)
        if (locked) cache.add(keyOf(phrase)) else cache.remove(keyOf(phrase))
        persist(ctx)
    }

    /** 切换锁定状态并返回切换后的状态 */
    fun toggle(ctx: Context, phrase: PinyinCustomPhrase): Boolean {
        val next = !isLocked(ctx, phrase)
        setLocked(ctx, phrase, next)
        return next
    }

    private fun persist(ctx: Context) {
        val sp: SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sp.edit().putStringSet(KEY, cache.toSet()).apply()
    }
}
