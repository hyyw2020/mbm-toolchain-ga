/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * Recall 采集规则清单（唯一事实来源）—— 同进程内嵌版 / E2 修订。
 *
 * 与旧版（独立 APK）的核心差异（冻结方案 E2：全局零 App 豁免）：
 *  - 彻底移除「金融类应用包名 / 关键词」豁免清单。旧版对银行 / 支付 / 钱包 /
 *    证券包名整屏不采集；E2 下**不再有任何包名级豁免**——任何应用都可能被捕获，
 *    除非命中下面的节点级 / 模式级硬规则。
 *  - 节点级（isPassword / 密码 inputType / 系统 isDataSensitive / 提示语含「密码」）
 *    与模式级（卡号 / 身份证 / 验证码正则）规则**硬编码、不可关闭**，二者均不
 *    因「某个 App 是金融类」而放宽。
 *  - 模式级命中（卡号 / 身份证 / 验证码）不实行为整屏丢弃，而是把命中的**词条**
 *    标记为 sensitive 并降权（见 RecallSegmenter 第三层过滤），其余文字照常记录。
 *  - 整屏丢弃仅发生在节点级（密码框 / 系统敏感窗口）与「系统高级保护模式」开启时。
 *
 * 三开关（总开关 / 拾词 / OCR）在 RecallService 中统一裁决，不在本对象内。
 */
package org.fcitx.fcitx5.android.recall

import android.text.InputType
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

object RecallPolicy {

    /** 节点是否被系统标记为敏感数据（API 34+，Android 14 引入）。 */
    fun isDataSensitive(node: AccessibilityNodeInfo): Boolean =
        Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive

    /** 密码输入框的 inputType 判定（含 passwordNumber / web password 等变体）。 */
    fun isPasswordInputType(inputType: Int): Boolean {
        val cls = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            )
        ) return true
        return cls == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    /**
     * 事件级过滤：E2 下不再有任何包名豁免，故事件本身不丢弃。
     * 真正的丢弃发生在窗口级（[shouldDiscardWindow]）。
     */
    fun shouldDiscardEvent(@Suppress("UNUSED_PARAMETER") event: AccessibilityEvent): Boolean = false

    /**
     * 整个页面判定（节点级硬规则）。命中任一即整屏丢弃：
     *  - 密码框被命中（isPassword / 提示语含「密码」）；
     *  - 节点被系统标记为敏感数据（isAccessibilityDataSensitive，API 34+）。
     * 注意：模式级（卡号 / 身份证 / 验证码）命中**不**在此丢弃整屏，仅降权对应词条。
     */
    fun shouldDiscardWindow(
        @Suppress("UNUSED_PARAMETER") pkg: String,
        sawPasswordNode: Boolean,
        sawDataSensitive: Boolean
    ): Boolean = sawPasswordNode || sawDataSensitive

    /**
     * 模式级命中：从一段文本中提取「卡号 / 身份证 / 验证码」等敏感词条。
     * 这些词条不丢弃整屏，但会被标记为 sensitive 并降权（不打扰、不出候选首位）。
     */
    fun findSensitiveTokens(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (pattern in RecallConfig.SENSITIVE_PATTERNS) {
            val m = pattern.toPattern().matcher(text)
            while (m.find()) out += m.group().trim()
        }
        return out.toList()
    }

    /** 供设置页以可读文本公示「不会读取什么」（E2 措辞已更新）。 */
    fun disclosureLines(): List<String> = listOf(
        "密码输入框（isPassword / 提示语含“密码”）整屏不采集",
        "系统标记为「敏感数据」的窗口（API 34+ isDataSensitive）不采集",
        "FLAG_SECURE 窗口：平台自动脱敏，叠加节点级规则双保险",
        "卡号 / 身份证 / 验证码等敏感词条自动打标降权，不出候选首位",
        "无包名级豁免：任何应用都可能被捕获，仅节点级 / 模式级硬规则约束",
        "开启系统「高级保护模式」时本服务整体停用并明确提示",
        "仅在检测到以上情形时才停用对应窗口，其余可见文字才会被记录",
    )
}
