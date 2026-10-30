/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.pinyin.dict

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.utils.errorArg
import java.io.File

class CellDictionary(file: File) : PinyinDictionary() {
    override var file: File = file
        private set

    override val type: Type = Type.Cell

    init {
        ensureFileExists()
        if (file.extension != type.ext)
            errorArg(R.string.exception_cell_dict_filename, file.name)
    }

    override fun toTextDictionary(dest: File): TextDictionary {
        ensureTxt(dest)
        PinyinDictManager.cellDictConv(file.absolutePath, dest.absolutePath)
        return TextDictionary(dest)
    }

    override fun toLibIMEDictionary(dest: File): LibIMEDictionary {
        val txtDict = toTextDictionary()
        val libimeDict = txtDict.toLibIMEDictionary(dest)
        txtDict.file.delete()
        return libimeDict
    }

}
