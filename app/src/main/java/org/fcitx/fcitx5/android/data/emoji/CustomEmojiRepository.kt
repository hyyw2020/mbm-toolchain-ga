/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * 自定义图像表情（sticker）本地仓库。
 *
 * 资产 100% 由用户在运行时通过系统文件选择器（Android Storage Access
 * Framework）导入，落在本应用私有目录 filesDir/custom_emoji/<packId>/，
 * 并附 pack.json 清单。APK 内**不预置任何第三方专有表情资产**，也不
 * 联网下载任何素材；导入、索引、渲染、删除全部在本机完成。
 *
 * 富媒体输出使用 AndroidX Core 的 FileProvider 生成 content:// URI，
 * 由 Rich Content API 在提交时做临时读授权（见 EmojiRichContentCommitter）。
 *
 * 所有公开方法均为纯本地、可重入、异常安全：任何单个损坏资产都不会使
 * 仓库整体不可用，也不会令输入法进程崩溃。
 */
package org.fcitx.fcitx5.android.data.emoji

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.data.password.PrivacyController
import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

object CustomEmojiRepository {

    private const val ROOT_DIR = "custom_emoji"
    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    private const val CLIPBOARD_PACK_ID = "clipboard"
    private const val ZIP_MAGIC_SIZE = 4

    /**
     * v1.0.76：剪贴板导入串行化。旧实现「读清单 → 写 tmp → 重命名 → 重写清单 →
     * 删孤儿」全程无同步，用户连续快速复制多个表情时多个 onPrimaryClipChanged
     * 协程会并发执行，互相覆盖 pack.json 并删掉对方刚落盘的文件，导致条目丢失、
     * 清单与实际文件不一致（面板出现空白格）。
     */
    private val clipboardImportLock = Any()

    /** 每次导入/删除自增，供表情面板判断是否需要重建分类页。 */
    @Volatile
    var revision: Int = 0
        private set

    private val root: File
        get() = File(appContext.filesDir, ROOT_DIR)

    private fun packDir(packId: String): File? {
        val id = CustomEmojiProtocol.safePackId(packId) ?: return null
        return File(root, id)
    }

    private fun authority(context: Context) = context.packageName + FILE_PROVIDER_SUFFIX

    // ------------------------------------------------------------------ 查询

    /** 读取全部包（损坏清单自动跳过；清单缺失时按目录内容重建）。 */
    fun listPacks(): List<CustomEmojiPack> {
        val dir = root
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { readPack(it) }
            .sortedBy { it.createdMs }
    }

    fun pack(packId: String): CustomEmojiPack? = packDir(packId)?.let { readPack(it) }

    private fun readPack(dir: File): CustomEmojiPack? {
        val id = CustomEmojiProtocol.safePackId(dir.name) ?: return null
        val manifest = File(dir, CustomEmojiProtocol.MANIFEST_NAME)
        val parsed = if (manifest.isFile) {
            runCatching { CustomEmojiProtocol.decodeManifest(manifest.readText()) }.getOrNull()
        } else null
        if (parsed != null) return parsed
        return rebuildManifest(dir, id)
    }

    /** 清单损坏或用户手动往私有目录放入图片时，按目录内容重建。 */
    private fun rebuildManifest(dir: File, id: String): CustomEmojiPack? {
        val files = dir.listFiles { f -> f.isFile && CustomEmojiProtocol.mimeFor(f.name) != null }
            .orEmpty()
            .sortedBy { it.name }
        if (files.isEmpty()) return null
        val items = files.take(CustomEmojiProtocol.MAX_ITEMS_PER_PACK).map { f ->
            CustomEmojiItem(
                file = f.name,
                label = CustomEmojiProtocol.labelFromFileName(f.name),
                mime = CustomEmojiProtocol.mimeFor(f.name) ?: "image/png",
                size = f.length()
            )
        }
        val pack = CustomEmojiPack(
            id = id,
            name = if (id == CLIPBOARD_PACK_ID) "剪贴板导入" else id,
            createdMs = dir.lastModified(),
            items = items
        )
        runCatching { File(dir, CustomEmojiProtocol.MANIFEST_NAME).writeText(CustomEmojiProtocol.encodeManifest(pack)) }
        return pack
    }

    /** 供表情面板构造分类页的 token 列表；未导入任何资产时返回空列表。 */
    fun allTokens(): List<String> = listPacks().flatMap { p ->
        p.items.map { CustomEmojiProtocol.token(p.id, it.file) }
    }

    fun itemCount(): Int = listPacks().sumOf { it.items.size }

