/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.quickphrase

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorRuntime
import org.fcitx.fcitx5.android.utils.safeResolve
import org.fcitx.fcitx5.android.utils.withTempDir
import java.io.File
import java.io.InputStream

object QuickPhraseManager {

    private val builtinQuickPhraseDir = File(
        DataManager.dataDir, "usr/share/fcitx5/data/quickphrase.d"
    )

    private val customQuickPhraseDir = File(
        appContext.getExternalFilesDir(null)!!, "data/data/quickphrase.d"
    ).also { it.mkdirs() }

    fun listQuickPhrase(): List<QuickPhrase> {
        val builtin = listDir(builtinQuickPhraseDir) { file ->
            BuiltinQuickPhrase(file, File(customQuickPhraseDir, file.name))
        }
        val custom = listDir(customQuickPhraseDir) { file ->
            CustomQuickPhrase(file).takeUnless { cq -> builtin.any { cq.name == it.name } }
        }
        return builtin + custom
    }

    fun newEmpty(name: String): CustomQuickPhrase {
        // N39：用户提供的名称限制为单段文件名，禁止 "../" 注入
        val file = safeResolve(customQuickPhraseDir, "$name.${QuickPhrase.EXT}")
        file.createNewFile()
        return CustomQuickPhrase(file)
    }

    private fun importFromFile(file: File): Result<CustomQuickPhrase> {
        return runCatching {
            // check quickphrase format of each line
            file.readLines().forEachIndexed { idx, line ->
                if (line.isNotBlank() && QuickPhraseEntry.fromLine(line) == null) {
                    errorRuntime(R.string.exception_quickphrase_parse, "\n(${idx + 1}) $line")
                }
            }
            // N39：导入文件名限制为单段文件名，强制落在自定义短语目录内
            val dest = safeResolve(customQuickPhraseDir, file.name)
            file.copyTo(dest)
            CustomQuickPhrase(dest)
        }
    }

    fun importFromInputStream(stream: InputStream, fileName: String): Result<CustomQuickPhrase> {
        return stream.use { i ->
            withTempDir { dir ->
                // N39：导入文件名限制为单段文件名，禁止遍历出临时目录
                val tempFile = safeResolve(dir, fileName)
                tempFile.outputStream().use { o -> i.copyTo(o) }
                importFromFile(tempFile)
            }
        }
    }

    /**
     * v1.0.54 更新式导入：把新快速输入文件合并进已存在的同名自定义词条，而不是报错拒绝。
     * 合并规则：完全相同的「键=短语」只保留一份；两份中都出现的条目（重复词/词组）
     * 提到同键分组的最前，提高候选优先级；随后是仅旧文件、仅新文件的条目。
     */
    fun mergeUpdate(
        existing: CustomQuickPhrase,
        stream: InputStream,
        fileName: String
    ): Result<CustomQuickPhrase> = runCatching {
        val newEntries = mutableListOf<QuickPhraseEntry>()
        stream.bufferedReader().useLines { lines ->
            lines.forEachIndexed { idx, line ->
                if (line.isNotBlank()) {
                    newEntries += QuickPhraseEntry.fromLine(line)
                        ?: errorRuntime(R.string.exception_quickphrase_parse, "\n(${idx + 1}) $line")
                }
            }
        }
        val oldEntries = existing.loadData()
        val oldSet = oldEntries.map { it.keyword to it.phrase }.toSet()
        val newSet = newEntries.map { it.keyword to it.phrase }.toSet()
        val duplicated = oldEntries.filter { it.keyword to it.phrase in newSet }
        val oldOnly = oldEntries.filter { it.keyword to it.phrase !in newSet }
        val newOnly = newEntries.filter { it.keyword to it.phrase !in oldSet }
        existing.saveData(QuickPhraseData(duplicated + oldOnly + newOnly))
        CustomQuickPhrase(existing.file, existing.isEnabled)
    }

    private fun <T : QuickPhrase> listDir(
        dir: File,
        block: (File) -> T?
    ): List<T> =
        dir.listFiles()
            ?.mapNotNull { file ->
                file.name.takeIf { name ->
                    name.endsWith(".${QuickPhrase.EXT}") || name.endsWith(".${QuickPhrase.EXT}.${QuickPhrase.DISABLE}")
                }
                    ?.let { block(file) }
            } ?: listOf()


}