/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.editorinfo

import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.SurroundingText
import splitties.bitflags.hasFlag
import timber.log.Timber
import java.lang.reflect.Field
import java.lang.reflect.Modifier

object EditorInfoParser {

    private const val NULL = "null"

    /** N114：字段无法解析时的占位值（如 extras 中的自定义 Parcelable） */
    private const val UNPARSEABLE = "<unparseable>"

    private fun Field.isStatic() = Modifier.isStatic(modifiers)

    private val EDITOR_INFO_MEMBER = EditorInfo::class.java.declaredFields
        .filter { !it.isStatic() }

    ////////// EditorInfo constants

    private val EDITOR_INFO_STATIC = EditorInfo::class.java.declaredFields
        .filter { it.isStatic() }

    private val IME_ACTION = EDITOR_INFO_STATIC
        .filter { it.name.startsWith("IME_ACTION_") }

    private val IME_FLAG = EDITOR_INFO_STATIC
        .filter { it.name.startsWith("IME_FLAG_") }

    ////////// InputType constants

    private val INPUT_TYPE_STATIC = InputType::class.java.declaredFields
        .filter { it.isStatic() }

    private val TYPE_CLASS = INPUT_TYPE_STATIC
        .filter { it.name.contains("_CLASS_") || it.name == "TYPE_NULL" }

    private val TYPE_FLAGS = INPUT_TYPE_STATIC
        .filter { it.name.contains("_FLAG_") }

    private val TYPE_VARIATION = INPUT_TYPE_STATIC
        .filter { it.name.contains("_VARIATION_") }

    ////////// TextUtils constants

    private val CAP_MODE = TextUtils::class.java.declaredFields
        .filter { it.isStatic() && it.name.startsWith("CAP_MODE_") }

    private fun parseImeOptions(imeOptions: Int): String {
        val action = imeOptions and EditorInfo.IME_MASK_ACTION
        val actionString = IME_ACTION.find { it.getInt(null) == action }?.name ?: "$action"
        val flags = IME_FLAG
            .filter { imeOptions.hasFlag(it.getInt(null)) }
            .joinToString("\n  ") { it.name }
        return "action=$actionString\nflags=$flags"
    }

    private fun parseInputType(inputType: Int): String {
        val `class` = inputType and InputType.TYPE_MASK_CLASS
        val classString = TYPE_CLASS.find { it.getInt(null) == `class` }?.name ?: "$`class`"
        val flags = inputType and InputType.TYPE_MASK_FLAGS
        val flagsString = TYPE_FLAGS
            .filter { flags.hasFlag(it.getInt(null)) }
            .joinToString("\n  ") { it.name }
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val variationPrefix = classString.replace("_CLASS", "")
        val variationString = TYPE_VARIATION
            .filter { variation == it.getInt(null) && it.name.startsWith(variationPrefix) }
            .joinToString("\n  ") { it.name }
        return "class=$classString\nflags=$flagsString\nvariation=$variationString"
    }

    private fun parseCapsMode(capsMode: Int): String {
        return CAP_MODE
            .filter { capsMode.hasFlag(it.getInt(null)) }
            .joinToString("\n  ") { it.name }
    }

    private fun parseSurroundingText(st: Any?): String {
        if (st == null) return NULL
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || st !is SurroundingText) return st.toString()
        return st.run {
            "text=$text\noffset=$offset\nselectionStart=$selectionStart\nselectionEnd=$selectionEnd"
        }
    }

    private fun parseStringArray(arr: Any?): String {
        if (arr == null) return NULL
        if (arr !is Array<*>) return arr.toString()
        return arr.joinToString()
    }

    @Suppress("DEPRECATION")
    private fun parseBundle(bundle: Any?): String {
        if (bundle == null) return NULL
        if (bundle !is Bundle) return bundle.toString()
        return bundle.keySet().joinToString("\n") { key ->
            // N114：extras 里可能是第三方自定义 Parcelable，Bundle.get() 反序列化时
            // 找不到对应 Class 会抛 ClassNotFoundException / BadParcelableException。
            // 单个键解析失败只降级该项，不能让整个面板崩掉。
            val value = try {
                bundle.get(key)
            } catch (e: Exception) {
                Timber.w(e, "N114: failed to unparcel EditorInfo.extras key=$key")
                return@joinToString "$key => $UNPARSEABLE"
            }
            "$key => $value"
        }
    }

    fun parse(info: EditorInfo): Map<String, String> = EDITOR_INFO_MEMBER
        .associate {
            it.isAccessible = true
            val name = it.name
            // N114：单个字段读取失败（反射取值 / 反序列化）不能让整个解析崩掉
            val value = try {
                when (name) {
                    "contentMimeTypes" -> parseStringArray(it.get(info))
                    "extras" -> parseBundle(it.get(info))
                    "imeOptions" -> parseImeOptions(it.getInt(info))
                    "initialCapsMode" -> parseCapsMode(it.getInt(info))
                    "inputType" -> parseInputType(it.getInt(info))
                    "mInitialSurroundingText" -> parseSurroundingText(it.get(info))
                    else -> it.get(info)?.toString() ?: NULL
                }
            } catch (e: Exception) {
                Timber.w(e, "N114: failed to parse EditorInfo field=$name")
                UNPARSEABLE
            }
            name to value
        }
}
