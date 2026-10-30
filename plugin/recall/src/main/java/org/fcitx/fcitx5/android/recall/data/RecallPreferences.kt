/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 */
package org.fcitx.fcitx5.android.recall.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.fcitx.fcitx5.android.utils.appContext

/**
 * Recall 偏好设置：全部本地保存。
 */
val Context.recallDataStore: DataStore<Preferences> by preferencesDataStore(name = "recall")

object RecallPreferences {

    /** N77：默认保留天数（非 0，避免库无限增长）。0 仍表示「永不清理」，但需用户显式选择。 */
    const val DEFAULT_KEEP_DAYS = 30

    val Enabled = booleanPreferencesKey("enabled")
    val OcrEnabled = booleanPreferencesKey("ocr_enabled")
    val AutoAddPhrase = booleanPreferencesKey("auto_add_phrase")
    val AutoClipboard = booleanPreferencesKey("auto_clipboard")

    private val KeepDays = intPreferencesKey("keep_days")

    /** 捕获开关（默认关闭，需用户在「隐私与 Recall」中授权开启） */
    fun enabledFlow(): Flow<Boolean> = flow(Enabled, false)

    fun ocrEnabledFlow(): Flow<Boolean> = flow(OcrEnabled, true)

    fun autoAddPhraseFlow(): Flow<Boolean> = flow(AutoAddPhrase, true)

    fun autoClipboardFlow(): Flow<Boolean> = flow(AutoClipboard, true)

    suspend fun setEnabled(v: Boolean) = set(Enabled, v)

    suspend fun setOcrEnabled(v: Boolean) = set(OcrEnabled, v)

    suspend fun setAutoAddPhrase(v: Boolean) = set(AutoAddPhrase, v)

    suspend fun setAutoClipboard(v: Boolean) = set(AutoClipboard, v)

    suspend fun isEnabled(): Boolean = enabledFlow().first()

    suspend fun isOcrEnabled(): Boolean = ocrEnabledFlow().first()

    suspend fun isAutoAddPhrase(): Boolean = autoAddPhraseFlow().first()

    suspend fun isAutoClipboard(): Boolean = autoClipboardFlow().first()

    /**
     * N77：保留天数默认 **30 天**（此前是 0 = 永不清理）。
     * 0 会让 recall 表与 recall_blobs/ 只增不减：每次捕获都要对**无索引**的
     * packageName+text / +sha256 做全表 COUNT，行数到十万级后写路径线性变慢。
     * 现在既有索引兜底，又恢复按天回收（清理走 [RecallRepository.trim] 并记审计日志）；
     * 仍想永久保留的用户可在「隐私与 Recall」里显式设 0。
     */
    fun keepDaysFlow(): Flow<Int> = flow(KeepDays, DEFAULT_KEEP_DAYS)

    suspend fun setKeepDays(v: Int) = appContext.recallDataStore.edit { it[KeepDays] = v }

    private fun <T> flow(key: Preferences.Key<T>, def: T): Flow<T> =
        appContext.recallDataStore.data.map { it[key] ?: def }

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        appContext.recallDataStore.edit { it[key] = value }
    }
}
