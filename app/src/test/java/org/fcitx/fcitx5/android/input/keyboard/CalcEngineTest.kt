/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * v1.0.83 回归单测：CalcEngine 全局计算器候选的「后缀 %」运算符。
 *
 * 真 bug：旧 [CalcEngine.hasOperator] 只检查 + - * / × ÷，漏掉了文档文法
 * `postfix := primary '%'？（n% = n/100）` 中的后缀 %。于是「10%」「50%」这类
 * 仅含后缀 % 的合法算式被「无运算符」闸门整体拒掉、返回 null，而解析器本身
 * 早已正确支持 %（parsePostfix 做 n/100）。修复后 % 纳入运算符集合。
 */
package org.fcitx.fcitx5.android.input.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalcEngineTest {

    @Test
    fun percentIsEvaluated() {
        // 仅含后缀 % 的算式此前被 hasOperator 闸门误拒、返回 null
        assertEquals("0.1", CalcEngine.evaluate("10%"))
        assertEquals("0.5", CalcEngine.evaluate("50%"))
        assertEquals("0.03", CalcEngine.evaluate("3%"))
        // % 与二元运算符混合：先 n/100 再参与运算
        assertEquals("5", CalcEngine.evaluate("100*5%"))      // 100 * 0.05
        assertEquals("199.5", CalcEngine.evaluate("200-50%")) // 200 - 0.5
    }

    @Test
    fun doublePercentStillRejected() {
        // 5%% 仍属非法（连续运算符 / 多余后缀），与修复前一致
        assertNull(CalcEngine.evaluate("10%%"))
    }

    @Test
    fun baselineArithmeticUnaffected() {
        assertEquals("579", CalcEngine.evaluate("123+456"))
        assertEquals("14", CalcEngine.evaluate("2+3*4"))
        assertEquals("20", CalcEngine.evaluate("(2+3)*4"))
        assertEquals("14", CalcEngine.evaluate("2*(3+4)"))
        assertEquals("75", CalcEngine.evaluate("100-50+25"))
        assertEquals("3", CalcEngine.evaluate("((1+2))"))
        assertEquals("-2", CalcEngine.evaluate("-5+3"))
        assertEquals("2.5", CalcEngine.evaluate("10/4"))
        assertEquals("0.3", CalcEngine.evaluate("0.1+0.2"))
        assertEquals("4", CalcEngine.evaluate("|5-9|"))
        // 非法输入依旧返回 null
        assertNull(CalcEngine.evaluate("1/0"))
        assertNull(CalcEngine.evaluate("1.2.3"))
        assertNull(CalcEngine.evaluate(".5"))
        assertNull(CalcEngine.evaluate("5."))
        assertNull(CalcEngine.evaluate("878--4"))
        assertNull(CalcEngine.evaluate("5*-3"))
        assertNull(CalcEngine.evaluate("abc"))
        assertNull(CalcEngine.evaluate("(1+2"))
        assertNull(CalcEngine.evaluate("1+2)"))
    }
}
