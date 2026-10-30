/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 *
 * MBM v1.0.31：大文本分块粘贴核心（**纯 JVM，无 Android 依赖 → 可单测**）。
 *
 * 背景：`InputConnection.commitText` 走 Binder 事务（上限约 1MB），
 * 直接把 100MB 字符串交给它必抛 TransactionTooLargeException。
 * 本对象把「流式读 → 按字节分块 → UTF-8 边界处理 → 文本块」的循环
 * 从 InputMethodService 里抽出来，Service 只负责 Android 侧调用。
 *
 * 正确性不变量（单测逐条验证）：
 *  1. 所有文本块拼接 == 原文完整解码字符串（一字不差）；
 *  2. 任何块都不含被切断的 UTF-8 字符（块间余量 carry 前移）；
 *  3. onChunk 返回 false（提交失败）时立即停止，返回值 = 已提交字节数；
 *  4. 空输入不产块。
 */
package org.fcitx.fcitx5.android.data.clipboard

import java.io.InputStream

object ClipboardPasteChunker {

    /**
     * 计算 buf[0 until len] 尾部不完整 UTF-8 序列的字节数（0 = 完整）。
     * 例：末尾是 0xE4（3 字节序列的首字节且无后续）→ 返回 1。
     */
    fun incompleteUtf8Tail(buf: ByteArray, len: Int): Int {
        var i = len - 1
        var continuation = 0
        while (i >= 0 && continuation < 4) {
            val b = buf[i].toInt() and 0xFF
            if (b and 0xC0 == 0x80) {
                continuation++
                i--
                continue
            }
            val expected = when {
                b and 0x80 == 0 -> 1      // ASCII
                b and 0xE0 == 0xC0 -> 2   // 2 字节
                b and 0xF0 == 0xE0 -> 3   // 3 字节（中文主体）
                b and 0xF8 == 0xF0 -> 4   // 4 字节
                else -> return 0          // 非法字节，当完整处理
            }
            return if (continuation + 1 < expected) continuation + 1 else 0
        }
        return 0
    }

    /**
     * 流式分块读取，把每块文本交给 [onChunk]。
     *
     * @param chunkSize 块字节数（生产用 ClipboardBlobStore.PASTE_CHUNK_BYTES=256KB）
     * @param onChunk (text, byteLen) -> 是否提交成功；返回 false 立即停止
     * @return 已成功提交的字节数（失败即停，不含失败块）
     */
    suspend fun chunks(
        input: InputStream,
        chunkSize: Int = ClipboardBlobStore.PASTE_CHUNK_BYTES,
        onChunk: suspend (text: String, byteLen: Int) -> Boolean
    ): Long {
        var committed = 0L
        val buf = ByteArray(chunkSize + 4) // +4：UTF-8 序列最大 4 字节的跨块余量
        var carry = 0
        while (true) {
            // 填满 buf[carry until carry+chunkSize]
            var read = 0
            while (read < chunkSize) {
                val n = runCatching { input.read(buf, carry + read, chunkSize - read) }
                    .getOrElse { -1 }
                if (n < 0) break
                if (n == 0) continue
                read += n
            }
            val total = carry + read
            if (total == 0) break
            val atEof = read < chunkSize
            // 非 EOF 时把尾部不完整的 UTF-8 序列留给下一轮，避免字符被切断。
            // 注意：atEof=false 时 read==chunkSize（读满），total > chunkSize ≥ 4 > 3 ≥ tail，
            // 不会出现「整块都是不完整序列」；那种情况只可能是 chunkSize<4 的病态用法，
            // 此时整体 carry 前移、下轮补读，依然正确（不会死循环：每轮至少读到 1 字节）。
            val tail = if (atEof) 0 else incompleteUtf8Tail(buf, total)
            val text = String(buf, 0, total - tail, Charsets.UTF_8)
            if (text.isNotEmpty()) {
                if (!onChunk(text, total - tail)) break
                committed += total - tail
            }
            // 余量前移
            if (tail > 0) System.arraycopy(buf, total - tail, buf, 0, tail)
            carry = tail
            if (atEof && read == 0) break
        }
        return committed
    }

    /**
     * 读取明文流末尾 [n] 字节作为「期望尾部文本」（尾部校验用，纯 JVM 可单测）。
     * 末尾字节可能切碎多字节字符，去掉前导替换符再比对。
     *
     * MBM v1.0.34：blob 文件加密后不再能 RandomAccessFile seek，改为流式
     * 一遍过 + 尾部环缓冲（内存 O(n)）。入参从 File 改为 InputStream
     * （由 ClipboardBlobStore.open 给出已解密的流）。
     */
    fun expectedTailText(input: InputStream, n: Int): String? {
        if (n <= 0) return ""
        return runCatching {
            val ring = ByteArray(n)
            var filled = 0
            var pos = 0
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                var i = 0
                while (i < read) {
                    val take = minOf(read - i, n - pos)
                    buf.copyInto(ring, pos, i, i + take)
                    pos = (pos + take) % n
                    filled = minOf(filled + take, n)
                    i += take
                }
            }
            if (filled == 0) return@runCatching ""
            // 环缓冲按读取顺序还原：pos 处是最早字节
            val ordered = if (filled < n) ring.copyOf(filled) else ByteArray(n).also { out ->
                ring.copyInto(out, 0, pos)
                ring.copyInto(out, n - pos, 0, pos)
            }
            String(ordered, Charsets.UTF_8).trimStart('�')
        }.getOrNull()
    }

    /**
     * 尾部比对（启发式）：目标输入框光标前的文本是否以期望尾部结尾。
     * 放行条件：完全结尾，或最后 16 字符结尾（目标应用对粘贴文本做过少量加工时）。
     */
    fun tailMatches(before: String, expected: String): Boolean {
        if (expected.isEmpty() || before.isEmpty()) return true
        return before.endsWith(expected) || before.endsWith(expected.takeLast(16))
    }
}
