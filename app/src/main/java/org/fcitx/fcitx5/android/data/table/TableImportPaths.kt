/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 */
package org.fcitx.fcitx5.android.data.table

import org.fcitx.fcitx5.android.utils.safeResolve
import java.io.File

/**
 * 码表导入时把外部文件名（来自 SAF DISPLAY_NAME）解析为临时目录下的落盘文件。
 *
 * 单独抽成纯 JVM 对象以便单测覆盖。v1.0.84 真 bug：旧实现为裸 `File(tempDir, name)`，
 * 而 name 来自 SAF DISPLAY_NAME（文档提供者可控）——绝对路径会令 File 忽略 parent，
 * `../../` 会逃出临时目录，可把内容写到应用沙箱任意位置覆盖 databases/。
 * 现统一走 [safeResolve]：只接受单段文件名，拒绝绝对路径、`..` / `.` 段、分隔符与 NUL。
 */
internal object TableImportPaths {
    fun resolve(tempDir: File, name: String): File = safeResolve(tempDir, name)
}
