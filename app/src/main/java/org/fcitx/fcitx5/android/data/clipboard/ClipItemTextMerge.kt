/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: MBM Contributors
 *
 * 多 Item 剪贴板文本合并策略 —— 纯数据层，零 Android 依赖，可在 JVM 单测中验证。
 */
package org.fcitx.fcitx5.android.data.clipboard

/**
 * MBM v1.0.77：修复「复制侧多 Item ClipData 只记第一项」。
 *
 * Android 的 [android.content.ClipData] 允许携带多个 Item（一次复制多段文字、
 * 多行、或标题 + 正文）。旧实现 [ClipboardEntry.fromClipData] 只读
 * `getItemAt(0)`，其余 Item 的文本被静默丢弃 —— 用户明明复制了整段内容，
 * 输入法历史里却只剩第一截，属于「复制不完整」的真缺陷。
 *
 * 约定（保持单 Item 快路径行为完全不变）：
 *  - 0 个有效文本 → null（调用方回退到 URI / Intent 派生分支）；
 *  - 1 个有效文本 → **原样返回该字符串**，不加换行、不 trim、不改任何字符；
 *  - 多个有效文本 → 按 Item 顺序以 `\n` 连接，完整保留每一段。
 *
 * 「有效」定义为非 null 且非空串；**不做 isBlank 判断**，因为纯空白 / 换行
 * 也是用户复制内容的一部分，丢弃它就破坏了逐字保真（与 v1.0.77 的
 * trim 修复同一目标）。
 */
object ClipItemTextMerge {

    fun merge(parts: List<String?>): String? {
        val valid = parts.filterNotNull().filter { it.isNotEmpty() }
        return when (valid.size) {
            0 -> null
            1 -> valid[0]
            else -> valid.joinToString(separator = "\n")
        }
    }
}
