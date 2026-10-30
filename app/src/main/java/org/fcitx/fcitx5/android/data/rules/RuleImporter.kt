/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 */
package org.fcitx.fcitx5.android.data.rules

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.data.table.TableManager
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 统一规则 / 词库安装器。
 *
 * 所有来源（fcitx5 原生词库、Rime 方案/词典、第三方词库 A、第三方词库 B、自定义词表）
 * 都通过同一个入口安装，安装位置对用户可见：
 *  - Rime：`Android/data/<pkg>/files/data/rime/`
 *  - 拼音：`Android/data/<pkg>/files/data/pinyin/dictionaries/`
 *  - 码表：fcitx5 输入法目录（`Android/data/<pkg>/files/data/table/`）
 */
object RuleImporter {

    data class Item(
        val uri: Uri,
        val fileName: String,
        val format: RuleFormat,
        val sizeBytes: Long,
        val detail: String = "",
        val selected: Boolean = true
    )

    data class Outcome(val item: Item, val ok: Boolean, val message: String)

    // N111：以下是循环内/每次调用都要用到的正则，预编译成常量，避免逐行重复编译
    private val ILLEGAL_FILE_NAME_CHARS = Regex("[\\x00-\\x1f\\\\/:*?\"<>|]")
    private val SCHEMA_LIST_ITEM = Regex("-\\s*schema:\\s*\\S+")
    private val SCHEMA_ID = Regex("schema:\\s*(\\S+)")
    private val WORD_LIST_SEPARATOR = Regex("[\\t ]+")

    // N112：zip 炸弹防护上限。规则 / 词库包正常只有几 MB，以下取值已留足余量。
    private const val MAX_ZIP_FILE_BYTES = 256L * 1024 * 1024   // 压缩包本身
    private const val MAX_ZIP_TOTAL_BYTES = 512L * 1024 * 1024  // 解压后总字节
    private const val MAX_ZIP_ENTRIES = 5000                    // 条目数

    /** N112：zip 体积 / 条目数超限；message 直接展示给用户 */
    private class ZipTooLargeException(message: String) : IllegalArgumentException(message)

    /** Rime 用户数据目录 */
    fun rimeUserDir(): File = File(
        appContext.getExternalFilesDir(null)!!, "data/rime"
    ).also { it.mkdirs() }

    /** 拼音词典目录 */
    fun pinyinDir(): File = File(
        appContext.getExternalFilesDir(null)!!, "data/pinyin/dictionaries"
    ).also { it.mkdirs() }

    // ---------- 分析 ----------

    suspend fun analyze(context: Context, uris: List<Uri>): List<Item> = withContext(Dispatchers.IO) {
        uris.mapNotNull { analyzeOne(context, it) }
    }

