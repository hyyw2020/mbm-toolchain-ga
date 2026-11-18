/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.utils

import java.io.File
import java.util.zip.ZipInputStream

/**
 * @return top-level files in zip file
 */
fun ZipInputStream.extract(destDir: File): List<File> {
    var entry = nextEntry
    val canonicalDest = destDir.canonicalPath
    while (entry != null) {
        // 规范化后必须仍在 destDir 内部。注意不能只 startsWith(canonicalDest)：
        // "/tmp/x" 会把 "/tmp/xy" 也判为合法（兄弟目录误放行），必须拼上 File.separator。
        val file = File(destDir, entry.name)
        if (!file.canonicalPath.startsWith(canonicalDest + File.separator)) throw SecurityException()
        if (!entry.isDirectory) {
            // 编程式 zip 常只含 "sub/dir/a.txt" 而无显式目录条目；写文件前先建父目录，
            // 否则 file.outputStream() 因父目录不存在抛 FileNotFoundException，导入整体失败。
            file.parentFile?.mkdirs()
            copyTo(file.outputStream())
        } else {
            file.mkdirs()
        }
        entry = nextEntry
    }
    return destDir.listFiles()?.toList() ?: emptyList()
}
