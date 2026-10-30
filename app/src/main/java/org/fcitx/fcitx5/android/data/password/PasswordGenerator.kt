/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 */
package org.fcitx.fcitx5.android.data.password

import java.security.SecureRandom

/**
 * 高强度密码生成器 —— 全部计算在本地完成，不联网、不上传。
 *
 * 功能与《密码生成器原型.html》1:1 对应：
 *  - 字符集开关（大写 / 小写 / 数字 / 符号）
 *  - 长度 4-128、生成数量 1-100
 *  - 每类字符最少数量（联动校验）
 *  - 排除易混淆字符 / 常见弱密码 / 重复字符 / 连续序列（abc、123）/ 自定义字符
 *  - 自定义符号：追加 或 仅使用
 *  - 批量生成 + 结果去重 + 强度评估（熵值 bit + 弱/中/强/极强）
 *
 * 随机源为 SecureRandom + 无偏拒绝采样（等价原型的 crypto.getRandomValues 方案）。
 */
object PasswordGenerator {

    const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    const val DIGITS = "0123456789"
    const val SYMBOLS = "!@#$%^&*()-_=+[]{};:,.<>?/"
    private val CONFUSING = setOf('0', 'O', '1', 'l', 'I', '|', '`', '\'', '"', '\\')
    private val WEAK_LIST = setOf(
        "password", "123456", "qwerty", "admin", "letmein",
        "12345678", "abc123", "iloveyou", "111111", "000000"
    )

    data class Config(
        val useUpper: Boolean = true,
        val useLower: Boolean = true,
        val useDigits: Boolean = true,
        val useSymbols: Boolean = true,
        val length: Int = 16,
        val count: Int = 5,
        val minUpper: Int = 1,
        val minLower: Int = 1,
        val minDigits: Int = 1,
        val minSymbols: Int = 1,
        val excludeConfusing: Boolean = false,
        val excludeWeak: Boolean = true,
        val noRepeat: Boolean = false,
        val noSequential: Boolean = false,
        val excludeCustom: String = "",
        val customSymbols: String = "",
        val customSymbolsOnly: Boolean = false,
        val checkStrength: Boolean = true
    )

    data class Entry(val value: String, val entropy: Double?, val level: String?)

    data class Result(val entries: List<Entry>, val poolSize: Int, val short: Boolean)

    enum class Level(val label: String) {
        WEAK("弱"), MEDIUM("中"), STRONG("强"), VERY_STRONG("极强");

        companion object {
            fun of(entropy: Double): Level = when {
                entropy < 40 -> WEAK
                entropy < 60 -> MEDIUM
                entropy < 80 -> STRONG
                else -> VERY_STRONG
            }
        }
    }

    private val random = SecureRandom()

    /** 无偏拒绝采样：[0, max) */
    private fun randInt(max: Int): Int {
        require(max > 0) { "max must be > 0" }
        val limit = (0xFFFFFFFF.toLong() / max) * max
        while (true) {
            val v = random.nextInt().toLong() and 0xFFFFFFFFL
            if (v >= limit) continue
            return (v % max).toInt()
        }
    }

    private fun <T> pick(list: List<T>): T = list[randInt(list.size)]

    private fun <T> shuffle(a: MutableList<T>) {
        for (i in a.size - 1 downTo 1) {
            val j = randInt(i + 1)
            val tmp = a[i]
            a[i] = a[j]
            a[j] = tmp
        }
    }

    private fun dedup(s: String): String = s.toList().distinct().joinToString("")

    /** 连续序列检测，默认 3 连（abc / 321 均命中） */
    private fun hasSequential(pwd: String, run: Int = 3): Boolean {
        if (pwd.length < run) return false
        for (i in 0..pwd.length - run) {
            var up = true
            var down = true
            for (j in 0 until run - 1) {
                val d = pwd[i + j + 1].code - pwd[i + j].code
                if (d != 1) up = false
                if (d != -1) down = false
            }
            if (up || down) return true
        }
        return false
    }

    internal class Pool(val name: String, val chars: String, val min: Int)