    private fun analyzeOne(context: Context, uri: Uri): Item? {
        val resolver = context.contentResolver
        var name = "unknown"
        var size = 0L
        resolver.query(uri, null, null, null, null)?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = it.getString(nameIndex)
                if (sizeIndex >= 0) size = it.getLong(sizeIndex)
            }
        }
        val isZip = isZipFile(context, uri)
        val children = if (isZip) listZipTopLevel(context, uri) else emptyList()
        val head = readHeadText(context, uri, if (isZip) 0 else 8 * 1024)
        val format = RuleFormat.detect(name, isZip, head, children)

        val detail = when {
            isZip && format.target == RuleFormat.InstallTarget.RIME -> {
                val schemas = extractSchemaIds(context, uri)
                if (schemas.isNotEmpty()) "方案：${schemas.joinToString(", ")}" else "Rime 数据包"
            }
            format == RuleFormat.RIME_SCHEMA -> {
                val id = head?.lineSequence()
                    ?.firstOrNull { it.trim().startsWith("schema_id:") }
                    ?.substringAfter(":")?.trim()
                if (id != null) "schema_id：$id" else "Rime 方案"
            }
            format == RuleFormat.RIME_DICT -> {
                val dictName = head?.lineSequence()
                    ?.firstOrNull { it.trim().startsWith("name:") }
                    ?.substringAfter(":")?.trim()
                if (dictName != null) "词典：$dictName" else "Rime 词典"
            }
            format == RuleFormat.PLAIN_WORDLIST -> "将自动转换为 Rime 词典"
            else -> format.description
        }
        return Item(uri, name, format, size, detail, selected = format != RuleFormat.UNKNOWN)
    }

    // ---------- 安装 ----------

    suspend fun install(context: Context, items: List<Item>): List<Outcome> =
        withContext(Dispatchers.IO) {
            val outcomes = mutableListOf<Outcome>()
            items.forEach { item ->
                outcomes += runCatching { installOne(context, item) }
                    .getOrElse { Outcome(item, false, it.localizedMessage ?: "导入失败") }
            }
            outcomes
        }

    private fun installOne(context: Context, item: Item): Outcome {
        return when (item.format.target) {
            RuleFormat.InstallTarget.RIME -> installRime(context, item)
            RuleFormat.InstallTarget.PINYIN -> installPinyin(context, item)
            RuleFormat.InstallTarget.TABLE -> installTable(context, item)
            RuleFormat.InstallTarget.UNKNOWN ->
                Outcome(item, false, appContext.getString(R.string.rule_import_failed, item.fileName))
        }
    }

    private fun installRime(context: Context, item: Item): Outcome {
        val target = rimeUserDir()
        val resolver = context.contentResolver

        return when (item.format) {
            RuleFormat.RIME_SCHEMA, RuleFormat.RIME_DICT -> {
                val dest = File(target, sanitizeFileName(item.fileName))
                resolver.openInputStream(item.uri)?.use { input ->
                    // N111：输出流必须用 use {} 关闭，否则每次导入都泄漏一个 FD
                    dest.outputStream().use { output -> input.copyTo(output) }
                } ?: return Outcome(item, false, "无法读取文件")
                registerSchemaIntoDefault(listOf(schemaIdOf(dest)))
                Outcome(item, true, dest.absolutePath)
            }
            RuleFormat.PLAIN_WORDLIST -> {
                val dest = File(target, sanitizeFileName(item.fileName).removeSuffix(".txt") + ".dict.yaml")
                val lines = resolver.openInputStream(item.uri)?.bufferedReader(Charsets.UTF_8)
                    ?.lineSequence() ?: return Outcome(item, false, "无法读取文件")
                val converted = convertWordList(lines, dest.nameWithoutExtension)
                dest.writeText(converted)
                Outcome(item, true, dest.absolutePath)
            }
            else -> {
                // zip：整体解压到 rime 用户目录（保持包内相对路径）
                // N112：拷贝进 cacheDir 时限制体积，解压时限制条目数与解压后总字节
                val cache = copyZipToCache(context, item.uri, "import")
                val ids = mutableListOf<String>()
                // N112：用 try/finally 兜住解压全过程，超限或出错也不留缓存残骸
                try {
                    val canonicalTarget = target.canonicalPath
                    var entryCount = 0
                    var totalBytes = 0L
                    ZipFile(cache).use { zip ->
                        zip.entries().asSequence().forEach { entry ->
                            if (entry.isDirectory) return@forEach
                            // N112：先算量再落地，超出上限就带着友好提示中止
                            if (++entryCount > MAX_ZIP_ENTRIES) {
                                throw ZipTooLargeException(
                                    "压缩包条目过多（超过 $MAX_ZIP_ENTRIES 个），已取消导入"
                                )
                            }
                            // ZipEntry.size 在流式写入（数据描述符）时为 -1，只累加已知值
                            if (entry.size > 0) {
                                totalBytes += entry.size
                                if (totalBytes > MAX_ZIP_TOTAL_BYTES) {
                                    throw ZipTooLargeException(
                                        "压缩包解压后体积过大（超过 ${MAX_ZIP_TOTAL_BYTES / 1024 / 1024}MB），已取消导入"
                                    )
                                }
                            }
                            // Zip Slip 防护：条目名可含 "../" 或绝对路径，直接 File(target, name)
                            // 会写出目标目录（可覆盖 app 自己的数据库/配置）。规范化后必须仍在
                            // target 内，与 utils/ZipStream.kt 的既有防护保持一致。
                            val dest = File(target, entry.name)
                            val canonicalDest = dest.canonicalPath
                            if (!canonicalDest.startsWith(canonicalTarget + File.separator)) {
                                Timber.w("MBM: skip zip slip entry: ${entry.name}")
                                return@forEach
                            }
                            dest.parentFile?.mkdirs()
                            // N111：输出流必须用 use {} 关闭，否则每个 zip 条目都泄漏一个 FD
                            zip.getInputStream(entry).use { input ->
                                dest.outputStream().use { output -> input.copyTo(output) }
                            }
                            if (dest.name.endsWith("schema.yaml")) {
                                ids += dest.readText().lineSequence()
                                    .firstOrNull { it.trim().startsWith("schema_id:") }
                                    ?.substringAfter(":")?.trim()
                                    ?: dest.name.removeSuffix(".schema.yaml")
                            }
                        }
                    }
                } finally {
                    cache.delete()
                }
                registerSchemaIntoDefault(ids)
                Outcome(item, true, target.absolutePath)
            }
        }
    }

    private fun installPinyin(context: Context, item: Item): Outcome {
        val cache = File(context.cacheDir, "pinyin_${sanitizeFileName(item.fileName)}")
        context.contentResolver.openInputStream(item.uri)?.use { input ->
            // N111：输出流必须用 use {} 关闭，否则每次导入都泄漏一个 FD
            cache.outputStream().use { output -> input.copyTo(output) }
        } ?: return Outcome(item, false, "无法读取文件")
        return PinyinDictManager.importFromFile(cache).fold(
            onSuccess = { Outcome(item, true, it.file.absolutePath) },
            onFailure = { Outcome(item, false, it.localizedMessage ?: "拼音词库转换失败") }
        ).also { cache.delete() }
    }

    private fun installTable(context: Context, item: Item): Outcome {
        val stream = context.contentResolver.openInputStream(item.uri)
            ?: return Outcome(item, false, "无法读取文件")
        // N111：输入流同样要关闭，避免导入失败时 FD 泄漏
        return stream.use {
            TableManager.importFromZip(it).fold(
                onSuccess = { Outcome(item, true, it.name) },
                onFailure = { Outcome(item, false, it.localizedMessage ?: "码表导入失败") }
            )
        }
    }

    // ---------- 工具 ----------

    /**
     * 把 uri 拷进 cacheDir，并限制拷贝字节数。
     *
     * N112：原先是整包无上限拷进内部存储，一个高压缩比的 zip（zip bomb）就能撑满
     * cacheDir。超限抛 [ZipTooLargeException]，由调用方转成友好提示；抛异常时临时
     * 文件会被删掉，不留缓存残骸。成功返回的文件由调用方负责 finally 删除。
     */
    private fun copyZipToCache(context: Context, uri: Uri, prefix: String): File {
        val cache = File(context.cacheDir, "${prefix}_${System.currentTimeMillis()}.zip")
        var copied = 0L
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                // N111：输出流必须用 use {} 关闭，否则每次导入都泄漏一个 FD
                cache.outputStream().use { output ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        copied += n
                        if (copied > MAX_ZIP_FILE_BYTES) {
                            throw ZipTooLargeException(
                                "压缩包过大（超过 ${MAX_ZIP_FILE_BYTES / 1024 / 1024}MB），已取消导入"
                            )
                        }
                        output.write(buf, 0, n)
                    }
                }
            } ?: throw IOException("无法读取文件")
            return cache
        } catch (t: Throwable) {
            // N112：异常路径上也要清掉临时缓存
            cache.delete()
            throw t
        }
    }

    /**
     * 净化文件名：DISPLAY_NAME 完全由提供方（文件管理器/Intent）决定，
     * 可能含路径分隔符、".."、绝对路径前缀，直接 File(dir, name) 会越出目标目录。
     * 只保留 basename 并剔除危险字符；为空时回退时间戳名，保证下游 File() 永不逃逸。
     */
    private fun sanitizeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .removePrefix("/")
            .replace(ILLEGAL_FILE_NAME_CHARS, "_")
            .trim()
            .takeIf { it.isNotBlank() && it != "." && it != ".." }
            ?: "import_${System.currentTimeMillis()}"
        // 兜底：即便 basename 仍是 ".."，替换后也只会是普通名字
        return if (base == ".." || base == ".") "import_${System.currentTimeMillis()}" else base
    }

    /**
     * 把方案追加进 default.custom.yaml 的 schema_list，让用户导入后能直接看到 / 启用。
     */
    private fun registerSchemaIntoDefault(ids: List<String>) {
        val valid = ids.mapNotNull { it.takeIf { s -> s.isNotBlank() } }.distinct()
        if (valid.isEmpty()) return
        val dir = rimeUserDir()
        val default = File(dir, "default.custom.yaml")
        // N108：写前备份，防止覆盖用户其它 patch（ascii_mode/key_binder/menu…）后不可恢复
        if (default.exists()) {
            runCatching { default.copyTo(File(dir, "default.custom.yaml.bak"), overwrite = true) }
        }
        val existing = if (default.exists()) default.readText() else ""

        // 解析已有 schema_list 的 id，并保留 patch: 下的其它兄弟键
        val existingIds = mutableListOf<String>()
        val beforePatch = StringBuilder()
        val afterPatch = StringBuilder()
        var inPatch = false
        var inSchemaList = false
        var patchSeen = false
        for (line in existing.lineSequence()) {
            val trimmed = line.trimStart()
            when {
                trimmed == "patch:" -> {
                    inPatch = true
                    patchSeen = true
                    beforePatch.appendLine(line)
                }
                inPatch && trimmed == "schema_list:" -> {
                    inSchemaList = true
                }
                inSchemaList && trimmed.matches(SCHEMA_LIST_ITEM) -> {
                    SCHEMA_ID.find(line)?.groupValues?.get(1)?.let { existingIds.add(it) }
                }
                inSchemaList && trimmed.isNotBlank() && !trimmed.startsWith("-") && trimmed.contains(":") -> {
                    inSchemaList = false
                    afterPatch.appendLine(line)
                }
                else -> {
                    if (inPatch) afterPatch.appendLine(line) else beforePatch.appendLine(line)
                }
            }
        }

        val merged = (existingIds + valid).distinct()
        val sb = StringBuilder()
        if (!patchSeen) {
            // 原文件无 patch:，整段新建（备份已做）
            sb.appendLine("# MBM 自动维护：由「规则与词库」导入生成")
            sb.appendLine("patch:")
            sb.appendLine("  schema_list:")
            merged.forEach { sb.appendLine("    - schema: $it") }
        } else {
            sb.append(beforePatch)
            if (sb.isNotEmpty() && !sb.endsWith("\n")) sb.appendLine()
            sb.appendLine("# MBM 自动维护：schema_list 由「规则与词库」导入维护（其余 patch 已保留）")
            sb.appendLine("  schema_list:")
            merged.forEach { sb.appendLine("    - schema: $it") }
            if (afterPatch.isNotEmpty()) {
                if (!sb.endsWith("\n")) sb.appendLine()
                sb.append(afterPatch)
            }
        }
        default.writeText(sb.toString())
    }

    private fun schemaIdOf(file: File): String =
        file.readText().lineSequence()
            .firstOrNull { it.trim().startsWith("schema_id:") }
            ?.substringAfter(":")?.trim()
            ?: file.nameWithoutExtension

    /**
     * 纯文本词表 → Rime dict.yaml
     * 兼容两种常见写法：
     *  - 词语<Tab>拼音
     *  - 编码<空格>词语
     */
    private fun convertWordList(lines: Sequence<String>, name: String): String {
        val entries = mutableListOf<String>()
        lines.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            // N111：正则已预编译为常量，不再每行编译一次
            val parts = line.split(WORD_LIST_SEPARATOR, limit = 2)
            if (parts.size != 2) return@forEach
            val (a, b) = parts
            if (a.any { it in '一'..'鿿' }) {
                // 词语在前：词语 拼音
                entries += "$a\t$b"
            } else {
                // 编码在前：编码 词语
                entries += "$b\t$a"
            }
        }
        val sb = StringBuilder()
        sb.appendLine("# Rime dictionary —— 由 MBM 统一导入自动生成")
        sb.appendLine("# source: custom word list")
        sb.appendLine("---")
        sb.appendLine("name: $name")
        sb.appendLine("version: \"1.0\"")
        sb.appendLine("sort: by_weight")
        sb.appendLine("use_preset_vocabulary: false")
        sb.appendLine("...")
        entries.forEach { sb.appendLine("$it\t1000") }
        return sb.toString()
    }

    fun exportZip(items: List<File>, dest: File): Boolean = runCatching {
        ZipOutputStream(dest.outputStream()).use { zip ->
            items.forEach { file ->
                if (!file.exists()) return@forEach
                val entry = ZipEntry(file.name)
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        true
    }.getOrDefault(false)

    private fun isZipFile(context: Context, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(2)
                input.read(head)
                head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            } ?: false
        }.getOrDefault(false)
    }

    private fun listZipTopLevel(context: Context, uri: Uri): List<String> {
        return runCatching {
            // N112：探测同样要限制体积，并用 try/finally 清掉临时缓存
            val cache = copyZipToCache(context, uri, "zipprobe")
            try {
                ZipFile(cache).use { zip ->
                    zip.entries().asSequence().map { it.name }.toList()
                }
            } finally {
                cache.delete()
            }
        }.getOrDefault(emptyList())
    }

    /** 从 zip 内所有 schema.yaml 中抽取 schema_id */
    private fun extractSchemaIds(context: Context, uri: Uri): List<String> {
        return runCatching {
            // N112：探测同样要限制体积，并用 try/finally 清掉临时缓存
            val cache = copyZipToCache(context, uri, "schema_probe")
            try {
                ZipFile(cache).use { zip ->
                    zip.entries().asSequence()
                        .filter { it.name.endsWith("schema.yaml") }
                        .mapNotNull { entry ->
                            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8)
                                .lineSequence()
                                .firstOrNull { line -> line.trim().startsWith("schema_id:") }
                                ?.substringAfter(":")?.trim()
                                ?: File(entry.name).nameWithoutExtension
                        }.toList()
                }
            } finally {
                cache.delete()
            }
        }.getOrDefault(emptyList())
    }

    private fun readHeadText(context: Context, uri: Uri, limit: Int): String? {
        if (limit <= 0) return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(limit)
                val n = input.read(buf)
                if (n <= 0) null else String(buf, 0, n, Charsets.UTF_8)
            }
        }.getOrNull()
    }
}
