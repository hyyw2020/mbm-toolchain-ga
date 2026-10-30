/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.extract
import org.fcitx.fcitx5.android.utils.versionCodeCompat
import org.fcitx.fcitx5.android.utils.withTempDir
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object UserDataManager {

    private val json = Json { prettyPrint = true }

    @Serializable
    data class Metadata(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val exportTime: Long
    )

    private fun writeFileTree(
        srcDir: File,
        destPrefix: String,
        dest: ZipOutputStream,
        exclude: (File) -> Boolean = { false }
    ) {
        dest.putNextEntry(ZipEntry("$destPrefix/"))
        srcDir.walkTopDown().forEach { f ->
            val related = f.relativeTo(srcDir)
            if (related.path != "") {
                if (exclude(f)) return@forEach
                if (f.isDirectory) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}/"))
                } else if (f.isFile) {
                    dest.putNextEntry(ZipEntry("$destPrefix/${related.path}"))
                    f.inputStream().use { it.copyTo(dest) }
                }
            }
        }
    }

    private val sharedPrefsDir = File(appContext.applicationInfo.dataDir, "shared_prefs")
    private val dataBasesDir = File(appContext.applicationInfo.dataDir, "databases")
    private val externalDir = appContext.getExternalFilesDir(null)!!
    private val recentlyUsedDir = appContext.filesDir.resolve(RecentlyUsed.DIR_NAME)

    /**
     * MBM v1.0.35u（N05）：备份导出必须带上剪贴板大文本 blob 目录，
     * 否则恢复后所有 ≥256KB 的条目只剩摘要行、正文全丢。
     */
    private val clipBlobDir = appContext.filesDir.resolve("clipblobs")

    /**
     * MBM v1.0.35u（N06）：密钥信封（Keystore 包裹的 raw key）**不参与导入**。
     *
     * 背景：信封密文与本机 Keystore 密钥绑定。换机/恢复出厂后本机 Keystore
     * 密钥已销毁 → 解封必然失败 → [AppKeyManager] 走「轮换新 key」分支 →
     * 设备上全部历史 blob 变成解不开的孤儿（随后被 GC 删除，用户数据静默消失）。
     * 若再让导入把备份里的旧信封覆盖到本机，则连「同设备备份恢复」这条
     * 本该可用的路径也被打断（备份信封 ≠ 本机信封 → 覆盖即轮换）。
     *
     * 处置：备份里**不含**信封、导入时**跳过**信封文件（保留本机信封）；
     * 若恢复出来的数据与本机密钥不匹配，blob 读不出来时按「孤儿」处理，
     * 属于可预期的数据不匹配，而不是把本机可用密钥也毁掉。
     */
    private const val KEY_ENVELOPE_PREFS = "mbm_clip_secure_prefs"

    private fun isKeyEnvelope(f: File): Boolean =
        f.isFile && f.name.substringBeforeLast('.') == KEY_ENVELOPE_PREFS

    @OptIn(ExperimentalSerializationApi::class)
    fun export(dest: OutputStream, timestamp: Long = System.currentTimeMillis()) = runCatching {
        ZipOutputStream(dest.buffered()).use { zipStream ->
            // shared_prefs（排除密钥信封：见 N06 说明）
            writeFileTree(sharedPrefsDir, "shared_prefs", zipStream) { isKeyEnvelope(it) }
            // databases
            writeFileTree(dataBasesDir, "databases", zipStream)
            // external
            writeFileTree(externalDir, "external", zipStream)
            // clipblobs：剪贴板大文本正文（N05）
            if (clipBlobDir.isDirectory) {
                writeFileTree(clipBlobDir, "clipblobs", zipStream)
            }
            // recently_used moved to SharedPreference and shoud not be exported
            // metadata
            zipStream.putNextEntry(ZipEntry("metadata.json"))
            val pkgInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val metadata = Metadata(
                pkgInfo.packageName,
                pkgInfo.versionCodeCompat,
                Const.versionName,
                timestamp
            )
            json.encodeToStream(metadata, zipStream)
            zipStream.closeEntry()
        }
    }

    private fun copyDir(source: File, target: File, exclude: (File) -> Boolean = { false }) {
        val exists = source.exists()
        val isDir = source.isDirectory
        if (exists && isDir) {
            source.walkTopDown().forEach { f ->
                val rel = f.relativeTo(source)
                if (rel.path == "") return@forEach
                if (exclude(f)) {
                    Timber.i("UserDataManager: skip importing '${f.name}'")
                    return@forEach
                }
                val dst = File(target, rel.path)
                if (f.isDirectory) {
                    dst.mkdirs()
                } else if (f.isFile) {
                    dst.parentFile?.mkdirs()
                    f.inputStream().use { ins ->
                        dst.outputStream().use { outs -> ins.copyTo(outs) }
                    }
                }
            }
        } else {
            Timber.w("Cannot import user data: path='${source.path}', exists=$exists, isDir=$isDir")
        }
    }

    fun import(src: InputStream) = runCatching {
        ZipInputStream(src).use { zipStream ->
            withTempDir { tempDir ->
                val extracted = zipStream.extract(tempDir)
                val metadataFile = extracted.find { it.name == "metadata.json" }
                    ?: errorRuntime(R.string.exception_user_data_metadata)
                val metadata = json.decodeFromString<Metadata>(metadataFile.readText())
                if (metadata.packageName != BuildConfig.APPLICATION_ID)
                    errorRuntime(R.string.exception_user_data_package_name_mismatch)
                // N06：跳过密钥信封 —— 备份里的信封与本机 Keystore 不配，
                // 覆盖即导致本机密钥被轮换、历史数据全部变孤儿。
                copyDir(File(tempDir, "shared_prefs"), sharedPrefsDir) { isKeyEnvelope(it) }
                copyDir(File(tempDir, "databases"), dataBasesDir)
                copyDir(File(tempDir, "external"), externalDir)
                // N05：恢复剪贴板大文本 blob
                copyDir(File(tempDir, "clipblobs"), clipBlobDir)
                // keep importing recently_used for backwords compatibility
                copyDir(File(tempDir, "recently_used"), recentlyUsedDir)
                metadata
            }
        }
    }
}