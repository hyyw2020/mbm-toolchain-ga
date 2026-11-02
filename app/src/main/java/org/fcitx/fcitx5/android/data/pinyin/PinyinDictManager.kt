/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.data.pinyin.dict.BuiltinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.LibIMEDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.utils.PinyinUtils
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorArg
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.InputStream

object PinyinDictManager {

    private val pinyinDicDir = File(
        appContext.getExternalFilesDir(null)!!, "data/pinyin/dictionaries"
    ).also { it.mkdirs() }

    private val builtinPinyinDictDir = File(
        DataManager.dataDir, "usr/share/fcitx5/pinyin/dictionaries"
    )

    private val nativeDir = File(appContext.applicationInfo.nativeLibraryDir)

    private val scel2org5 by lazy { File(nativeDir, scel2org5Name) }

    fun listDictionaries(): List<PinyinDictionary> {
        val builtin = mutableListOf<PinyinDictionary>()
        builtinPinyinDictDir.listFiles()?.forEach {
            if (it.extension == PinyinDictionary.Type.LibIME.ext) {
                builtin.add(BuiltinDictionary(it))
            }
        }
        builtin.sortBy { it.name }
        val user = mutableListOf<PinyinDictionary>()
        pinyinDicDir.listFiles()?.forEach {
            PinyinDictionary.new(it)?.let { dict ->
                if (dict is LibIMEDictionary) {
                    user.add(dict)
                }
            }
        }
        user.sortBy { it.name }
        return builtin + user
    }

    fun importFromFile(file: File): Result<LibIMEDictionary> = runCatching {
        val raw =
            PinyinDictionary.new(file) ?: errorArg(R.string.exception_dict_filename, file.path)
        // convert to libime format in dictionaries dir
        // preserve original file name
        val new = raw.toLibIMEDictionary(
            File(
                pinyinDicDir,
                file.nameWithoutExtension + ".${PinyinDictionary.Type.LibIME.ext}"
            )
        )
        Timber.d("Converted $raw to $new")
        new
    }

    fun importFromInputStream(stream: InputStream, name: String): Result<LibIMEDictionary> {
        val tempFile = File(appContext.cacheDir, name)
        tempFile.outputStream().use {
            stream.copyTo(it)
        }
        val new = importFromFile(tempFile)
        tempFile.delete()
        return new
    }

    /**
     * v1.0.54 更新式导入：把新词库合并进已存在的同名用户词库，而不是报错拒绝。
     * 合并规则：拼音+词 相同的条目视为重复，重复词/词组提高优先级
     * （频率取 max + 0.5*min，重复导入可继续叠加，越用越靠前）。
     */
    fun mergeUpdate(
        existing: LibIMEDictionary,
        stream: InputStream,
        fileName: String
    ): Result<LibIMEDictionary> = runCatching {
        val type = PinyinDictionary.Type.fromFileName(fileName)
            ?: errorArg(R.string.exception_dict_filename, fileName)
        val tempFile = File(appContext.cacheDir, fileName)
        tempFile.outputStream().use { stream.copyTo(it) }
        val incomingDict = PinyinDictionary.new(tempFile)
            ?: errorArg(R.string.exception_dict_filename, fileName)
        val incomingTxt = File.createTempFile("import_incoming", ".txt", appContext.cacheDir)
        val oldTxt = File.createTempFile("import_existing", ".txt", appContext.cacheDir)
        val mergedTxt = File.createTempFile("import_merged", ".txt", appContext.cacheDir)
        // 输出到目标目录内，保证与目标文件同文件系统，renameTo 才能原子替换
        val outBin = File(existing.file.parentFile, ".merge_tmp_${existing.file.name}")
        try {
            incomingDict.toTextDictionary(incomingTxt)
            existing.toTextDictionary(oldTxt)
            mergedTxt.writeText(
                mergeDictText(
                    oldTxt.readText(),
                    // v1.0.55：新库文本先补全单列纯词组行的拼音列，避免纯词表在合并时被整行丢弃
                    normalizeSingleWordLines(incomingTxt.readText())
                )
            )
            pinyinDictConv(mergedTxt.absolutePath, outBin.absolutePath, MODE_TXT_TO_BIN)
            if (!outBin.renameTo(existing.file)) {
                throw IOException("Failed to replace ${existing.file.absolutePath}")
            }
            LibIMEDictionary(existing.file)
        } finally {
            tempFile.delete()
            incomingTxt.delete()
            oldTxt.delete()
            mergedTxt.delete()
            outBin.delete()
        }
    }

