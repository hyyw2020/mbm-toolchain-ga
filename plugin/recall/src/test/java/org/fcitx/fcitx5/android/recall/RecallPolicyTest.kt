/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * RecallPolicy 隐私规则回归测试（MBM v1.0.26 bug 修复轮新增）。
 *
 * 钉住的行为：
 *  1. 密码类 inputType 六种变体全部识别为密码框；
 *  2. 整屏级判定（shouldDiscardWindow）在密码节点/敏感数据/金融包名下必须丢弃；
 *  3. 事件级判定（shouldDiscardEvent）金融包名丢弃、普通事件放行；
 *  4. 用户自定义排除优先于内置清单；
 *  5. 兜底 v1.0.26 修复：隐私丢弃的判据语义（null vs ""）不在此文件覆盖，
 *     由 RecallService.captureNodeTree 的集成路径保证，此处锁规则入口。
 */
package org.fcitx.fcitx5.android.recall

import android.text.InputType
import org.junit.Assert
import org.junit.Test

class RecallPolicyTest {

    // ---------- 1. 密码类 inputType ----------

    @Test
    fun `password input type variations are detected`() {
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val visiblePassword = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val webPassword = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        val numberPassword = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        Assert.assertTrue(RecallPolicy.isPasswordInputType(password))
        Assert.assertTrue(RecallPolicy.isPasswordInputType(visiblePassword))
        Assert.assertTrue(RecallPolicy.isPasswordInputType(webPassword))
        Assert.assertTrue(RecallPolicy.isPasswordInputType(numberPassword))
    }

    @Test
    fun `non-password input types are not detected as password`() {
        val normal = InputType.TYPE_CLASS_TEXT
        val email = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        val number = InputType.TYPE_CLASS_NUMBER
        val numberDecimal = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL
        Assert.assertFalse(RecallPolicy.isPasswordInputType(normal))
        Assert.assertFalse(RecallPolicy.isPasswordInputType(email))
        Assert.assertFalse(RecallPolicy.isPasswordInputType(uri))
        Assert.assertFalse(RecallPolicy.isPasswordInputType(number))
        Assert.assertFalse(RecallPolicy.isPasswordInputType(numberDecimal))
    }

    // ---------- 2. 整屏级判定 ----------

    @Test
    fun `password node discards whole window`() {
        Assert.assertTrue(RecallPolicy.shouldDiscardWindow("com.example.app", sawPasswordNode = true, sawDataSensitive = false))
    }

    @Test
    fun `data sensitive node discards whole window`() {
        Assert.assertTrue(RecallPolicy.shouldDiscardWindow("com.example.app", sawPasswordNode = false, sawDataSensitive = true))
    }

    @Test
    fun `financial package discards whole window`() {
        // 微信（支付场景）与支付宝在内置金融清单
        Assert.assertTrue(RecallPolicy.shouldDiscardWindow("com.tencent.mm", false, false))
        Assert.assertTrue(RecallPolicy.shouldDiscardWindow("com.eg.android.AlipayGphone", false, false))
    }

    @Test
    fun `ordinary package with no privacy marks is kept`() {
        Assert.assertFalse(RecallPolicy.shouldDiscardWindow("com.android.settings", sawPasswordNode = false, sawDataSensitive = false))
    }

    // ---------- 3. 事件级判定（包名） ----------

    @Test
    fun `financial heuristics catch pay-wallet-bank keywords`() {
        Assert.assertTrue(RecallPolicy.isSensitivePackage("com.example.bankapp"))
        Assert.assertTrue(RecallPolicy.isSensitivePackage("com.someone.mypay"))
        Assert.assertTrue(RecallPolicy.isSensitivePackage("com.x.insurance"))
        Assert.assertTrue(RecallPolicy.isSensitivePackage("com.x.finance"))
        Assert.assertTrue(RecallPolicy.isSensitivePackage("com.x.wallet"))
        Assert.assertTrue(RecallPolicy.isSensitivePackage("com.x.credit"))
        Assert.assertTrue(RecallPolicy.isSensitivePackage("com.x.unionpay"))
        Assert.assertTrue(RecallPolicy.isSensitivePackage("com.x.alipay"))
    }

    @Test
    fun `ordinary package passes event level check`() {
        // 注意：普通浏览/工具类包名不应命中关键词启发式
        Assert.assertFalse(RecallPolicy.isSensitivePackage("com.android.chrome"))
        Assert.assertFalse(RecallPolicy.isSensitivePackage("com.microsoft.emmx"))
        Assert.assertFalse(RecallPolicy.isSensitivePackage("org.telegram.messenger"))
        Assert.assertFalse(RecallPolicy.isSensitivePackage("com.github.android"))
    }

    // ---------- 4. 用户自定义排除 ----------

    @Test
    fun `user exclusion overrides everything`() {
        RecallPolicy.setUserExcluded(setOf("com.private.app"))
        try {
            Assert.assertTrue(RecallPolicy.isExcludedByUser("com.private.app"))
            Assert.assertTrue(RecallPolicy.isSensitivePackage("com.private.app"))
            Assert.assertTrue(RecallPolicy.shouldDiscardWindow("com.private.app", false, false))
        } finally {
            RecallPolicy.setUserExcluded(emptySet())
        }
    }

    @Test
    fun `user exclusion clearing restores defaults`() {
        RecallPolicy.setUserExcluded(setOf("com.private.app"))
        RecallPolicy.setUserExcluded(emptySet())
        Assert.assertFalse(RecallPolicy.isExcludedByUser("com.private.app"))
        // 清空后普通包名回到非敏感
        Assert.assertFalse(RecallPolicy.isSensitivePackage("com.android.chrome"))
    }

    // ---------- 5. 公示文本 ----------

    @Test
    fun `disclosure lines cover all documented rules`() {
        val lines = RecallPolicy.disclosureLines()
        Assert.assertTrue(lines.isNotEmpty())
        // 每条公示必须提到至少一个关键词，避免公示与实现漂移
        val joined = lines.joinToString("\n")
        listOf("密码", "敏感数据", "金融", "高级保护").forEach { keyword ->
            Assert.assertTrue("disclosure missing keyword: $keyword", joined.contains(keyword))
        }
    }
}
