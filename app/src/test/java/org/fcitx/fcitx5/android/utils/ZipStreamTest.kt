/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * v1.0.84 回归单测：ZipInputStream.extract 对「无显式目录条目的嵌套文件」的处理。
 *
 * 真 bug：extract 旧实现直接 copyTo(file.outputStream())，若 zip 只含
 * "sub/dir/a.txt" 而没有 "sub/" / "sub/dir/" 目录条目（大量编程式 zip 生成器如此），
 * 父目录不存在 → FileNotFoundException → 备份/主题/码表导入整体失败。
 * 修复：写文件前 file.parentFile?.mkdirs()。canonical 路径穿越防护保持不变。
 */
package org.fcitx.fcitx5.android.utils

import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class ZipStreamTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 造一个仅含嵌套文件、不含任何目录条目的 zip。 */
    private fun nestedZip(): ByteArray = ByteArrayOutputStream().use { bos ->
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("sub/dir/a.txt"))
            zos.write("hello".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        bos.toByteArray()
    }

    @Test
    fun `extract creates missing parent dirs for nested file without directory entry`() {
        // 修复前：父目录 sub/dir 不存在，copyTo(file.outputStream()) 抛 FileNotFoundException → FAILED
        val dest = tmp.newFolder("out")
        ByteArrayInputStream(nestedZip()).use { input ->
            val extracted = ZipInputStream(input).extract(dest)
            Assert.assertEquals(1, extracted.size) // 顶层只有 sub 目录
        }
        val nested = File(dest, "sub/dir/a.txt")
        Assert.assertTrue("nested file should be extracted", nested.exists())
        Assert.assertEquals("hello", nested.readText(Charsets.UTF_8))
    }

    @Test
    fun `extract still rejects zip slip`() {
        // 回归保护：canonical 穿越防护必须保留
        val bytes = ByteArrayOutputStream().use { bos ->
            ZipOutputStream(bos).use { zos ->
                zos.putNextEntry(ZipEntry("../evil.txt"))
                zos.write("x".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            bos.toByteArray()
        }
        val dest = tmp.newFolder("slip")
        Assert.assertThrows(SecurityException::class.java) {
            ByteArrayInputStream(bytes).use { ZipInputStream(it).extract(dest) }
        }
    }
}
