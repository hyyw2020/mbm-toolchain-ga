/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * CrashLogSanitizer 的单元测试（MBM v1.0.26 / P2-7）。
 * 纯 JVM 测试，不需要 Android 设备 —— 正则部分是本类唯一有风险的逻辑。
 */

package org.fcitx.fcitx5.android

import org.fcitx.fcitx5.android.utils.CrashLogSanitizer
import org.junit.Assert
import org.junit.Test

class CrashLogSanitizerTest {

    @Test
    fun `masks user text in input path frames`() {
        val stack = """
            java.lang.RuntimeException: boom
                at org.fcitx.fcitx5.android.input.FcitxInputMethodService.commitText("我把银行卡号发群里了", 1)
                at org.fcitx.fcitx5.android.input.FcitxInputMethodService.handleFcitxEvent(FcitxEvent${'$'}CommitStringEvent)
        """.trimIndent()
        val out = CrashLogSanitizer.sanitize(stack)
        Assert.assertFalse("输入路径帧里的用户文本必须被掩码", out.contains("我把银行卡号发群里了"))
        Assert.assertTrue("方法名必须保留，否则失去定位价值", out.contains("commitText"))
        Assert.assertTrue("应留下长度信息", out.contains("<masked:"))
    }

    @Test
    fun `masks FormattedText toString shape`() {
        val stack = """
            java.lang.IllegalStateException: bad state
                at org.fcitx.fcitx5.android.input.FcitxInputMethodService.updateComposingText(FormattedText(strings=[nihao, 你好世界]))
        """.trimIndent()
        val out = CrashLogSanitizer.sanitize(stack)
        Assert.assertFalse(out.contains("你好世界"))
        Assert.assertFalse(out.contains("nihao"))
        Assert.assertTrue(out.contains("strings=[<masked>]"))
    }

    @Test
    fun `keeps short ascii literals outside sensitive frames`() {
        val stack = """
            java.lang.NullPointerException
                at org.fcitx.fcitx5.android.input.KeyboardManager.attach("pinyin", 2)
        """.trimIndent()
        val out = CrashLogSanitizer.sanitize(stack)
        Assert.assertTrue("短 ASCII 字面量是诊断依据，必须原样保留", out.contains("\"pinyin\""))
    }

    @Test
    fun `masks long ascii literals even outside sensitive frames`() {
        val stack = """
            java.lang.IllegalArgumentException
                at org.fcitx.fcitx5.android.data.SmartDictManager.load("file:///storage/emulated/0/Download/user_backup_v3.dict")
        """.trimIndent()
        val out = CrashLogSanitizer.sanitize(stack)
        Assert.assertFalse(out.contains("user_backup_v3.dict"))
        Assert.assertTrue(out.contains("<masked:"))
    }

    @Test
    fun `empty input passes through`() {
        Assert.assertEquals("", CrashLogSanitizer.sanitize(""))
    }

    @Test
    fun `does not touch method names or line numbers`() {
        val stack = "java.lang.RuntimeException\n    at foo.Bar.baz(Bar.java:42)"
        Assert.assertEquals(stack, CrashLogSanitizer.sanitize(stack))
    }
}
