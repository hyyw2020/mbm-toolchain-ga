/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * MBM v1.0.39：数字键盘计算器候选 —— 本地算式求值器。
 * 纯 Kotlin 标准库、零第三方依赖、纯本地计算、不联网、无任何开关（全局默认开启）。
 *
 * 文法（递归下降；BigDecimal 精确十进制运算；除法取 16 位有效数字 HALF_UP）：
 *   expr    := term (('+' | '-') term)*
 *   term    := postfix (('*' | '/') postfix)*
 *   unary   := ('+' | '-')* postfix
 *   postfix := primary ('%')*
 *   primary := NUMBER | '(' expr ')'
 * 后缀 %：n% = n/100（例：50%+1 → 1.5）。
 *
 * 「[]里的内容必须正确」硬约束：
 *  - 仅当整段输入能被完整解析为合法算式时才给出候选，任何歧义/残缺一律不出；
 *  - 数字必须形如 `123` 或 `12.34`（拒绝 `.5` / `5.` / `1.2.3`）；
 *  - 除零不出结果；结果展示去除多余尾零（123+456 → "579" 而非 "579.0"）。
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

    /** 常见运算符变体归一化，并去除空白。 */
    private fun normalize(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                ' ' , '\t', '\u3000' -> {}
                '（' -> append('(')
                '）' -> append(')')
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
            var v = parseUnary()
            while (!failed && (peek() == '*' || peek() == '/')) {
                val op = s[pos]; pos++
                val r = parseUnary()
                if (!failed) {
                    if (op == '*') v = v.multiply(r)
                    else v = divide(v, r) ?: run { failed = true; BigDecimal.ZERO }
                }
            }
            return v
        }

        private fun parseUnary(): BigDecimal {
            if (peek() == '+' || peek() == '-') {
                val op = s[pos]; pos++
                val v = parseUnary()
                return if (op == '-') v.negate() else v
            }
            return parsePostfix()
        }

        private fun parsePostfix(): BigDecimal {
            var v = parsePrimary()
            while (!failed && peek() == '%') {
                pos++
                v = divide(v, BigDecimal(100)) ?: run { failed = true; BigDecimal.ZERO }
            }
            return v
        }

        private fun parsePrimary(): BigDecimal {
            if (peek() == '(') {
                pos++
                val v = parseExpr()
                if (failed) return BigDecimal.ZERO
                if (peek() != ')') { failed = true; return BigDecimal.ZERO }
                pos++
                return v
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
