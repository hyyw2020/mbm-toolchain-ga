/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * v1.0.84 回归单测：码表导入文件名（来自 SAF DISPLAY_NAME）的路径穿越防护。
 *
 * 真 bug：TableManager.importFromConfAndDict / replaceTableDict 旧实现用
 * File(tempDir, name)，而 name 来自 ContentResolver.queryFileName（DISPLAY_NAME
 * 原样透传，SAF provider 可控）。恶意 name 为绝对路径时 File(parent,child) 忽略
 * parent，为 "../../x" 时逃出 tempDir，可写到应用沙箱任意位置覆盖 databases/。
 * 修复：改用 PathSafety.safeResolve(tempDir, name)，只接受单段安全文件名。
 */
package org.fcitx.fcitx5.android.data.table

import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TableImportPathSafetyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `safeResolve accepts plain file names`() {
        val dir = tmp.root
        val f = TableImportPaths.resolve(dir, "pinyin.conf")
        Assert.assertEquals(File(dir, "pinyin.conf").canonicalPath, f.canonicalPath)
        val d = TableImportPaths.resolve(dir, "wb.dict")
        Assert.assertEquals(File(dir, "wb.dict").canonicalPath, d.canonicalPath)
    }

    @Test
    fun `safeResolve rejects absolute path`() {
        // 修复前 File(tempDir, "/data/data/x") 会忽略 tempDir → 逃逸
        Assert.assertThrows(IllegalArgumentException::class.java) {
            TableImportPaths.resolve(tmp.root, "/data/data/pkg/databases/evil.conf")
        }
    }

    @Test
    fun `safeResolve rejects parent traversal`() {
        Assert.assertThrows(IllegalArgumentException::class.java) {
            TableImportPaths.resolve(tmp.root, "../../databases/evil.conf")
        }
    }

    @Test
    fun `safeResolve rejects backslash traversal`() {
        Assert.assertThrows(IllegalArgumentException::class.java) {
            TableImportPaths.resolve(tmp.root, "..\\..\\shared_prefs\\evil.xml")
        }
    }
}
