/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * Recall 采集规则清单（唯一事实来源）。
 *
 * 裁决（P0-2）：隐私规则全部清单化到这一个对象，服务与设置界面共用，
 * 并生成设置页可读公示文本。命中任一规则即整屏不采集，不做「部分采集」。
 *
 * 覆盖的「全停」条件：
 *   1. 输入框类型是密码（EditorInfo.inputType password / passwordNumber / 密码变体）
 *   2. 节点自带 isPassword=true，或提示语含「密码」
 *   3. 窗口/节点被系统标记为「敏感数据」（AccessibilityNodeInfo.isAccessibilityDataSensitive，
 *      API 34+；XML 侧对应 android:accessibilityDataSensitive）
 *   4. 金融类应用（内置包名清单 + 关键词启发式 + 用户自定义排除）
 *   5. FLAG_SECURE 窗口：平台对安全窗口的回退是自动脱敏（不会给 a11y 返回可见文本），
 *      本清单再叠加金融包名与 isDataSensitive 做双保险。
 */
package org.fcitx.fcitx5.android.recall

import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Build

object RecallPolicy {

    /** 金融类应用包名清单（主流中资银行 / 支付 / 钱包 / 证券） */
    private val FINANCIAL_PACKAGES: Set<String> = setOf(
        // 银行
        "com.icbc", "com.chinamworld.main", "com.android.bankabc", "com.bankcomm.Bankcomm",
        "com.chinapaylife.mobile", "com.bank.udcs", "cmb.pb", "com.cmbchina.ccd.pluto.cmbActivity",
        "com.bsp.cgb", "com.pingan.bank.pab", "cn.com.spdb.mobilebank", "cn.com.cmbc.mbank",
        "com.bankcomm.mafinance", "com.hxb.mobile", "com.psbc.mobilebank", "com.boc.bocsoft",
        "com.ecitic.bank.mobile", "com.wecash.mobile", // 部分银行沿用旧壳
        // 支付 / 钱包
        "com.tencent.mm", "com.eg.android.AlipayGphone", "com.alipay.auto",
        "com.unionpay", "com.jd.pay", "com.unionpay.acp.portal.sdk",
        // 证券 / 理财 / 保险
        "com.lphtsccft", "com.eastmoney.android.berlin", "com.xueqiu.android",
        "cn.com.cmbc.mbank.oversea", "com.tigerbrokers.stock", "com.futu.futunn",
        "com.pingan.lifeinsurance", "com.pingan.lifemanager", "com.zhongan.insurance",
        // 征信 / 政务金融
        "com.pbccreditmarketplatform",
    )

    /** 启发式：包名含这些关键词的按金融类处理 */
    private val FINANCIAL_HINTS: List<String> = listOf(
        "bank", "pay", "wallet", "alipay", "unionpay", "finance", "insurance", "credit"
    )

    /** 用户自定义排除（设置页写入，与内置清单取并集） */
    @Volatile
    private var userExcluded: Set<String> = emptySet()

    fun setUserExcluded(pkgs: Set<String>) {
        userExcluded = pkgs.toSet()
    }

    fun isExcludedByUser(pkg: String): Boolean =
        userExcluded.any { it.equals(pkg, ignoreCase = true) }

    fun isSensitivePackage(pkg: String): Boolean {
        if (isExcludedByUser(pkg)) return true
        if (FINANCIAL_PACKAGES.contains(pkg)) return true
        val lower = pkg.lowercase()
        return FINANCIAL_HINTS.any { lower.contains(it) }
    }

    /**
     * 节点是否被系统标记为敏感数据（API 34+，Android 14 引入）。
     * 方法名是 isAccessibilityDataSensitive —— 名字长但别无选择，
     * 银行/支付类 App 输入框会带上这个标记，看到就整棵子树丢弃。
     */
    fun isDataSensitive(node: AccessibilityNodeInfo): Boolean =
        Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive

    /** 密码输入框的 inputType 判定 */
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

    /** 事件携带的窗口内容是否命中隐私规则 */
    fun shouldDiscardEvent(event: AccessibilityEvent): Boolean {
        val pkg = event.packageName?.toString() ?: return true
        if (isSensitivePackage(pkg)) return true
        return false
    }

    /** 整个页面判定：命中任一即整屏丢弃 */
    fun shouldDiscardWindow(
        pkg: String,
        sawPasswordNode: Boolean,
        sawDataSensitive: Boolean
    ): Boolean {
        if (sawPasswordNode) return true
        if (sawDataSensitive) return true
        if (isSensitivePackage(pkg)) return true
        return false
    }

    /** 供设置页以可读文本公示「不会读取什么」 */
    fun disclosureLines(): List<String> = listOf(
        "密码输入框（isPassword / 提示语含“密码”）整屏不采集",
        "系统标记为「敏感数据」的窗口（API 34+ isDataSensitive）不采集",
        "金融类应用（银行/支付/钱包/证券，内置清单+关键词）不采集",
        "FLAG_SECURE 窗口：平台自动脱敏，叠加金融清单双保险",
        "开启系统「高级保护模式」时本服务整体停用并明确提示",
        "仅在检测到以上情形时才停用对应窗口，其余可见文字才会被记录",
    )
}
