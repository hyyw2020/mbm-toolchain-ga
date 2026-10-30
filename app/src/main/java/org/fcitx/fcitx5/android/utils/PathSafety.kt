/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2024 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.utils

import java.io.File

/**
 * 路径遍历（Zip-Slip / DocumentsProvider docId / 外部文件名注入）统一防护。
 *
 * 任何「父目录 + 外部传入的子路径或文件名」的构造都必须经过这里，
 * 确保最终文件始终落在预期的父目录（或其自身）之内。
 */

private val ILLEGAL_CHILD_CHARS = charArrayOf(File.separatorChar, '/', '\\', '\u0000')

/**
 * 校验并规整为单段文件名。
 * 拒绝：空、含路径分隔符、含 `..` / `.` 段、含 NUL。
 * @throws IllegalArgumentException 当名称非法时
 */
fun sanitizeChildName(name: String): String {
    val trimmed = name.trim()
    require(trimmed.isNotEmpty()) { "Empty child name" }
    require(ILLEGAL_CHILD_CHARS.none { it in trimmed }) {
        "Child name must not contain path separators or NUL: '$name'"
    }
    // 拆分并逐个校验，禁止任何 . 或 .. 段（防御 URL 解码后的跨目录）
    val segments = trimmed.split('/', '\\', File.separatorChar)
    require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) {
        "Child name must be a single path segment: '$name'"
    }
    return trimmed
}

/**
 * 判断 [child] 是否位于 [base] 目录内（或即为其自身）。
 * 使用规范化（canonical）路径比较，可抵御 `..`、符号链接等绕过。
 */
fun isWithin(child: File, base: File): Boolean {
    val canonicalBase = base.canonicalPath
    val canonicalChild = child.canonicalPath
    return canonicalChild == canonicalBase ||
        canonicalChild.startsWith(canonicalBase + File.separator)
}

/**
 * 在 [parent] 下安全解析一个**单段文件名**，返回的子文件必须位于 [parent] 目录内。
 * @throws IllegalArgumentException 当名称非法或逃逸时
 */
fun safeResolve(parent: File, childName: String): File {
    val name = sanitizeChildName(childName)
    val child = File(parent, name)
    require(isWithin(child, parent)) { "Path traversal attempt: '$childName' escapes '${parent.path}'" }
    return child
}

/**
 * 解析一个（可为多段的）相对路径 [relativePath]，强制其结果落在 [baseDir] 内。
 * 用于 DocumentsProvider 的 docId、主题导入的相对路径等场景。
 * @throws IllegalArgumentException 当结果逃逸出 baseDir 时
 */
fun safeResolveWithin(baseDir: File, relativePath: String): File {
    val child = File(baseDir, relativePath)
    require(isWithin(child, baseDir)) { "Path traversal attempt: '$relativePath' escapes '${baseDir.path}'" }
    return child
}
