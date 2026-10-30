/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.data

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import timber.log.Timber

class RecentlyUsed(val type: String, val limit: Int) {

    companion object {
        // for backwords compatibility only
        const val DIR_NAME = "recently_used"
        const val PREFERENCE_NAME = "picker_recently_used"

        // N49：合并写的延迟窗口，窗口内多次上屏只落盘一次
        private const val SAVE_DELAY_MS = 1000L
        // N49：落盘统一放到共享 IO 线程，不占用调用方（主线程）时间
        private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private val sharedPreferences = FcitxApplication.getInstance().directBootAwareContext
        .getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)

    // map 会被主线程（insert/items）与 IO 线程（save）同时访问，统一加锁
    private val lock = Any()

    private val map = LinkedHashMap<String, Boolean>(limit).apply {
        (migrate() ?: load()).forEach { put(it, true) }
    }

    init {
        // N49：迁移/读取的历史数据可能超过上限，先淘汰到容量内
        trimToLimit()
    }

    val items: List<String> get() = synchronized(lock) { map.keys.reversed() }

    /** N49：LRU 淘汰，超过 limit 就从最久未用的一端丢弃 */
    private fun trimToLimit() {
        while (map.size > limit) {
            val eldest = map.keys.firstOrNull() ?: break
            map.remove(eldest)
        }
    }

    private fun load(): List<String> {
        val rawValue = sharedPreferences.getString(type, "") ?: ""
        if (rawValue.isEmpty()) {
            return emptyList()
        }
        return try {
            Json.decodeFromString<List<String>>(rawValue)
        } catch (_: Exception) {
            sharedPreferences.edit {
                remove(type)
            }
            emptyList()
        }
    }

    private fun save() {
        val snapshot = synchronized(lock) { map.keys.toList() }
        sharedPreferences.edit {
            putString(type, Json.encodeToString<List<String>>(snapshot))
        }
    }

    private var saveJob: Job? = null

    /**
     * N49：不再每次上屏都全量序列化写盘。已有待落盘任务时直接合并本次变更，
     * 否则延迟 [SAVE_DELAY_MS] 后写一次，把连续上屏的多次写入合并成一次。
     */
    private fun scheduleSave() {
        synchronized(lock) {
            if (saveJob?.isActive == true) return
            saveJob = saveScope.launch {
                delay(SAVE_DELAY_MS)
                try {
                    save()
                } catch (e: Exception) {
                    Timber.w("Failed to save RecentlyUsed(type=$type)")
                    Timber.w(e)
                }
            }
        }
    }

    fun insert(item: String) {
        synchronized(lock) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                map.putLast(item, true)
            } else {
                // LinkedHashMap's encounter order is not affected when `put` an existing key
                if (map.containsKey(item)) {
                    map.remove(item)
                }
                map.put(item, true)
            }
            trimToLimit()
        }
        scheduleSave()
    }

    fun migrate(): List<String>? {
        val dir = FcitxApplication.getInstance().directBootAwareContext.filesDir.resolve(DIR_NAME)
        val file = dir.resolve(type)
        if (file.exists()) {
            try {
                val lines = file.readLines()
                // save to sharedPreferences before deleting old file
                sharedPreferences.edit {
                    putString(type, Json.encodeToString<List<String>>(lines))
                }
                file.delete()
                if (dir.list()?.isEmpty() == true) {
                    dir.delete()
                }
                return lines
            } catch (e: Exception) {
                Timber.w("Failed to migrate RecentlyUsed(type=$type)")
                Timber.w(e)
                return null
            }
        }
        return null
    }
}