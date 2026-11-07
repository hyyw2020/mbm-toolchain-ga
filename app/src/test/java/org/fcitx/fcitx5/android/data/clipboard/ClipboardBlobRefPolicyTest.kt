/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: MBM Contributors
 */
package org.fcitx.fcitx5.android.data.clipboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.0.81：编辑 blob 条目后的引用计数回收判定回归。
 *
 * 核心不变量：blob 内容寻址共享，**只要还有任意一行（含软删可 undo 的行）
 * 引用旧 sha，就绝不能物理删除文件**，否则其它条目的全文永久丢失。
 */
class ClipboardBlobRefPolicyTest {

    private val old = "a".repeat(64)
    private val newer = "b".repeat(64)

    @Test
    fun noOldBlobNeverRecycles() {
        // 内联条目编辑后仍是内联 / 升级为 blob：都没有旧文件可回收
        assertFalse(ClipboardBlobRefPolicy.shouldRecycle(null, null, emptySet()))
        assertFalse(ClipboardBlobRefPolicy.shouldRecycle(null, newer, setOf(newer)))
    }

    @Test
    fun unchangedContentKeepsItsBlob() {
        // 编辑后仍指向同一 blob（内容未变或改回原内容）：当前行还在用
        assertFalse(ClipboardBlobRefPolicy.shouldRecycle(old, old, setOf(old)))
        assertFalse(ClipboardBlobRefPolicy.shouldRecycle(old, old, emptySet()))
    }

    @Test
    fun sharedBlobMustNotBeDeleted() {
        // 真 bug 主场景：另一条存活条目仍引用同一 sha（sensitive 标记不同 /
        // importFromUri 与系统复制两条路径），编辑本行不得删掉共享文件
        assertFalse(ClipboardBlobRefPolicy.shouldRecycle(old, null, setOf(old)))
        assertFalse(ClipboardBlobRefPolicy.shouldRecycle(old, newer, setOf(old, newer)))
    }

    @Test
    fun softDeletedRowStillCountsAsReference() {
        // 软删的条目仍可 undo 恢复，它引用的 blob 必须在 liveShas 里被保留
        assertFalse(ClipboardBlobRefPolicy.shouldRecycle(old, null, setOf(old)))
    }

    @Test
    fun unreferencedBlobIsRecycledToAvoidLeak() {
        // 修复不能变成「永不回收」：确实无人引用时仍要正常删除，避免空间泄漏
        assertTrue(ClipboardBlobRefPolicy.shouldRecycle(old, null, emptySet()))
        assertTrue(ClipboardBlobRefPolicy.shouldRecycle(old, newer, setOf(newer)))
    }

    @Test
    fun onlyTheOldRefMattersNotOtherShas() {
        // 集合里有别的 sha 不影响旧 blob 的回收判定
        assertTrue(ClipboardBlobRefPolicy.shouldRecycle(old, newer, setOf("c".repeat(64))))
        assertFalse(ClipboardBlobRefPolicy.shouldRecycle(old, newer, setOf(old, "c".repeat(64))))
    }
}
