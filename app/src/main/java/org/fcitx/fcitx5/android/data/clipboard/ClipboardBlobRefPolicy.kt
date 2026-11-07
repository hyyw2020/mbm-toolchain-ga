/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: MBM Contributors
 *
 * 编辑大文本（blob）条目后，旧 blob 能否安全回收 —— 纯数据层，零 Android
 * 依赖，可在 JVM 单测中验证。
 *
 * v1.0.81 真 bug 背景：
 * [ClipboardBlobStore] 是**内容寻址**存储（同 sha256 共用同一个 .bin 文件），
 * 同一个 blob 可能被多条 DB 行引用：
 *  - 相同内容但 sensitive 标记不同的两行（findBySha 带 sensitive 参数去重）；
 *  - 软删除（deleted=1）后仍可 undo 恢复的行；
 *  - importFromUri / addText / addRecallText 与系统剪贴板监听两条写入路径
 *    对同一内容各自的去重口径不同。
 * 旧实现 [ClipboardManager.updateText] 在编辑条目时直接
 * `ClipboardBlobStore.delete(旧 blobRef)` 物理删除文件，绕过了 N01/N02 建立的
 * 「引用计数式 GC」，会**永久删除其它条目的全文**：之后 readFullText 返回
 * null，用户粘贴时只得到「条目文件丢失，无法粘贴」，且内容不可恢复。
 *
 * 正确顺序（本策略固化其判定）：
 *  1. 先更新 DB 元数据（当前行不再指向旧 blob）；
 *  2. 再取「所有仍引用 blob 的行」的 sha 集合；
 *  3. 仅当旧 sha 不在该集合、且新内容也不再使用它时才物理回收。
 *
 * 与 delete()/realDelete() 的 gcOrphanBlobs 保持同一语义，不改变任何功能。
 */
package org.fcitx.fcitx5.android.data.clipboard

object ClipboardBlobRefPolicy {

    /**
     * @param oldRef   编辑前当前行的 blobRef；null = 原本是内联小文本，无 blob 可回收
     * @param newRef   编辑后当前行的 blobRef；改小到内联时为 null
     * @param liveShas 更新元数据**之后**，所有仍引用 blob 的行的 sha256 集合
     *                 （必须含软删可 undo 的行，见 ClipboardDao.allBlobShas）
     * @return true 表示旧 blob 已无任何引用，可安全物理删除
     */
    fun shouldRecycle(oldRef: String?, newRef: String?, liveShas: Set<String>): Boolean {
        if (oldRef == null) return false        // 本来就没有 blob
        if (oldRef == newRef) return false      // 内容未变，当前行仍指向它
        return oldRef !in liveShas             // 其余行仍引用 → 必须保留
    }
}
