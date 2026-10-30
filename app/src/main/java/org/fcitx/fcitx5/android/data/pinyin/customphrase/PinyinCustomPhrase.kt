/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.customphrase

import org.fcitx.fcitx5.android.core.FcitxUtils
import kotlin.math.absoluteValue

data class PinyinCustomPhrase(
    val key: String,
    val order: Int,
    val value: String
) {
    val enabled: Boolean get() = order > 0

    fun copyEnabled(e: Boolean): PinyinCustomPhrase {
        return copy(order = (if (e) 1 else -1) * order.absoluteValue)
    }

    fun serialize() = "$key,${order.absoluteValue}=${FcitxUtils.escapeForValue(value)}"

    companion object {
        /**
         * MBM v1.0.31「词库无上限」：原 100MB **裁剪上限**已废弃（pitfalls #70），
         * 此值仅作**告警阈值**——估算落盘体积超过它时保存后提示用户，
         * 不再裁剪、不丢数据。保存失败有精确报错（条目数/字节数）。
         */
        const val STORE_WARN_BYTES = 100L * 1024 * 1024

        /**
         * 估算自定义词组数组落盘后的字节体积（UTF-8 粗略上界）。
         * 每行形如 `key,order=value` + 换行；中文按 3 字节/字上估，保证不会低估导致超限。
         */
        fun estimateBytes(items: Array<PinyinCustomPhrase>): Long =
            items.sumOf { (it.serialize().length + 1) * 3L }
    }
}
