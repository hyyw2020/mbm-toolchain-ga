/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: MBM Contributors
 *
 * 剪贴板文本逐字保真策略 —— 纯数据层，零 Android 依赖，可在 JVM 单测中验证。
 */
package org.fcitx.fcitx5.android.data.clipboard

import java.security.MessageDigest

/**
 * MBM v1.0.77：把「剪贴板文本写入历史库」的保真语义集中到一处。
 *
 * 旧实现在 [ClipboardManager] 的 `insertEntry` / `updateText` 里统一
 * `text.trim()` 后再落库，带来两个真实缺陷：
 *  1. 用户复制「  abc\n\n」历史里变成「abc」，首尾空白与换行被静默吃掉；
 *  2. 哈希按 trim 后文本算，「 A 」与「A」被判为同一条，却只保存了裁剪后的
 *     「A」——去重口径与存储内容不一致。
 *
 * 三条不变量（单测逐条固化）：
 *  - 只有**全空白**条目才丢弃（[shouldDrop]），非空白一律保留；
 *  - 落库文本恒等于原文（[stored]），不做任何裁剪/规范化；
 *  - 去重与 sha256 列统一按**原文**计算（[sha256]），与 blob 流式写入
 *    （[ClipboardBlobStore.write] 按原始字节算哈希）口径一致。
 *
 * 注意：这与「系统剪贴板监听到的内容本来就是 String」并不冲突——保真指的是
 * IME 拿到什么就存什么，不额外裁剪；平台层能否放下超大文本是另一条边界，
 * 由 [ClipboardCapacityPolicy] 与 blob 外置负责。
 */
object ClipboardTextFidelity {

    /** 全空白（含空串）才丢弃；trim 仅用于判定，绝不参与落库。 */
    fun shouldDrop(text: String): Boolean = text.trim().isEmpty()

    /** 落库文本 = 原文，逐字保真。 */
    fun stored(text: String): String = text

    /** 去重与 sha256 列统一按原文 UTF-8 计算。 */
    fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