    /**
     * 合并两份 libIME 文本词典（每行：词 拼音 [频率]）。
     * 相同 (拼音, 词) 的条目聚合：freq = max(old, new) + 0.5 * min(old, new)，
     * 使重复出现的词/词组排序概率显著上升；重复导入同一文件也会继续提权。
     * 无频率的条目（2 列行）按 0 处理，输出时省略频率列。
     */
    /**
     * v1.0.55：单行规范化——补全「词 拼音 频率」三列中的缺失拼音列。
     * libIME 文本词典每行必须为「词 拼音 [频率]」两/三列（loadTextImpl 只接受 2/3 token），
     * 单列纯词组行（如 Recall 导出的 recall_phrase_export.txt）会被静默跳过，
     * 产出 0 字节空词库（用户可见「永远显示 0MB」）。
     * 对「仅一个 token 且含汉字」的行：用离线 ICU 拼音（[PinyinUtils]）生成
     * 音节 `'` 连接的全拼列，默认频率 10.0；无法生成拼音的行保持原样（由 libIME 继续跳过）。
     * 已规范的行（2/3 列）原样返回，幂等可重复调用。
     */
    fun normalizeDictLine(line: String): String {
        val tokens = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
        if (tokens.size == 1 && tokens[0].any { it in '\u4E00'..'\u9FFF' }) {
            val word = tokens[0]
            val py = PinyinUtils.toPinyin(word)
                ?.split(' ')
                ?.filter { it.isNotEmpty() }
                ?.joinToString("'")
                ?.takeIf { it.isNotBlank() }
            if (py != null) return "$word $py 10.0"
        }
        return line
    }

    /** 对整段文本逐行做 [normalizeDictLine]（流式场景请改用 normalizeDictLine 逐行处理）。 */
    fun normalizeSingleWordLines(text: String): String =
        text.lineSequence().joinToString("\n") { normalizeDictLine(it) }

    private fun mergeDictText(oldText: String, newText: String): String {
        val map = LinkedHashMap<String, Double>()
        fun feed(line: String) {
            val tokens = line.trim().split(' ', '\t').filter { it.isNotEmpty() }
            if (tokens.size < 2) return
            val freq = if (tokens.size >= 3) tokens[2].toDoubleOrNull() ?: return else 0.0
            val word = tokens[0]
            val pinyin = tokens[1]
            val key = "$pinyin\u0000$word"
            val merged = map[key]
            map[key] = if (merged == null) freq
            else maxOf(merged, freq) + minOf(merged, freq) * 0.5
        }
        oldText.lineSequence().forEach(::feed)
        newText.lineSequence().forEach(::feed)
        return buildString {
            for ((key, freq) in map) {
                val sep = key.indexOf('\u0000')
                append(key, sep + 1, key.length)
                append(' ')
                append(key, 0, sep)
                if (freq != 0.0) {
                    append(' ')
                    append(freq)
                }
                append('\n')
            }
        }
    }

    fun cellDictConv(src: String, dest: String) {
        val process = Runtime.getRuntime()
            .exec(
                arrayOf(scel2org5.absolutePath, "-o", dest, src),
                arrayOf("LD_LIBRARY_PATH=${nativeDir.absolutePath}")
            )
        process.waitFor()
        if (process.exitValue() != 0) {
            throw IOException(process.errorStream.bufferedReader().readText())
        }
    }

    @JvmStatic
    external fun pinyinDictConv(src: String, dest: String, mode: Boolean)

    const val MODE_BIN_TO_TXT = true
    const val MODE_TXT_TO_BIN = false
    private const val scel2org5Name = "libscel2org5.so"

}