/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 *
 * MBM v1.0.31「词库大小无上限」：
 *  - native load()/save() 在预编译库里，全量数组进内存是平台行为，无条数硬上限；
 *  - 但「每次长按候选菜单都全量 JNI load」在大词库下（百万级条目）不可接受，
 *    故加进程内快照缓存：loadCached/saveCached，保存/导入后缓存自动同步；
 *  - Kotlin 侧原有的 100MB 落盘裁剪已移除（见 PinyinCustomPhraseFragment.saveConfig
 *    与 pitfalls #70）——保存失败要精确可观测，不静默丢用户数据。
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase

object CustomPhraseManager {
    @JvmStatic
    external fun load(): Array<PinyinCustomPhrase>?

    @JvmStatic
    external fun save(items: Array<PinyinCustomPhrase>)

    /** 进程内快照（IME 服务与设置页同进程，保存即同步；跨进程写入需 invalidateCache）。 */
    @Volatile
    private var cache: Array<PinyinCustomPhrase>? = null

    /** 带缓存的读取：首次全量 JNI load，之后命中内存快照。 */
    @JvmStatic
    fun loadCached(): Array<PinyinCustomPhrase>? {
        cache?.let { return it }
        return load().also { cache = it }
    }

    /** 带缓存的保存：落盘后同步快照，避免随后的一次 load 又走 JNI。 */
    @JvmStatic
    fun saveCached(items: Array<PinyinCustomPhrase>) {
        save(items)
        cache = items
    }

    /** 外部（非本类路径）改动了词库时调用，强制下次重新 load。 */
    @JvmStatic
    fun invalidateCache() {
        cache = null
    }
}