    /** 按面板 token 解析出（文件、MIME、显示名），严格校验，绝不越界。 */
    fun resolve(token: String): Resolved? {
        val (packId, file) = CustomEmojiProtocol.parseToken(token) ?: return null
        val dir = packDir(packId) ?: return null
        val f = File(dir, file)
        if (!f.isFile || f.parentFile?.canonicalPath != dir.canonicalPath) return null
        val mime = CustomEmojiProtocol.mimeFor(file) ?: return null
        val label = readPack(dir)?.items?.find { it.file == file }?.label
            ?: CustomEmojiProtocol.labelFromFileName(file)
        return Resolved(packId, f, mime, label)
    }

    data class Resolved(val packId: String, val file: File, val mime: String, val label: String)

    /** FileProvider content:// URI（供 commitContent 使用）。 */
    fun contentUri(context: Context, resolved: Resolved): Uri? = runCatching {
        FileProvider.getUriForFile(context, authority(context), resolved.file)
    }.onFailure { Timber.w(it, "CustomEmoji: FileProvider uri failed") }.getOrNull()

    /** 缩略图解码：两级 inSampleSize 降采样，动画资源只取首帧。 */
    fun thumbnail(resolved: Resolved, targetPx: Int = 128): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(resolved.file.absolutePath, bounds)
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / sample > targetPx * 2 && sample < 64) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(resolved.file.absolutePath, opts)
    }.onFailure { Timber.w(it, "CustomEmoji: thumbnail decode failed") }.getOrNull()

    // 缩略图内存缓存：面板滚动/绑定是主线程，绝不能在那里解码用户导入的大图。
    // 导入完成后后台预解码，绑定时只做一次内存查表（微秒级），满足低延迟目标。
    private val thumbCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var preloadedRevision = -1

    /** 幂等后台预解码；revision 未变化时不重复做 IO。 */
    fun preloadThumbnails() {
        val rev = revision
        if (rev == preloadedRevision) return
        preloadedRevision = rev
        preloadScope.launch {
            allTokens().forEach { token ->
                val r = resolve(token) ?: return@forEach
                val bmp = thumbnail(r) ?: return@forEach
                synchronized(thumbCache) { thumbCache.put(token, bmp) }
            }
        }
    }

    fun cachedThumbnail(token: String): Bitmap? = synchronized(thumbCache) { thumbCache.get(token) }

    private fun invalidateThumbnailCache() {
        synchronized(thumbCache) { thumbCache.evictAll() }
        preloadedRevision = -1
    }

    /** 资产变更后统一自增：同时让缩略图缓存失效，避免面板显示已删除的旧图。 */
    private fun bumpRevision() {
        revision++
        invalidateThumbnailCache()
    }

    // ------------------------------------------------------------------ 导入

    /**
     * 从 SAF URI 列表导入。支持：
     *  - 单张 / 多张 `image/xxx` 文件；
     *  - ZIP 包（内部图像条目，可选自带 pack.json 作为显示名来源）。
     *
     * 返回成功写入的条目数。任何失败项只跳过，不影响其余条目。
     */
    fun importFromUris(context: Context, uris: List<Uri>, packName: String? = null): Int {
        if (uris.isEmpty()) return 0
        val packId = CustomEmojiProtocol.safePackId(
            "pack_" + System.currentTimeMillis().toString(36)
        ) ?: return 0
        val dir = packDir(packId) ?: return 0
        if (!dir.mkdirs() && !dir.isDirectory) return 0

        var totalBytes = 0L
        val items = ArrayList<CustomEmojiItem>()
        for (uri in uris) {
            if (items.size >= CustomEmojiProtocol.MAX_ITEMS_PER_PACK) break
            val displayName = queryDisplayName(context, uri) ?: "sticker.png"
            if (isZip(context, uri, displayName)) {
                val (fromZip, bytes) = importZip(context, uri, dir, items.size)
                items.addAll(fromZip)
                totalBytes += bytes
            } else {
                val item = copyOne(context, uri, dir, displayName) ?: continue
                if (totalBytes + item.size > CustomEmojiProtocol.MAX_IMPORT_BYTES) {
                    File(dir, item.file).delete()
                    continue
                }
                totalBytes += item.size
                items.add(item)
            }
        }
        if (items.isEmpty()) {
            dir.deleteRecursively()
            return 0
        }
        val pack = CustomEmojiPack(
            id = packId,
            name = (packName?.take(CustomEmojiProtocol.MAX_NAME_LENGTH) ?: "自定义表情 ${listPacks().size + 1}"),
            createdMs = System.currentTimeMillis(),
            items = items.distinctBy { it.file }
        )
        runCatching {
            File(dir, CustomEmojiProtocol.MANIFEST_NAME).writeText(CustomEmojiProtocol.encodeManifest(pack))
        }
        bumpRevision()
        return pack.items.size
    }

    private fun copyOne(context: Context, uri: Uri, dir: File, displayName: String): CustomEmojiItem? {
        val name = CustomEmojiProtocol.safeFileName(displayName) ?: return null
        val mime = CustomEmojiProtocol.mimeFor(name) ?: return null
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        var written = 0L
        val ok = runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: return@runCatching false
            input.use { src ->
                tmp.outputStream().use { dst ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        written += n
                        if (written > CustomEmojiProtocol.MAX_ITEM_BYTES) return@runCatching false
                        dst.write(buf, 0, n)
                    }
                }
            }
            if (written == 0L) return@runCatching false
            tmp.renameTo(target)
        }.onFailure { Timber.w(it, "CustomEmoji: copy failed") }.getOrDefault(false)
        if (!ok) {
            tmp.delete()
            return null
        }
        return CustomEmojiItem(name, CustomEmojiProtocol.labelFromFileName(name), mime, written)
    }

    private fun importZip(
        context: Context, uri: Uri, dir: File, already: Int
    ): Pair<List<CustomEmojiItem>, Long> {
        val items = ArrayList<CustomEmojiItem>()
        var total = 0L
        val zip = runCatching {
            ZipInputStream(context.contentResolver.openInputStream(uri))
        }.getOrNull() ?: return items to 0L
        zip.use { z ->
            while (true) {
                val entry = runCatching { z.nextEntry }.getOrNull() ?: break
                if (entry.isDirectory) continue
                // Zip Slip 防护：只取末段文件名并净化，绝不使用条目自带路径
                val leaf = entry.name.substringAfterLast('/').substringAfterLast('\\')
                val name = CustomEmojiProtocol.safeFileName(leaf) ?: continue
                val mime = CustomEmojiProtocol.mimeFor(name) ?: continue
                if (already + items.size >= CustomEmojiProtocol.MAX_ITEMS_PER_PACK) break
                if (total >= CustomEmojiProtocol.MAX_IMPORT_BYTES) break
                val target = File(dir, name)
                val tmp = File(dir, "$name.tmp")
                var written = 0L
                var aborted = false
                runCatching {
                    tmp.outputStream().use { dst ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = z.read(buf)
                            if (n < 0) break
                            written += n
                            if (written > CustomEmojiProtocol.MAX_ITEM_BYTES) {
                                aborted = true
                                break
                            }
                            dst.write(buf, 0, n)
                        }
                    }
                }.onFailure { aborted = true }
                if (aborted || written == 0L) {
                    tmp.delete()
                    continue
                }
                if (!tmp.renameTo(target)) {
                    tmp.delete()
                    continue
                }
                total += written
                items.add(
                    CustomEmojiItem(name, CustomEmojiProtocol.labelFromFileName(name), mime, written)
                )
            }
        }
        return items to total
    }

    private fun isZip(context: Context, uri: Uri, displayName: String): Boolean {
        if (displayName.endsWith(".zip", ignoreCase = true)) return true
        val declared = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        if (declared == "application/zip" || declared == "application/x-zip-compressed") return true
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { s ->
                val head = ByteArray(ZIP_MAGIC_SIZE)
                val n = s.read(head)
                n == ZIP_MAGIC_SIZE && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
            } ?: false
        }.getOrDefault(false)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull()

    /**
     * 剪贴板复制侧：把系统剪贴板里可读取的图像资产落到专用包，使其在
     * 表情面板内可见、可再次以富媒体方式提交。
     *
     * v1.0.75：不再信任剪贴板声明的 MIME（宿主应用经常不声明、声明错误，
     * 或只给 content URI / 短码文本）。这里**一次打开输入流**，边读边用
     * 文件头魔数嗅探真实位图格式，再按真实格式选扩展名落盘：
     *  - 真实图像（PNG/JPEG/GIF/WebP/BMP/HEIC/AVIF）→ 正常入库；
     *  - 非图像或短码文本 → 直接丢弃，绝不把乱码当表情存进去。
     * 单流读取同时规避 content URI 临时读授权可能只可消费一次的风险。
     * 调用方须已在隐私模式下跳过（见 ClipboardManager）。
     *
     * v1.0.76 真 bug 修复（不改变任何既有功能）：
     *  1. 文件头改为**逐段累积 + 按有效字节数判定**（ImageHeaderSniffer）。
     *     `InputStream.read` 允许分块返回（ContentProvider 基于 pipe 时常见），
     *     旧实现只在第一次 read 后就把固定长 16 的数组拿去嗅探，PNG 的 8 字节
     *     签名只填进 4 字节即被误判为「非图像」，真表情被静默丢弃。
     *  2. 去掉「达到 500 条就 return false」的硬拒绝：它使尾部 takeLast 淘汰
     *     逻辑成为死代码，用户累计复制 500 次后新表情**永久无法加入**。
     *  3. 文件名由内容摘要派生：重复复制同一张图不再新增条目、不消耗配额，
     *     只把它刷新为「最近使用」；配合 ClipboardStickerPackPolicy 真正保留
     *     最近 N 条。否则反复复制几张图就会把配额吃光、功能彻底失效。
     *  4. 全程串行化 + 过滤清单中指向已删除文件的幽灵条目，修并发覆盖与
     *     面板空白格。
     */
    fun importClipboardImage(context: Context, uri: Uri): Boolean =
        synchronized(clipboardImportLock) { importClipboardImageLocked(context, uri) }

    private fun importClipboardImageLocked(context: Context, uri: Uri): Boolean {
        if (PrivacyController.inPrivacyMode) return false
        val dir = packDir(CLIPBOARD_PACK_ID) ?: return false
        if (!dir.mkdirs() && !dir.isDirectory) return false

        val tmp = File(dir, "clip_${System.nanoTime()}.tmp")
        var written = 0L
        val sniffer = ImageHeaderSniffer()
        val ok = runCatching {
            val input = context.contentResolver.openInputStream(uri) ?: return@runCatching false
            input.use { src ->
                tmp.outputStream().use { dst ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = src.read(buf)
                        if (n < 0) break
                        // 逐段累积：read 可能只返回几个字节，也可能返回 0
                        sniffer.accept(buf, 0, n)
                        written += n
                        if (written > CustomEmojiProtocol.MAX_ITEM_BYTES) return@runCatching false
                        dst.write(buf, 0, n)
                    }
                }
            }
            written > 0 && sniffer.result() != null
        }.onFailure { Timber.w(it, "CustomEmoji: clipboard sniff failed") }.getOrDefault(false)
        if (!ok) {
            tmp.delete()
            return false
        }

        val mime = sniffer.result() ?: return false.also { tmp.delete() }
        val ext = ClipboardStickerExtractor.extensionForMime(mime)
        val digest = runCatching { sha256Hex(tmp) }.getOrNull()
            ?: return false.also { tmp.delete() }
        val name = "clip_$digest$ext"
        val target = File(dir, name)
        // 同内容重复复制：renameTo 覆盖同名文件（Linux 语义）；若目标已存在且
        // rename 失败，内容摘要相同，直接复用已有文件即可，不必报错。
        if (!tmp.renameTo(target) && !target.isFile) {
            tmp.delete()
            return false
        }
        tmp.delete()

        val existing = readPack(dir)
        // 清单可能残留指向已删除文件的幽灵条目，先过滤，避免面板出现空白格
        val live = (existing?.items ?: emptyList()).filter { File(dir, it.file).isFile }
        val item = CustomEmojiItem(
            file = name,
            label = CustomEmojiProtocol.labelFromFileName(name),
            mime = mime,
            size = target.length()
        )
        val items = ClipboardStickerPackPolicy.merge(live, item)
        val keep = ClipboardStickerPackPolicy.keepFiles(items)
        dir.listFiles { f -> f.isFile && f.name != CustomEmojiProtocol.MANIFEST_NAME && f.name !in keep }
            ?.forEach { it.delete() }
        val pack = CustomEmojiPack(
            id = CLIPBOARD_PACK_ID,
            name = "剪贴板导入",
            createdMs = System.currentTimeMillis(),
            items = items
        )
        runCatching {
            File(dir, CustomEmojiProtocol.MANIFEST_NAME).writeText(CustomEmojiProtocol.encodeManifest(pack))
        }
        bumpRevision()
        return true
    }

    /** 文件内容的 SHA-256 十六进制（用于剪贴板表情按内容去重）。 */
    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------------ 删除

    fun deletePack(packId: String): Boolean {
        val dir = packDir(packId) ?: return false
        val ok = dir.deleteRecursively()
        if (ok) bumpRevision()
        return ok
    }

    fun clearAll(): Boolean {
        val ok = root.deleteRecursively()
        if (ok) bumpRevision()
        return ok
    }

    /** 供提交侧显式授予目标编辑器临时读权限（双保险，见 Rich Content API 规范）。 */
    fun grantReadPermission(context: Context, uri: Uri, targetPackage: String?) {
        if (targetPackage.isNullOrEmpty()) return
        runCatching {
            context.grantUriPermission(targetPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

}
