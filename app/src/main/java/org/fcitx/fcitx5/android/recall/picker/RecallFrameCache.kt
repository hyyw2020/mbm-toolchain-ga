/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 窗口帧缓存：窗口身份缓存 + 增量滚动哈希（拾取层 D1/D2/D3 去重核心）。
 *
 * P1-2 修复：单张 LinkedHashMap 合并哈希与时间戳，重写 removeEldestEntry 实现
 * 真正的 LRU 淘汰（旧实现两张表均未重写，capacity 只是初始容量，窗口数无界增长；
 * 且两表独立淘汰会导致 shouldCapture 退化为「总是捕获」）。
 *
 * P1-3 修复：语义纠正——同一窗口**内容未变一律跳过**（时间不再让静止屏反复穿透）；
 * PICKER_MIN_FRAME_GAP_MS 只用于限制「变化帧」的采样频率（防抖），不再让未变帧
 * 在超过间隔后被重复 ingest，去重成本不再转嫁到 SQLCipher COUNT + 分词 + 落盘。
 *
 * 哈希为 FNV-1a 变体（O(1) 增量友好）。全部方法 synchronized：ingest 可能由
 * 拾取 HandlerThread 与 OCR 回调协程并发调用。
 */
package org.fcitx.fcitx5.android.recall.picker

import org.fcitx.fcitx5.android.recall.RecallConfig

class RecallFrameCache(capacity: Int = 64) {

    private class Entry(val hash: Int, var at: Long)

    private val cache = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > capacity
    }

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
     *  - 内容哈希未变 → 一律跳过（静止屏零成本空转，不再受时间间隔影响）；
     *  - 哈希变化但距上次捕获不足 [RecallConfig.PICKER_MIN_FRAME_GAP_MS] → 跳过（变化帧防抖）；
     *  - 哈希变化且间隔足够 → 捕获并刷新时间戳。
     */
    @Synchronized
    fun shouldCapture(windowKey: String, text: String, now: Long): Boolean {
        val h = hash(text)
        val e = cache[windowKey]
        if (e != null) {
            if (e.hash == h) return false // 内容未变，永久跳过
            if (now - e.at < RecallConfig.PICKER_MIN_FRAME_GAP_MS) return false // 变化过快，限流
        }
        cache[windowKey] = Entry(h, now)
        return true
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }
}
