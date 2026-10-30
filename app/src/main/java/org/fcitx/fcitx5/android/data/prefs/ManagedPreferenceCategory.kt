/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.prefs

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.preference.PreferenceScreen

abstract class ManagedPreferenceCategory(
    @StringRes val title: Int,
    protected val sharedPreferences: SharedPreferences
) : ManagedPreferenceProvider() {

    protected fun switch(
        @StringRes
        title: Int,
        key: String,
        defaultValue: Boolean,
        @StringRes
        summary: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ): ManagedPreference.PBool {
        val pref = ManagedPreference.PBool(sharedPreferences, key, defaultValue)
        val ui = ManagedPreferenceUi.Switch(title, key, defaultValue, summary, enableUiOn)
        pref.register()
        ui.registerUi()
        return pref
    }

    protected fun <T : Any> list(
        @StringRes
        title: Int,
        key: String,
        defaultValue: T,
        codec: ManagedPreference.StringLikeCodec<T>,
        entryValues: List<T>,
        @StringRes
        entryLabels: List<Int>,
        enableUiOn: (() -> Boolean)? = null
    ): ManagedPreference.PStringLike<T> {
        // N101：持久化的值可能是非法值（例如升级后枚举被重命名/删除）。
        // 这里提前把非法值写回默认值：
        // 1. 避免 [ManagedPreference.PStringLike.getValue] 读取非法值时抛异常后
        //    在函数内部调用 setValue()，进而触发 SharedPreferences 监听器重入；
        // 2. 让之后创建的 ListPreference 能读到合法值，摘要不再显示为空白。
        sanitizePersistedValue(key, defaultValue, codec)
        val pref = ManagedPreference.PStringLike(sharedPreferences, key, defaultValue, codec)
        val ui = ManagedPreferenceUi.StringList(
            title, key, defaultValue, codec, entryValues, entryLabels, enableUiOn
        )
        pref.register()
        ui.registerUi()
        return pref
    }

    /**
     * N101：持久化的值可能是非法值（例如枚举被重命名/删除后留下的旧值）。
     *
     * 这里提前把非法值写回默认值，原因有二：
     * 1. 避免 [ManagedPreference.PStringLike.getValue] 读到非法值后抛异常，
     *    进而在函数内部调用 setValue() 写回默认值，触发 SharedPreferences
     *    监听器再次派发（递归重入）；
     * 2. 让之后创建的 ListPreference 能读到合法值，摘要不再显示为空白。
     */
    private fun <T : Any> sanitizePersistedValue(
        key: String,
        defaultValue: T,
        codec: ManagedPreference.StringLikeCodec<T>
    ) {
        val raw = try {
            sharedPreferences.getString(key, null)
        } catch (e: Exception) {
            // 类型都不是 String，说明是历史遗留的脏数据，同样按非法值处理
            writeDefaultValue(key, defaultValue, codec)
            return
        } ?: return
        val decoded = try {
            codec.decode(raw)
        } catch (e: Exception) {
            null
        }
        if (decoded != null) return
        writeDefaultValue(key, defaultValue, codec)
    }

    private fun <T : Any> writeDefaultValue(
        key: String,
        defaultValue: T,
        codec: ManagedPreference.StringLikeCodec<T>
    ) {
        sharedPreferences.edit {
            putString(key, codec.encode(defaultValue))
        }
    }

    protected inline fun <reified T> enumList(
        @StringRes
        title: Int,
        key: String,
        defaultValue: T,
        noinline enableUiOn: (() -> Boolean)? = null
    ): ManagedPreference.PStringLike<T> where T : Enum<T>, T : ManagedPreferenceEnum {
        val codec = object : ManagedPreference.StringLikeCodec<T> {
            /**
             * N101：非法值不再抛异常，而是返回 null，
             * 由 [ManagedPreference.PStringLike.getValue] 回退到默认值，
             * 从而不会在 getValue() 内部调用 setValue() 造成递归重入。
             */
            override fun decode(raw: String): T? =
                enumValues<T>().firstOrNull { it.toString() == raw }
        }
        val entryValues = enumValues<T>().toList()
        val entryLabels = entryValues.map { it.stringRes }
        return list(title, key, defaultValue, codec, entryValues, entryLabels, enableUiOn)
    }

    protected fun voiceInputPreference(
        @StringRes
        title: Int,
        key: String,
        defaultValue: String,
        enableUiOn: (() -> Boolean)? = null
    ): ManagedPreference.PString {
        val pref = ManagedPreference.PString(sharedPreferences, key, defaultValue)
        val ui = ManagedPreferenceUi.VoiceInputList(title, key, defaultValue, enableUiOn)
        pref.register()
        ui.registerUi()
        return pref
    }

    protected fun int(
        @StringRes
        title: Int,
        key: String,
        defaultValue: Int,
        min: Int = 0,
        max: Int = Int.MAX_VALUE,
        unit: String = "",
        step: Int = 1,
        @StringRes
        defaultLabel: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ): ManagedPreference.PInt {
        val pref = ManagedPreference.PInt(sharedPreferences, key, defaultValue)
        // Int can overflow when min < 0 && max == Int.MAX_VALUE
        val ui = if ((max.toLong() - min.toLong()) / step.toLong() >= 240L)
            ManagedPreferenceUi.EditTextInt(
                title, key, defaultValue, min, max, unit, enableUiOn
            )
        else
            ManagedPreferenceUi.SeekBarInt(
                title, key, defaultValue, min, max, unit, step, defaultLabel, enableUiOn
            )
        pref.register()
        ui.registerUi()
        return pref
    }

    protected fun twinInt(
        @StringRes
        title: Int,
        @StringRes
        label: Int,
        key: String,
        defaultValue: Int,
        @StringRes
        secondaryLabel: Int,
        secondaryKey: String,
        secondaryDefaultValue: Int,
        min: Int,
        max: Int,
        unit: String = "",
        step: Int = 1,
        @StringRes
        defaultLabel: Int? = null,
        enableUiOn: (() -> Boolean)? = null
    ): Pair<ManagedPreference.PInt, ManagedPreference.PInt> {
        val primary = ManagedPreference.PInt(
            sharedPreferences,
            key, defaultValue,
        )
        val secondary = ManagedPreference.PInt(
            sharedPreferences,
            secondaryKey, secondaryDefaultValue
        )
        val ui = ManagedPreferenceUi.TwinSeekBarInt(
            title,
            label, key, defaultValue,
            secondaryLabel, secondaryKey, secondaryDefaultValue,
            min, max, unit, step, defaultLabel, enableUiOn
        )
        primary.register()
        secondary.register()
        ui.registerUi()
        return primary to secondary
    }

    override fun createUi(screen: PreferenceScreen) {
        val ctx = screen.context
        managedPreferencesUi.forEach {
            screen.addPreference(it.createUi(ctx).apply {
                isEnabled = it.isEnabled()
            })
        }
    }
}