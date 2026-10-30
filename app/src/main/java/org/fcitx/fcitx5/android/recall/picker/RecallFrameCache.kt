/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 窗口帧缓存：窗口身份缓存 + 增量滚动哈希（拾取层 D1/D2/D3 去重核心）。
 *
 * 同一窗口内容未变时（静止屏 / 重复滚动到同源），哈希命中即跳过，避免对同帧重复
 * 遍历、分词、写库。哈希为 FNV-1a 变体（O(1) 增量友好），窗口表有界（LRU 自动淘汰
 * 最久未用），不会随窗口数膨胀。
 */
package org.fcitx.fcitx5.android.recall.picker

import org.fcitx.fcitx5.android.recall.RecallConfig

class RecallFrameCache(capacity: Int = 64) {

    private val hashes = LinkedHashMap<String, Int>(capacity, 0.75f, true)
    private val lastAt = LinkedHashMap<String, Long>(capacity, 0.75f, true)

    /** 滚动哈希（FNV-1a，按 uint32 低位 reinterpret 为 Int 以保持 32 位）。 */
    fun hash(text: String): Int {
        var h = 0x811c9dc5.toInt()
        for (c in text) {
            h = h xor c.code
            h *= 0x01000193
        }
        return h
    }

    /**
     * 是否应当捕获该窗口的当前帧：
     *  - 窗口首次出现 → 捕获；
     *  - 哈希变化 → 捕获；
     *  - 距上次同窗口捕获不足 [RecallConfig.PICKER_MIN_FRAME_GAP_MS] → 跳过（防抖）。
     */
    fun shouldCapture(windowKey: String, text: String, now: Long): Boolean {
        val h = hash(text)
        val prev = hashes[windowKey]
        val last = lastAt[windowKey] ?: 0L
        if (prev == h && now - last < RecallConfig.PICKER_MIN_FRAME_GAP_MS) return false
        hashes[windowKey] = h
        lastAt[windowKey] = now
        return true
    }

    fun clear() {
        hashes.clear()
        lastAt.clear()
    }
}
