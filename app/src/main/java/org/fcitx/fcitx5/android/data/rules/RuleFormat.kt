/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 */
package org.fcitx.fcitx5.android.data.rules

import java.util.Locale

/**
 * MBM 统一规则 / 词库格式识别。
 *
 * 多家输入方案的「规则」在 MBM 中统一归一到同一导入入口：
 *  - fcitx5-android：fcitx5 自带方案 + libime 拼音/码表词典 + fcitx5-rime 插件
 *  - 第三方 Rime 系方案：**基于 Rime 定制 / 构建**，方案与词典均为 Rime 格式
 *
 * 因此 MBM 只要完整支持 Rime 方案/词典 + fcitx5 原生词典格式，即可覆盖三家规则；
 * 界面上不再按「应用」区分入口，而是按文件本身的格式与来源标签展示。
 */
enum class RuleFormat(
    val displayName: String,
    val sourceTag: String,
    val target: InstallTarget,
    val description: String
) {
    RIME_PACK(
        "Rime 方案包", "Rime 系方案", InstallTarget.RIME,
        "含 schema.yaml 的方案压缩包（第三方导出的均为 Rime 方案）"
    ),
    RIME_SCHEMA(
        "Rime 方案文件", "Rime", InstallTarget.RIME,
        "schema.yaml —— 输入方案定义"
    ),
    RIME_DICT(
        "Rime 词典文件", "Rime", InstallTarget.RIME,
        "*.dict.yaml —— Rime 词库"
    ),
    RIME_SCHEMA_ZIP(
        "Rime 数据压缩包", "Rime", InstallTarget.RIME,
        "包含多个 Rime 数据文件的 zip"
    ),
    FCITX5_PINYIN(
        "fcitx5 拼音词典", "fcitx5", InstallTarget.PINYIN,
        ".dict / .txt / .scel —— libime 拼音词库（含细胞词库 scel）"
    ),
    FCITX5_TABLE_ZIP(
        "fcitx5 码表方案", "fcitx5", InstallTarget.TABLE,
        "含 .conf + .dict 的码表输入法包（五笔、仓颉、笔画等）"
    ),
    PLAIN_WORDLIST(
        "纯文本词库", "自定义", InstallTarget.RIME,
        "每行「词语<Tab>拼音」或「编码<空格>词语」，自动转为 Rime 词典"
    ),
    UNKNOWN(
        "未能识别", "未知", InstallTarget.UNKNOWN,
        "无法判定格式，可尝试手动转换为 Rime 词典后再导入"
    );

    enum class InstallTarget { RIME, PINYIN, TABLE, UNKNOWN }

    companion object {

        /** 支持的文件扩展名（用于文件选择器 MIME/扩展名过滤展示） */
        val supportedExtensions = arrayOf(
            "zip", "yaml", "yml", "txt", "dict", "scel", "conf"
        )

        /**
         * 依据文件名 + 内容嗅探识别格式。
         * @param head 文件头部若干 KB 的文本内容（二进制则为 null）
         * @param children zip 内顶层文件名列表（非 zip 为空）
         */
        fun detect(
            fileName: String,
            isZip: Boolean,
            head: String?,
            children: List<String> = emptyList()
        ): RuleFormat {
            val lower = fileName.lowercase(Locale.US)

            if (isZip) {
                val names = children.map { it.lowercase(Locale.US) }
                val hasSchema = names.any { it.endsWith("schema.yaml") }
                val hasDict = names.any { it.endsWith("dict.yaml") }
                val hasTableConf = names.any { it.endsWith(".conf") } &&
                    names.any { it.endsWith(".dict") || it.endsWith(".txt") }
                return when {
                    hasSchema -> RIME_PACK
                    hasDict || names.any { it.endsWith(".yaml") } -> RIME_SCHEMA_ZIP
                    hasTableConf -> FCITX5_TABLE_ZIP
                    else -> UNKNOWN
                }
            }

            return when {
                lower.endsWith(".scel") -> FCITX5_PINYIN
                lower.endsWith(".dict") -> FCITX5_PINYIN
                lower.endsWith(".conf") -> FCITX5_TABLE_ZIP
                lower.endsWith(".yaml") || lower.endsWith(".yml") -> {
                    val h = head.orEmpty()
                    when {
                        h.contains("schema_id:") -> RIME_SCHEMA
                        h.contains("---") && h.contains("name:") && h.contains("...") -> RIME_DICT
                        h.contains("dictionary:") -> RIME_SCHEMA
                        else -> RIME_DICT
                    }
                }
                lower.endsWith(".txt") -> {
                    val h = head.orEmpty()
                    when {
                        // 拼音 .txt 导出：a ai 爱 格式
                        Regex("""^[a-zA-Z]+[ \t]+[a-zA-Z' ]+[ \t]+\S+""").containsMatchIn(h) &&
                            h.lines().count { it.isNotBlank() } > 3 -> FCITX5_PINYIN
                        h.contains("---") && h.contains("name:") -> RIME_DICT
                        else -> PLAIN_WORDLIST
                    }
                }
                else -> UNKNOWN
            }
        }
    }
}
