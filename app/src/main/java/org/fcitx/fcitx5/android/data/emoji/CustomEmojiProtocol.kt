/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 自定义图像表情（sticker）容器协议 —— 纯数据层，零 Android 依赖。
 *
 * 本输入法**不携带、不预置、不分发**任何第三方专有表情资产。它只提供
 * 一个开放容器：用户运行时自行导入 `image/xxx` 位图资产，资产落到应用
 * 私有目录，附一份可审计的清单（pack.json）。资产的著作权与来源由
 * 导入者负责，输入法仅做本地索引、渲染与富媒体提交。
 *
 * 富媒体提交遵循 Android Rich Content API（InputConnection.commitContent）
 * 与 AndroidX Core 的 InputContentInfoCompat / FileProvider；清单序列化
 * 使用 JetBrains kotlinx.serialization（Apache-2.0）。上述均为标准或
 * 开源组件，规范与许可标识以 SPDX 表达。
 *
 * 安全边界（全部由纯函数实现，可在 JVM 单测中验证）：
 *   - 文件名净化，阻断路径穿越与清单投毒
 *   - 扩展名 / MIME 白名单，拒绝可执行与非图像类型
 *   - 单文件大小、条目数、导入总量上限，阻断解压炸弹
 *   - 清单反序列化逐条容错，损坏条目不得使整包不可用
 */
package org.fcitx.fcitx5.android.data.emoji

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CustomEmojiItem(
    /** 私有目录内的文件名（已净化，绝不含路径分隔符）。 */
    val file: String,
    /** 面板显示名 / 富媒体提交时的内容描述。 */
    val label: String,
    /** `image/xxx` MIME 类型。 */
    val mime: String,
    val size: Long = 0L
)

@Serializable
data class CustomEmojiPack(
    val schema: Int = CustomEmojiProtocol.SCHEMA,
    val id: String,
    val name: String,
    val createdMs: Long = 0L,
    val items: List<CustomEmojiItem> = emptyList()
)

object CustomEmojiProtocol {

    const val SCHEMA = 1
    const val MANIFEST_NAME = "pack.json"

    /** 面板条目 token 前缀。使用 Unicode 私用区起始码点，避免与用户真实文本冲突。 */
    const val TOKEN_PREFIX = "\uE000sticker:"

    const val MAX_ITEM_BYTES = 8L * 1024 * 1024
    const val MAX_IMPORT_BYTES = 64L * 1024 * 1024
    const val MAX_ITEMS_PER_PACK = 500
    const val MAX_NAME_LENGTH = 120
    const val MAX_LABEL_LENGTH = 60

    /** 允许的图像类型。键为小写扩展名（含点）。 */
    private val MIME_BY_EXT = mapOf(
        ".png" to "image/png",
        ".apng" to "image/apng",
        ".jpg" to "image/jpeg",
        ".jpeg" to "image/jpeg",
        ".webp" to "image/webp",
        ".gif" to "image/gif",
        ".bmp" to "image/bmp",
        ".heic" to "image/heic",
        ".heif" to "image/heif",
        ".avif" to "image/avif"
    )

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** 扩展名 → MIME；不在白名单返回 null。 */
    fun mimeFor(fileName: String): String? =
        MIME_BY_EXT[fileName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }.lowercase()]

    fun isAllowedMime(mime: String): Boolean =
        mime.startsWith("image/") && mime in MIME_BY_EXT.values

    /**
     * 文件名净化：只保留字母、数字、点、下划线、连字符与 CJK 统一表意文字，
     * 其余（含路径分隔符与控制字符）一律替换为下划线。返回 null 表示拒绝。
     */
    fun safeFileName(raw: String): String? {
        if (raw.isEmpty() || raw.length > MAX_NAME_LENGTH) return null
        // 显式拒绝任何路径语义
        if (raw.contains('/') || raw.contains('\\') || raw.contains('\u0000')) return null
        val sb = StringBuilder(raw.length)
        raw.forEach { ch ->
            val ok = ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' ||
                ch == '.' || ch == '_' || ch == '-' || ch in '\u4E00'..'\u9FFF'
            sb.append(if (ok) ch else '_')
        }
        val name = sb.toString()
        if (name.isEmpty() || name == "." || name == ".." || name.startsWith("..")) return null
        if (mimeFor(name) == null) return null
        return name
    }

    /** 包 id 只允许 ASCII 安全字符，作为私有目录名与 token 段。 */
    fun safePackId(raw: String): String? {
        if (raw.isEmpty() || raw.length > 64) return null
        if (!raw.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }) return null
        return raw
    }

    fun labelFromFileName(fileName: String): String =
        fileName.substringBeforeLast('.', fileName).take(MAX_LABEL_LENGTH).ifBlank { "sticker" }

    fun token(packId: String, file: String): String = "$TOKEN_PREFIX$packId/$file"

    /** 解析面板 token，严格校验两段标识符，防止构造越界路径。 */
    fun parseToken(text: String): Pair<String, String>? {
        if (!text.startsWith(TOKEN_PREFIX)) return null
        val body = text.removePrefix(TOKEN_PREFIX)
        val slash = body.indexOf('/')
        if (slash <= 0 || slash == body.length - 1) return null
        val packId = safePackId(body.substring(0, slash)) ?: return null
        val file = safeFileName(body.substring(slash + 1)) ?: return null
        return packId to file
    }

    fun encodeManifest(pack: CustomEmojiPack): String = json.encodeToString(pack)

    /**
     * 清单解码：逐条过滤非法项，任何解析异常返回 null（由调用方决定是否
     * 回退到目录扫描重建）。绝不把未净化的文件名交给文件系统。
     */
    fun decodeManifest(text: String): CustomEmojiPack? {
        val raw = runCatching { json.decodeFromString<CustomEmojiPack>(text) }.getOrNull() ?: return null
        if (raw.schema != SCHEMA) return null
        val id = safePackId(raw.id) ?: return null
        val items = raw.items.asSequence()
            .mapNotNull { item ->
                val file = safeFileName(item.file) ?: return@mapNotNull null
                val mime = if (isAllowedMime(item.mime)) item.mime else mimeFor(file) ?: return@mapNotNull null
                CustomEmojiItem(
                    file = file,
                    label = item.label.take(MAX_LABEL_LENGTH).ifBlank { labelFromFileName(file) },
                    mime = mime,
                    size = item.size.coerceIn(0L, MAX_ITEM_BYTES)
                )
            }
            .distinctBy { it.file }
            .take(MAX_ITEMS_PER_PACK)
            .toList()
        return raw.copy(id = id, name = raw.name.take(MAX_NAME_LENGTH), items = items)
    }
}
