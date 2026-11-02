/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.dict

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.errorArg
import java.io.File

class TextDictionary(file: File) : PinyinDictionary() {
    override var file: File = file
        private set

    override val type: Type = Type.Text

    init {
        ensureFileExists()
        if (file.extension != type.ext)
            errorArg(R.string.exception_text_dict_filename, file.name)
    }

    override fun toTextDictionary(dest: File): TextDictionary {
        ensureTxt(dest)
        file.copyTo(dest)
        return TextDictionary(dest)
    }

    override fun toLibIMEDictionary(dest: File): LibIMEDictionary {
        ensureBin(dest)
        // MBM v1.0.55：先把「每行一个纯词组」的单列行补全为「词 拼音 频率」三列，
        // 否则 libIME loadTextImpl 会静默跳过这些行，产出 0 字节空词库
        // （表现：已安装词库里该词库永远显示 0.0 MB 且无候选）。
        // 逐行流式处理，避免超大词表整篇进内存。
        val normalized = File(appContext.cacheDir, "norm_${file.name}")
        try {
            normalized.bufferedWriter().use { out ->
                file.useLines { lines ->
                    lines.forEach { line ->
                        out.write(PinyinDictManager.normalizeDictLine(line))
                        out.write("\n")
                    }
                }
            }
            PinyinDictManager.pinyinDictConv(
                normalized.absolutePath,
                dest.absolutePath,
                PinyinDictManager.MODE_TXT_TO_BIN
            )
        } finally {
            normalized.delete()
        }
        return LibIMEDictionary(dest)
    }
}