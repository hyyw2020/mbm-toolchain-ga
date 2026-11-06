/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: MBM Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

/**
 * MBM v1.0.74：「拆分选词」词块打码策略。零 Android 依赖，便于 JVM 回归单测。
 *
 * 真 bug 背景：v1.0.45~73 的 [WordSelectWindow] 直接读取全局偏好
 * `clipboardMaskSensitive`（默认 true）作为整屏打码开关，未判断被打开的那条
 * 剪贴板条目本身是否被系统标记为敏感。于是默认配置下，任意普通文本进入
 * 「拆分选词」时，[WordSelectUi] 把每个词块都替换成 `•`，面板只剩一排小点点、
 * 完全无法辨认与选词。
 *
 * 正确语义与剪贴板列表（ClipboardAdapter.setEntry 的
 * `entry.sensitive && maskSensitive()`）保持一致：**仅当条目被标记为敏感、
 * 且用户开启了打码偏好时**才遮蔽词块。普通条目在任何偏好下都显示原文。
 */
object WordSelectMaskPolicy {

    /** 是否对本次「拆分选词」的词块做遮蔽。 */
    fun shouldMask(entrySensitive: Boolean, maskPrefEnabled: Boolean): Boolean =
        entrySensitive && maskPrefEnabled

    /**
     * 词块展示文本。遮蔽时用 `•` 代替原文并限制长度，避免超长词块撑破面板；
     * 不遮蔽时**原样返回**，保证中文、标点、Emoji 序列与换行都能正确辨认。
     */
    fun chipDisplayText(raw: String, mask: Boolean): String =
        if (mask) "•".repeat(raw.length.coerceAtMost(24)) else raw
}
