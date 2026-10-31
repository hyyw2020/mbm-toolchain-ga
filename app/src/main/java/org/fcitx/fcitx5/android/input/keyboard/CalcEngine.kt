/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * MBM v1.0.39c：全局计算器候选 —— 本地算式求值器（严格文法版）。
 * 纯 Kotlin 标准库、零第三方依赖、纯本地计算、不联网、无任何开关（全局默认开启）。
 *
 * 文法（递归下降；BigDecimal 精确十进制运算；除法取 16 位有效数字 HALF_UP）：
 *   expr    := term (('+' | '-') term)*
 *   term    := operand (('*' | '/') operand)*
 *   operand := SIGN? postfix        —— 仅当位于式首或开括号之后才允许一个正负号
 *   postfix := primary '%'?
 *   primary := NUMBER
 *            | '(' expr ')'         —— 圆括号（第 1 层）
 *            | '[' expr ']'         —— 方括号（第 2 层）
 *            | '{' expr '}'         —— 花括号（第 3 层）
 *            | '|' expr '|'         —— 绝对值 |x|
 *
 * 「[]里的内容必须正确」硬约束（v1.0.39c 收紧）：
 *  - 仅当整段输入能被完整解析为合法算式时才给出候选，任何残缺/歧义一律不出；
 *  - 连续运算符（如 878--4、5*-3、1++2）一律拒绝——负数必须写括号 (-4)；
 *    正负号只允许出现在式首或圆括号之后，且至多一个；
 *  - 数字必须形如 `123` 或 `12.34`（拒绝 `.5` / `5.` / `1.2.3`）；
 *  - 后缀 % 至多一个（5%% 拒绝），n% = n/100；
 *  - 括号遵循高等数学规范：成对、层级清楚、由内到外 圆→方→花，
 *    层级混用（如 ([1+2])）与错配（如 (1+2]）一律拒绝；
 *  - |x| 为绝对值，可出现在任意层，内部同样受文法约束；
 *  - 除零不出结果；结果展示去除多余尾零（123+456 → "579" 而非 "579.0"）。
 *  说明：LaTeX 记法（\frac、pmatrix 等）与 ⌊⌋⌈⌉ 取整括号无法在手机键盘输入，
 *  不在支持范围；本求值器覆盖可键入的 ( ) [ ] { } | 规范子集。
 */
package org.fcitx.fcitx5.android.input.keyboard

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

object CalcEngine {

    /** 输入是否包含运算符（忽略开头的一元正负号）。 */
    fun hasOperator(s: String): Boolean =
        s.dropWhile { it == '-' || it == '+' }.any { it in "+-*/×÷" }

    /**
     * 求值整段输入。仅当输入整体是一个含运算符的合法算式时返回结果字符串，否则 null。
     */
    fun evaluate(input: String): String? {
        val s = normalize(input)
        if (s.isEmpty() || s.length > MAX_LEN) return null
        if (!hasOperator(s)) return null
        val p = Parser(s)
        return try {
            val v = p.parseExpr()
            if (p.failed || !p.atEnd()) null else format(v)
        } catch (_: Exception) {
            null
        }
    }

    private const val MAX_LEN = 64

