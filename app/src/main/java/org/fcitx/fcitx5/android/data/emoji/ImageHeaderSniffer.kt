/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 增量图像文件头嗅探 —— 纯数据层，零 Android 依赖，可在 JVM 单测中验证。
 *
 * 真 bug 背景：v1.0.75 的 `importClipboardImage` 只在**第一次** read 时把
 * 字节拷进固定长 16 的数组就立刻嗅探，而 `InputStream.read(byte[])` 允许
 * 返回少于请求长度的任意字节数（ContentProvider 基于 pipe / 分块写入时很常见），
 * 甚至可能返回 0。此时 PNG 的 8 字节签名只填进 4 字节、其余为 0，
 * `sniffImageMime` 又用「数组长度」而非「有效字节数」判断，于是真实图像被
 * 判成非图像直接丢弃 —— 表现为「复制表情毫无反应」。
 *
 * 本类把「累积文件头」与「判定」彻底解耦：逐段喂入，直到收满或流结束，
 * 再用**有效字节数**做判定。零 Android API，行为可由纯 JVM 单测覆盖。
 */
package org.fcitx.fcitx5.android.data.emoji

class ImageHeaderSniffer(
    private val capacity: Int = ClipboardStickerExtractor.SNIFF_BYTES
) {
    private val buffer = ByteArray(capacity)
    private var filled = 0

    /** 已累积的有效字节数（≤ capacity）。 */
    val validBytes: Int get() = filled

    /** 内部缓冲区：仅前 [validBytes] 个字节有效，其余恒为 0；调用方只读。 */
    val header: ByteArray get() = buffer

    /** 是否已收满判定所需字节。 */
    val isFull: Boolean get() = filled >= capacity

    /**
     * 累积一次 read 的结果。
     * `len <= 0`（部分实现会返回 0）时不推进，避免把「空读」当成文件头、
     * 也不会污染已累积的真实字节。越界的 off/len 一律收敛到合法范围。
     */
    fun accept(buf: ByteArray, off: Int = 0, len: Int = buf.size) {
        if (len <= 0 || filled >= capacity) return
        val start = off.coerceIn(0, buf.size)
        val available = (buf.size - start).coerceAtLeast(0)
        val n = minOf(len, available, capacity - filled)
        if (n <= 0) return
        System.arraycopy(buf, start, buffer, filled, n)
        filled += n
    }

    /** 用已累积的有效字节做最终判定；无法识别返回 null。 */
    fun result(): String? = ClipboardStickerExtractor.sniffImageMime(buffer, filled)
}