    private fun buildPools(cfg: Config): Pair<List<Pool>, String> {
        var upper = UPPER
        var lower = LOWER
        var digits = DIGITS
        var symbols = if (cfg.customSymbolsOnly) cfg.customSymbols else SYMBOLS + cfg.customSymbols

        fun strip(s: String, drop: (Char) -> Boolean): String =
            s.filter { !drop(it) }

        if (cfg.excludeConfusing) {
            upper = strip(upper) { CONFUSING.contains(it) }
            lower = strip(lower) { CONFUSING.contains(it) }
            digits = strip(digits) { CONFUSING.contains(it) }
            symbols = strip(symbols) { CONFUSING.contains(it) }
        }
        if (cfg.excludeCustom.isNotEmpty()) {
            val ex = cfg.excludeCustom.toSet()
            upper = strip(upper) { ex.contains(it) }
            lower = strip(lower) { ex.contains(it) }
            digits = strip(digits) { ex.contains(it) }
            symbols = strip(symbols) { ex.contains(it) }
        }

        val pools = mutableListOf<Pool>()
        if (cfg.useUpper) pools += Pool("大写字母", dedup(upper), cfg.minUpper)
        if (cfg.useLower) pools += Pool("小写字母", dedup(lower), cfg.minLower)
        if (cfg.useDigits) pools += Pool("数字", dedup(digits), cfg.minDigits)
        if (cfg.useSymbols) pools += Pool("特殊符号", dedup(symbols), cfg.minSymbols)

        val all = dedup(pools.joinToString("") { it.chars })
        return pools to all
    }

    internal fun validate(cfg: Config, pools: List<Pool>, all: String): List<String> {
        val errors = mutableListOf<String>()
        pools.forEach {
            if (it.min > it.chars.length) {
                errors += "${it.name}可用 ${it.chars.length} 个，少于最少需要的 ${it.min} 个"
            }
        }
        val required = pools.sumOf { it.min }
        if (required > cfg.length) {
            errors += "各类最少数量合计 $required，超过密码长度 ${cfg.length}"
        }
        if (all.isEmpty()) {
            errors += "字符池为空，请至少启用一类字符"
        }
        if (cfg.noRepeat && all.length < cfg.length) {
            errors += "不允许重复时需要至少 ${cfg.length} 个不同字符，当前只有 ${all.length} 个"
        }
        return errors
    }

    private fun generateOne(cfg: Config, pools: List<Pool>, all: String): String {
        val l = cfg.length
        repeat(3000) {
            val chars = mutableListOf<Char>()
            var ok = true

            for (p in pools) {
                repeat(p.min) {
                    if (cfg.noRepeat) {
                        val avail = p.chars.toList().filter { it !in chars }
                        if (avail.isEmpty()) { ok = false; return@repeat }
                        chars += pick(avail)
                    } else {
                        chars += pick(p.chars.toList())
                    }
                }
                if (!ok) return@repeat
            }
            if (!ok) return@repeat

            while (chars.size < l) {
                if (cfg.noRepeat) {
                    val avail = all.toList().filter { it !in chars }
                    if (avail.isEmpty()) { ok = false; break }
                    chars += pick(avail)
                } else {
                    chars += pick(all.toList())
                }
            }
            if (!ok || chars.size != l) return@repeat

            shuffle(chars)
            val pwd = chars.joinToString("")

            if (cfg.noRepeat && pwd.toSet().size != pwd.length) return@repeat
            if (cfg.noSequential && hasSequential(pwd)) return@repeat
            if (cfg.excludeWeak && WEAK_LIST.contains(pwd.lowercase())) return@repeat

            return pwd
        }
        throw IllegalStateException("在合理尝试次数内无法满足所有约束，请放宽条件")
    }

    fun generate(cfg: Config): Result {
        val (pools, all) = buildPools(cfg)
        val errors = validate(cfg, pools, all)
        if (errors.isNotEmpty()) throw IllegalArgumentException(errors.joinToString("；"))

        val results = mutableListOf<String>()
        val seen = HashSet<String>()
        var attempts = 0
        val maxAttempts = cfg.count * 300
        while (results.size < cfg.count && attempts < maxAttempts) {
            attempts++
            val pwd = generateOne(cfg, pools, all)
            if (seen.add(pwd)) results += pwd
        }

        val poolSize = all.length
        val entries = results.map {
            if (!cfg.checkStrength) Entry(it, null, null)
            else {
                val e = if (poolSize > 1) it.length * kotlin.math.log2(poolSize.toDouble()) else 0.0
                val lv = Level.of(e)
                Entry(it, e, lv.label)
            }
        }
        return Result(entries, poolSize, results.size < cfg.count)
    }
}