    /** 常见运算符/括号变体归一化，并去除空白。 */
    private fun normalize(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                ' ', '\t', '\u3000' -> {}
                '（' -> append('(')
                '）' -> append(')')
                '【' -> append('[')
                '】' -> append(']')
                '｛' -> append('{')
                '｝' -> append('}')
                '｜' -> append('|')
                '×', 'x', 'X' -> append('*')
                '÷' -> append('/')
                '−', '－' -> append('-')
                '＋' -> append('+')
                '．', '。' -> append('.')
                else -> append(c)
            }
        }
    }

    private class Parser(private val s: String) {
        var pos = 0
        var failed = false

        /** 括号层级栈：4 = 顶层（允许 1/2/3 层），1/2/3 = 圆/方/花内部（只允许更小层级）。 */
        private val levels = ArrayDeque<Int>().apply { addLast(4) }

        fun atEnd() = pos >= s.length
        private fun peek() = if (atEnd()) ' ' else s[pos]

        fun parseExpr(): BigDecimal {
            var v = parseTerm()
            while (!failed && (peek() == '+' || peek() == '-')) {
                val op = s[pos]; pos++
                val r = parseTerm()
                if (!failed) v = if (op == '+') v.add(r) else v.subtract(r)
            }
            return v
        }

        private fun parseTerm(): BigDecimal {
            var v = parseOperand()
            while (!failed && (peek() == '*' || peek() == '/')) {
                val op = s[pos]; pos++
                val r = parseOperand()
                if (!failed) {
                    if (op == '*') v = v.multiply(r)
                    else v = divide(v, r) ?: run { failed = true; BigDecimal.ZERO }
                }
            }
            return v
        }

        /**
         * 正负号只允许一个，且仅当位于式首（pos==0）或紧随任一开括号之后；
         * 878--4 / 5*-3 / 1++2 等连续运算符在此被拒绝（负数须写 (-4)）。
         */
        private fun parseOperand(): BigDecimal {
            if ((pos == 0 || s[pos - 1] in "([{|") && (peek() == '+' || peek() == '-')) {
                val op = s[pos]; pos++
                val v = parseOperand()
                return if (op == '-') v.negate() else v
            }
            return parsePostfix()
        }

        /** 后缀 % 至多一个：n% = n/100；5%% 拒绝。 */
        private fun parsePostfix(): BigDecimal {
            val v = parsePrimary()
            if (!failed && peek() == '%') {
                pos++
                return divide(v, BigDecimal(100)) ?: run { failed = true; BigDecimal.ZERO }
            }
            return v
        }

        private fun parsePrimary(): BigDecimal {
            val c = peek()
            // 成对 + 层级：圆(1) ⊂ 方(2) ⊂ 花(3)，由内到外；混用/错配一律拒绝
            val level = when (c) {
                '(' -> 1; '[' -> 2; '{' -> 3
                else -> 0
            }
            if (level > 0) {
                // 内层层级必须 ≤ 外层（f(g(x)) 与 {[(a+b)c+d]e} 合法；([1+2]) 层级倒置拒绝）
                if (level > levels.last()) { failed = true; return BigDecimal.ZERO }
                levels.addLast(level)
                pos++
                val v = parseExpr()
                if (failed) return BigDecimal.ZERO
                val close = when (c) { '(' -> ')'; '[' -> ']'; else -> '}' }
                if (peek() != close) { failed = true; return BigDecimal.ZERO }
                pos++
                levels.removeLast()
                return v
            }
            if (c == '|') {
                pos++
                val v = parseExpr()
                if (failed) return BigDecimal.ZERO
                if (peek() != '|') { failed = true; return BigDecimal.ZERO }
                pos++
                return v.abs()
            }
            val start = pos
            while (!atEnd() && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) { failed = true; return BigDecimal.ZERO }
            val num = s.substring(start, pos)
            val dots = num.count { it == '.' }
            // 拒绝 ".5" / "5." / "1.2.3"——确保候选里的结果一定正确
            if (dots > 1 || num.startsWith('.') || num.endsWith('.')) {
                failed = true; return BigDecimal.ZERO
            }
            return BigDecimal(num)
        }

        private fun divide(a: BigDecimal, b: BigDecimal): BigDecimal? {
            if (b.signum() == 0) return null
            return try {
                a.divide(b, MathContext(16, RoundingMode.HALF_UP))
            } catch (_: Exception) {
                null
            }
        }
    }

    /** 去除多余尾零；整数结果保持全精度精确显示，仅超长小数按 12 位小数舍入。 */
    private fun format(v: BigDecimal): String {
        val t = v.stripTrailingZeros()
        val x = if (t.scale() < 0) t.setScale(0) else t
        val plain = x.toPlainString()
        return when {
            plain.length <= 24 || x.scale() <= 0 -> plain
            else -> v.setScale(12, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .let { if (it.scale() < 0) it.setScale(0) else it }
                .toPlainString()
        }
    }
}
