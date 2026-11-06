/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 剪贴板表情包的「保留最近 N 条」策略 —— 纯 JVM，零 Android 依赖。
 *
 * 真 bug 背景：v1.0.75 的 `importClipboardImage` 开头有一句
 * `if (existing.items.size >= MAX_ITEMS_PER_PACK) return false`，
 * 而函数尾部又写了 `takeLast(MAX_ITEMS_PER_PACK)` 的淘汰逻辑 —— 两者矛盾，
 * 淘汰分支永远执行不到（能走到时最多 499+1=500，不会超）。同时文件名由
 * `System.nanoTime()` 生成，重复复制**同一张图**也会不断新增条目，用户累计
 * 复制 500 次后新表情永久无法加入，「复制表情」功能彻底失效。
 *
 * 修复：文件名改由内容摘要派生（同名即同内容），合并时先移除同名旧条目再
 * 追加（等于把重复项刷新为「最近使用」），最后按上限从尾部保留最新条目。
 * 这样配额永远不会被重复项吃掉，也不会因达到上限而拒绝新内容。
 */
package org.fcitx.fcitx5.android.data.emoji

object ClipboardStickerPackPolicy {

    /**
     * 合并一条新导入的条目。
     *
     * @param existing 现有清单（调用方应已过滤掉文件不存在的幽灵条目）
     * @param newItem  本次导入的条目，其 file 由内容摘要派生
     * @param limit    条目上限，<=0 视为不保留任何条目
     * @return 保留的最近条目（时间顺序：旧 → 新）
     */
    fun merge(
        existing: List<CustomEmojiItem>,
        newItem: CustomEmojiItem,
        limit: Int = CustomEmojiProtocol.MAX_ITEMS_PER_PACK
    ): List<CustomEmojiItem> {
        if (limit <= 0) return emptyList()
        // 同一内容再次复制：不新增条目，只把它移到末尾（最近使用）
        val withoutDuplicate = existing.filterNot { it.file == newItem.file }
        val merged = withoutDuplicate + newItem
        return if (merged.size > limit) merged.takeLast(limit) else merged
    }

    /** 需要保留的文件名集合，供调用方清理孤儿文件（含 manifest 外的杂项）。 */
    fun keepFiles(items: List<CustomEmojiItem>): Set<String> =
        items.mapTo(HashSet(items.size)) { it.file }
}
