/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * Recall 采集规则清单（唯一事实来源）—— v1.0.39 火力全开修订。
 *
 * v1.0.39 裁决：移除全部应用内封锁点（密码框/敏感窗口整屏丢弃、事件级丢弃、
 * 高级保护模式停采）。这些规则只挡得住用户自己，挡不住真正拿到设备的人——
 * 属于虚假安全。真正的安全 = 数据只存本机（SQLCipher 静态加密）+ 不联网。
 * 采集本身火力全开：任何应用的可见文字都照常捕获。
 *
 * 唯一保留的「软规则」：卡号 / 身份证 / 验证码等敏感词条打标降权
 * （不丢弃、不屏蔽，仅不在候选首位打扰——见 RecallSegmenter 第三层）。
 *
 * 唯一无法绕过的平台约束：FLAG_SECURE 窗口的系统截图由系统返回黑帧，
 * 这是 OS 层行为，与本应用无关。
 *
 * 三开关（总开关 / 拾词 / OCR）在 RecallService 中统一裁决，不在本对象内。
 */
package org.fcitx.fcitx5.android.recall

object RecallPolicy {

    /**
     * 模式级命中：从一段文本中提取「卡号 / 身份证 / 验证码」等敏感词条。
     * 这些词条**不丢弃、不屏蔽**，仅打标降权（不出候选首位），时间线与词库照常收录。
     */
    fun findSensitiveTokens(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (pattern in RecallConfig.SENSITIVE_PATTERNS) {
            val m = pattern.toPattern().matcher(text)
            while (m.find()) out += m.group().trim()
        }
        return out.toList()
    }

    /** 供设置页以可读文本公示采集规则（v1.0.39 火力全开措辞）。 */
    fun disclosureLines(): List<String> = listOf(
        "火力全开：所有应用的可见文字均照常捕获，无任何应用/窗口级封锁",
        "密码框、系统敏感窗口照常采集（应用内不再做整屏丢弃）",
        "FLAG_SECURE 窗口的系统截图由 Android 返回黑帧——这是 OS 层行为，无法也无需绕过",
        "卡号 / 身份证 / 验证码等敏感词条自动打标：照常收录，仅不在候选首位打扰",
        "无包名级豁免：任何应用都可能被捕获",
        "系统「高级保护模式」不再停用本服务（该开关只挡用户自己，属虚假安全）",
        "数据只存本机：SQLCipher 静态加密 + 不联网，这才是真正的安全",
    )
}
