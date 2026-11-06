/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 剪贴板图像资产提取 —— 纯数据层，零 Android 依赖，可在 JVM 单测中验证。
 *
 * 系统剪贴板里的图像资产并不总是以「声明 image 类 MIME 且携带 content URI」
 * 这一形态出现：宿主应用常把它放进纯文本短码、HTML 片段的 img 标签、
 * URI 列表文本，或只给出 content URI 而不声明 MIME。旧实现只认第一种
 * 形态，导致绝大多数复制场景直接被丢弃；本提取器负责：
 *   1. 从任意文本片段（纯文本 / URI 列表 / HTML）中挑出 content URI 候选；
 *   2. 用文件头魔数嗅探资产的真实图像格式，不信任剪贴板声明的 MIME；
 *   3. 把真实格式映射到容器允许的扩展名。
 *
 * 依据公开的图像格式签名（PNG / JPEG / GIF / WebP / BMP / ISO Base Media
 * File Format），全部为标准或开源规范。本文件不含任何第三方应用名称，
 * 不预置、不内置、不下载任何图像资产，也不解析或发起网络链接。
 */
package org.fcitx.fcitx5.android.data.emoji

object ClipboardStickerExtractor {

    private const val CONTENT_SCHEME = "content://"

    /** 单次剪贴板事件最多检查的 URI 候选数，防止恶意超量条目拖慢主线程协程。 */
    const val MAX_CANDIDATES = 8

    /** 单个 URI 文本的最大长度，超长视为异常直接截断丢弃。 */
    const val MAX_URI_LENGTH = 1024

    /**
     * 从任意文本中挑出 content URI 候选。适用于：
     *  - 纯文本里直接粘贴的 content URI；
     *  - URI 列表文本（换行分隔）；
     *  - HTML 片段里的 img src 或其它属性值。
     *
     * 只接受 content 方案；file / http / 自定义方案一律忽略（不联网、
     * 不越权读取任意本地路径）。结果保序去重，数量受 [MAX_CANDIDATES] 限制。
     */
    fun extractContentUris(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        var i = 0
        val n = raw.length
        while (i < n && out.size < MAX_CANDIDATES) {
            val start = raw.indexOf(CONTENT_SCHEME, i, ignoreCase = true)
            if (start < 0) break
            var end = start + CONTENT_SCHEME.length
            while (end < n && !isTerminator(raw[end])) end++
            val candidate = raw.substring(start, end)
            i = end.coerceAtLeast(start + CONTENT_SCHEME.length)
            if (candidate.length > MAX_URI_LENGTH) continue
            // 至少要有 authority 段，形如 content://authority/...
            val rest = candidate.substring(CONTENT_SCHEME.length)
            if (rest.isEmpty() || rest.startsWith("/")) continue
            if (seen.add(candidate)) out.add(candidate)
        }
        return out
    }

    private fun isTerminator(ch: Char): Boolean =
        ch.isWhitespace() || ch == '"' || ch == '\'' || ch == '<' || ch == '>' ||
            ch == ')' || ch == ']' || ch == '}' || ch == ',' || ch == ';' || ch == '\\'

    /**
     * 用文件头魔数嗅探真实图像 MIME；无法识别返回 null。
     * 只判定公开的位图容器签名，绝不依据扩展名或剪贴板声明。
     */
    fun sniffImageMime(head: ByteArray?): String? {
        if (head == null || head.size < 4) return null
        fun b(i: Int): Int = head[i].toInt() and 0xFF
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (head.size >= 8 && b(0) == 0x89 && b(1) == 0x50 && b(2) == 0x4E &&
            b(3) == 0x47 && b(4) == 0x0D && b(5) == 0x0A && b(6) == 0x1A && b(7) == 0x0A
        ) return "image/png"
        // JPEG: FF D8 FF
        if (b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF) return "image/jpeg"
        // GIF87a / GIF89a
        if (b(0) == 0x47 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x38) return "image/gif"
        // BMP: "BM"
        if (b(0) == 0x42 && b(1) == 0x4D) return "image/bmp"
        // WebP: "RIFF"...."WEBP"
        if (head.size >= 12 && b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 &&
            b(3) == 0x46 && b(8) == 0x57 && b(9) == 0x45 && b(10) == 0x42 && b(11) == 0x50
        ) return "image/webp"
        // ISO Base Media File Format: ...."ftyp" + brand（HEIC / HEIF / AVIF）
        if (head.size >= 12 && b(4) == 0x66 && b(5) == 0x74 && b(6) == 0x79 && b(7) == 0x70) {
            val brand = String(charArrayOf(
                b(8).toChar(), b(9).toChar(), b(10).toChar(), b(11).toChar()
            ))
            return when {
                brand.startsWith("avi") -> "image/avif"
                brand == "mif1" || brand == "msf1" || brand.startsWith("hei") ||
                    brand.startsWith("hev") -> "image/heic"
                else -> null
            }
        }
        return null
    }

    /** 真实图像 MIME → 容器允许的扩展名（含点）。未知一律回退 PNG。 */
    fun extensionForMime(mime: String?): String = when (mime) {
        "image/gif" -> ".gif"
        "image/webp" -> ".webp"
        "image/jpeg" -> ".jpg"
        "image/bmp" -> ".bmp"
        "image/heic" -> ".heic"
        "image/heif" -> ".heif"
        "image/avif" -> ".avif"
        "image/apng" -> ".apng"
        else -> ".png"
    }

    /** 嗅探所需的文件头字节数。 */
    const val SNIFF_BYTES = 16
}
