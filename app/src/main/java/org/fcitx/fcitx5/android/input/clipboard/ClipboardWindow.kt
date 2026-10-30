/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.Keep
import java.io.InputStream
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.paging.CombinedLoadStates
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.google.android.material.snackbar.BaseTransientBottomBar.BaseCallback
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.SnackbarContentLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.db.ClipboardEntry
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.BooleanKey.ClipboardDbEmpty
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.BooleanKey.ClipboardListeningEnabled
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.AddMore
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.EnableListening
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.State.Normal
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.TransitionEvent.ClipboardDbUpdated
import org.fcitx.fcitx5.android.input.clipboard.ClipboardStateMachine.TransitionEvent.ClipboardListeningUpdated
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.menu.ImeMenuPopup
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.ui.main.ClipboardImportActivity
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.MbmDebug
import org.fcitx.fcitx5.android.utils.EventStateMachine
import org.fcitx.fcitx5.android.utils.imeExceptionHandler
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.dsl.core.withTheme
import timber.log.Timber

class ClipboardWindow : InputWindow.ExtendedInputWindow<ClipboardWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val windowManager: InputWindowManager by manager.must()
    private val theme by manager.theme()

    private val snackbarCtx by lazy {
        context.withTheme(R.style.InputViewSnackbarTheme)
    }
    private var snackbarInstance: Snackbar? = null

    private lateinit var stateMachine: EventStateMachine<ClipboardStateMachine.State, ClipboardStateMachine.TransitionEvent, ClipboardStateMachine.BooleanKey>

    @Keep
    private val clipboardEnabledListener = ManagedPreference.OnChangeListener<Boolean> { _, it ->
        stateMachine.push(
            ClipboardListeningUpdated, ClipboardListeningEnabled to it
        )
    }

    @Keep
    private val maskSensitiveListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        // N96：隐藏敏感信息开关变化时，立即重绑当前可见条目，使明文/打码切换即时生效
        runCatching { adapter.notifyDataSetChanged() }
    }

    private val prefs = AppPrefs.getInstance().clipboard

    private val clipboardEnabledPref = prefs.clipboardListening
    private val clipboardReturnAfterPaste by prefs.clipboardReturnAfterPaste
    private val clipboardMaskSensitive by prefs.clipboardMaskSensitive

    private val clipboardEntryRadius by ThemeManager.prefs.clipboardEntryRadius

    private val clipboardEntriesPager by lazy {
        // U05：PagingConfig 不设 maxSize 时，翻过的每一页都会永久驻留内存 → 翻历史必 OOM。
        // 这里按 10 页（160 条）封顶；Room Paging 下 initialLoadSize 会随 maxSize 放大，
        // 必须显式设成 2 页，否则首屏就要装载/渲染 160 条，面板打开变慢。
        Pager(
            PagingConfig(
                pageSize = PAGE_SIZE,
                initialLoadSize = PAGE_SIZE * 2,
                maxSize = PAGE_SIZE * 10
            )
        ) { ClipboardManager.allEntries() }
    }
    private var adapterSubmitJob: Job? = null

    // N45：LoadState 监听必须与 attach/detach 成对，否则每次打开面板都叠加一个监听，
    // 同一事件被 push 多次 → 状态机反复跳变、界面抖动。
    private var loadStateListener: ((CombinedLoadStates) -> Unit)? = null

    // U07：连点去重——记录上一次「操作 + 条目」及时间，窗口内重复触发直接丢弃
    private var lastActionKey: String? = null
    private var lastActionAtMs = 0L

    /**
     * U07：同一条目的同一操作在 [CLICK_DEDUPE_WINDOW_MS] 内只允许触发一次。
     * 连点会赶在列表刷新前重复粘贴/删除/置顶，表现为「点一下粘两遍」。
     */
    private fun claimAction(key: String): Boolean {
        val now = SystemClock.uptimeMillis()
        if (key == lastActionKey && now - lastActionAtMs < CLICK_DEDUPE_WINDOW_MS) return false
        lastActionKey = key
        lastActionAtMs = now
        return true
    }

    /**
     * U09：校验点击目标仍是列表里同一条（id 相同且 sha256 未变）。
     * 异步拉取/粘贴期间列表刷新或 ViewHolder 复用会让结果落到错条目（点 A 粘 B），
     * 目标已删除或已变化就丢弃本次结果；列表还没加载出来时无法校验，放行以免误伤。
     */
    private fun isTargetIntact(id: Int, sha256: String): Boolean {
        // 快照里可能夹着未加载的占位项（null），显式按可空类型处理
        val items: List<ClipboardEntry?> =
            runCatching { adapter.snapshot().items }.getOrNull() ?: return true
        if (items.isEmpty()) return true
        val current = items.firstOrNull { it?.id == id } ?: return false
        return current.sha256 == sha256
    }

    private val adapter: ClipboardAdapter by lazy {
        object : ClipboardAdapter(
            theme,
            context.dp(clipboardEntryRadius.toFloat()),
            { clipboardMaskSensitive }   // N96：传实时委托，开关改动后立即生效（不再构造期快照）
        ) {
            override fun onPin(id: Int) {
                // U07：连点去重
                if (!claimAction("pin:$id")) return
                service.lifecycleScope.launch { ClipboardManager.pin(id) }
            }

            override fun onUnpin(id: Int) {
                // U07：连点去重
                if (!claimAction("unpin:$id")) return
                service.lifecycleScope.launch { ClipboardManager.unpin(id) }
            }

            override fun onEdit(id: Int) {
                // U07：连点去重（否则会连开两个编辑页）
                if (!claimAction("edit:$id")) return
                AppUtil.launchClipboardEdit(context, id)
            }

            override fun onShare(entry: ClipboardEntry) {
                // U07：连点去重
                if (!claimAction("share:${entry.id}:${entry.sha256}")) return
                // U09：把点击目标（稳定 id + sha256）带进异步流程，回来时校验
                val targetId = entry.id
                val targetSha = entry.sha256
                service.lifecycleScope.launch(imeExceptionHandler) {
                    try {
                        shareEntry(entry, targetId, targetSha)
                    } catch (e: Throwable) {
                        Timber.e(e, "分享剪贴板条目失败")
                    }
                }
            }

            override fun onDelete(id: Int) {
                // U07：连点去重
                if (!claimAction("delete:$id")) return
                service.lifecycleScope.launch(imeExceptionHandler) {
                    try {
                        ClipboardManager.delete(id)
                        showUndoSnackbar(id)
                    } catch (e: Throwable) {
                        // 删除（Room 写库）或撤销 Snackbar 任一环节抛异常都不得令输入法进程崩溃
                        Timber.e(e, "删除剪贴板条目失败")
                    }
                }
            }

            override fun onViewFullText(entry: ClipboardEntry) {
                // U07：连点去重
                if (!claimAction("view:${entry.id}:${entry.sha256}")) return
                // U1 修复：提供可达的「查看全文」入口（blob 条目全文在文件里，不在 text 列）
                service.lifecycleScope.launch(imeExceptionHandler) {
                    try {
                        showFullText(entry)
                    } catch (e: Throwable) {
                        Timber.e(e, "查看剪贴板全文失败")
                    }
                }
            }

            override fun onPaste(entry: ClipboardEntry) {
                MbmDebug.logFeature("clipboard.paste", "window id=${entry.id}")
                // U07：连点去重——同一条目短时间内只粘一次
                if (!claimAction("paste:${entry.id}:${entry.sha256}")) return
                // U09：目标与当前列表不一致（已删除/已被替换）就不粘，避免「点 A 粘 B」
                if (!isTargetIntact(entry.id, entry.sha256)) return
                // MBM v1.0.31：大文本走分块粘贴（100MB 完整原文），小文本行为不变
                service.pasteClipboardEntry(entry)
                if (clipboardReturnAfterPaste) windowManager.attachWindow(KeyboardWindow)
            }
        }
    }

    /**
     * MBM v1.0.31：分享条目全文。小文本走 EXTRA_TEXT 原路；
     * 大文本（blob）经 FileProvider 流式分享——EXTRA_TEXT 同样受 Binder ~1MB 限制。
     */
    private suspend fun shareEntry(entry: ClipboardEntry, targetId: Int, targetSha: String) {
        if (!entry.isBlob) {
            val target = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, entry.text)
            }
            val chooser = Intent.createChooser(target, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(chooser)
            return
        }
        // N33：大文本明文解密与落盘移到 IO 线程，避免主线程 I/O 卡顿；
        // 分享目录集中管理并在写入前/退出时清理，不再把明文永久留在 cacheDir。
        val file = withContext(Dispatchers.IO) {
            val dir = java.io.File(service.cacheDir, "share").apply {
                mkdirs()
                listFiles()?.forEach { if (it.isFile) it.delete() }
            }
            val f = java.io.File(dir, "clipboard_${entry.id}_${entry.sha256.take(8)}.txt")
            val stream = ClipboardManager.readFullText(entry) ?: return@withContext null
            stream.use { input -> f.outputStream().use { output -> input.copyTo(output) } }
            f
        } ?: return
        // U09：解密/落盘期间列表可能已刷新，先校验点击目标没变，变了就丢弃这次结果
        if (!isTargetIntact(targetId, targetSha)) {
            Timber.w("分享目标已变化，丢弃过期结果")
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            service, "${service.packageName}.fileprovider", file
        )
        val target = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(target, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        service.startActivity(chooser)
    }

    private val ui by lazy {
        ClipboardUi(context, theme).apply {
            recyclerView.apply {
                layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL).apply {
                    // U11：StaggeredGrid 默认会在滚动中反复做 gap 重排（全量 measure/layout），
                    // 是滚动卡顿主因之一；关闭 gap 处理可显著降低滚动开销。
                    gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
                }
                adapter = this@ClipboardWindow.adapter
                // U11：条目数一变就跑默认的增删动画（逐个 measure + 重绘），打开面板和
                // 刷新时都卡；这里关掉动画并放大复用缓存，换取流畅的打开/滚动。
                itemAnimator = null
                setItemViewCacheSize(8)
            }
            ItemTouchHelper(object : ItemTouchHelper.Callback() {
                override fun getMovementFlags(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder
                ): Int {
                    return makeMovementFlags(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT)
                }

                override fun onMove(
                    recyclerView: RecyclerView,
                    viewHolder: RecyclerView.ViewHolder,
                    target: RecyclerView.ViewHolder
                ): Boolean {
                    return false
                }

                override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                    val entry = adapter.getEntryAt(viewHolder.bindingAdapterPosition) ?: return
                    MbmDebug.logFeature("clipboard.swipe_delete")
                    service.lifecycleScope.launch(imeExceptionHandler) {
                        try {
                            ClipboardManager.delete(entry.id)
                            showUndoSnackbar(entry.id)
                        } catch (e: Throwable) {
                            Timber.e(e, "滑动删除剪贴板条目失败")
                        }
                    }
                }
            }).attachToRecyclerView(recyclerView)
            enableUi.enableButton.setOnClickListener {
                MbmDebug.logFeature("clipboard.enable")
                clipboardEnabledPref.setValue(true)
            }
            deleteAllButton.setOnClickListener {
                MbmDebug.logFeature("clipboard.clear")
                service.lifecycleScope.launch {
                    try {
                        promptDeleteAll(ClipboardManager.haveUnpinned())
                    } catch (e: Throwable) {
                        Timber.e(e, "打开剪贴板清空确认失败")
                    }
                }
            }
            importButton.setOnClickListener {
                // MBM v1.0.31：导入文本文件（流式，无大小上限）
                MbmDebug.logFeature("clipboard.import")
                runCatching { ClipboardImportActivity.launch(context) }
                    .onFailure { Timber.e(it, "启动剪贴板导入失败") }
            }
        }
    }

    override fun onCreateView(): View = ui.root

    private var promptMenu: ImeMenuPopup? = null

    /**
     * 显示"清空剪贴板"确认。
     *
     * MBM v0.0.09：原实现用系统 PopupMenu（内部是可抢焦点的 ListPopupWindow）。
     * 在华为等 OEM ROM 上，IME 窗口内弹出抢焦点的独立窗口会被系统判定「IME 失焦」
     * 而强制收起输入法——用户报回「点按剪贴板删除键，输入法自动收起」
     * （pitfalls #60，与 #55 同源）。改用非焦点 PopupWindow（ImeMenuPopup）。
     * 极端 OEM 上弹窗失败时，回退到由 service.showDialog 托管的 AlertDialog
     * （使用 IME 安全的窗口类型），保证交互不崩溃。
     */
    private fun promptDeleteAll(skipPinned: Boolean) {
        promptMenu?.dismiss()
        val title =
            context.getString(if (skipPinned) R.string.delete_all_except_pinned else R.string.delete_all_pinned_items)
        val items = listOf(
            ImeMenuPopup.Item(context.getString(android.R.string.cancel), null) {},
            ImeMenuPopup.Item(context.getString(android.R.string.ok), null) {
                deleteAllConfirmed(skipPinned)
            }
        )
        val popup = ImeMenuPopup(context, theme)
        promptMenu = if (popup.show(ui.deleteAllButton, title, items)) popup else null
        if (promptMenu == null) {
            Timber.w("剪贴板清空确认菜单无法显示，回退到 AlertDialog")
            showDeleteAllConfirmDialog(skipPinned)
        }
    }

    private fun showDeleteAllConfirmDialog(skipPinned: Boolean) {
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.delete_all)
            .setMessage(
                context.getString(
                    if (skipPinned) R.string.delete_all_except_pinned
                    else R.string.delete_all_pinned_items
                )
            )
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ -> deleteAllConfirmed(skipPinned) }
            .create()
        service.showDialog(dialog)
    }

    private fun deleteAllConfirmed(skipPinned: Boolean) {
        service.lifecycleScope.launch {
            try {
                val ids = ClipboardManager.deleteAll(skipPinned)
                showUndoSnackbar(*ids)
            } catch (e: Throwable) {
                Timber.e(e, "清空剪贴板失败")
            }
        }
    }

    private val pendingDeleteIds = arrayListOf<Int>()

    @SuppressLint("RestrictedApi")
    private fun showUndoSnackbar(vararg id: Int) {
        id.forEach { pendingDeleteIds.add(it) }
        val str = context.resources.getString(R.string.num_items_deleted, pendingDeleteIds.size)
        try {
            snackbarInstance = Snackbar.make(snackbarCtx, ui.root, str, Snackbar.LENGTH_LONG)
                .setBackgroundTint(theme.popupBackgroundColor)
                .setTextColor(theme.popupTextColor)
                .setActionTextColor(theme.genericActiveBackgroundColor)
                .setAction(R.string.undo) {
                    service.lifecycleScope.launch {
                        runCatching {
                            ClipboardManager.undoDelete(*pendingDeleteIds.toIntArray())
                            pendingDeleteIds.clear()
                        }.onFailure { Timber.e(it, "undoDelete failed") }
                    }
                }
                .addCallback(object : Snackbar.Callback() {
                    override fun onDismissed(transientBottomBar: Snackbar, event: Int) {
                        if (snackbarInstance === transientBottomBar) {
                            snackbarInstance = null
                        }
                        when (event) {
                            BaseCallback.DISMISS_EVENT_SWIPE,
                            BaseCallback.DISMISS_EVENT_MANUAL,
                            BaseCallback.DISMISS_EVENT_TIMEOUT -> {
                            service.lifecycleScope.launch {
                                runCatching {
                                    ClipboardManager.realDelete()
                                    pendingDeleteIds.clear()
                                }.onFailure { Timber.e(it, "realDelete failed") }
                            }
                            }
                            BaseCallback.DISMISS_EVENT_ACTION,
                            BaseCallback.DISMISS_EVENT_CONSECUTIVE -> {
                                // user clicked "undo" or deleted more items which makes a new snackbar
                            }
                        }
                    }
                }).apply {
                    val hMargin = snackbarCtx.dp(24)
                    val vMargin = snackbarCtx.dp(16)
                    view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                        leftMargin = hMargin
                        rightMargin = hMargin
                        bottomMargin = vMargin
                    }
                    ((view as FrameLayout).getChildAt(0) as SnackbarContentLayout).apply {
                        messageView.letterSpacing = 0f
                        actionView.letterSpacing = 0f
                    }
                    show()
                }
        } catch (e: Throwable) {
            Timber.e(e, "显示撤销 Snackbar 失败（已标记待删除，超时后将真正删除）")
        }
    }

    override fun onAttached() {
        val isEmpty = ClipboardManager.itemCount == 0
        val isListening = clipboardEnabledPref.getValue()
        val initialState = when {
            !isListening -> EnableListening
            isEmpty -> AddMore
            else -> Normal
        }
        stateMachine = ClipboardStateMachine.new(initialState, isEmpty, isListening) {
            ui.switchUiByState(it)
        }
        // manually switch to initial ui
        ui.switchUiByState(initialState)
        // N45：先移除可能残留的旧监听再注册，保证任何时刻只有一个监听在回调
        loadStateListener?.let { adapter.removeLoadStateListener(it) }
        val listener: (CombinedLoadStates) -> Unit = {
            if (::stateMachine.isInitialized) {
                val empty = it.append.endOfPaginationReached && adapter.itemCount < 1
                stateMachine.push(ClipboardDbUpdated, ClipboardDbEmpty to empty)
            }
        }
        loadStateListener = listener
        adapter.addLoadStateListener(listener)
        adapterSubmitJob = service.lifecycleScope.launch {
            clipboardEntriesPager.flow.collect {
                adapter.submitData(it)
            }
        }
        clipboardEnabledPref.registerOnChangeListener(clipboardEnabledListener)
        prefs.clipboardMaskSensitive.registerOnChangeListener(maskSensitiveListener)
    }

    override fun onDetached() {
        clipboardEnabledPref.unregisterOnChangeListener(clipboardEnabledListener)
        prefs.clipboardMaskSensitive.unregisterOnChangeListener(maskSensitiveListener)
        // N33：退出剪贴板面板时清理分享目录里的明文临时文件
        java.io.File(service.cacheDir, "share").listFiles()?.forEach { if (it.isFile) it.delete() }
        // N45：detach 必须移除 LoadState 监听并置空，否则下次 attach 会再叠一个，
        // 旧监听还会继续 push 状态机 → 界面抖动
        loadStateListener?.let { adapter.removeLoadStateListener(it) }
        loadStateListener = null
        adapter.onDetached()
        adapterSubmitJob?.cancel()
        promptMenu?.dismiss()
        snackbarInstance?.dismiss()
    }

    override val title: String by lazy {
        context.getString(R.string.clipboard)
    }

    override fun onCreateBarExtension(): View = ui.extension

    // ---- U1：查看剪贴板全文（blob 条目的全文在文件里，不在 text 列）----

    /** 查看器内联显示的正文上限（字符数），超出部分截断并提示。避免把 100MB 整段读进内存。 */
    private val VIEW_FULL_TEXT_LIMIT_CHARS = 200_000

    private suspend fun showFullText(entry: ClipboardEntry) {
        // U11：blob 的打开/解密/读取与全文拼装都是重活，全部挪到 IO 线程，
        // 原先在主线程做会让剪贴板面板打开与滚动明显卡顿。
        val result: Pair<String, Boolean>? = withContext(Dispatchers.IO) {
            if (entry.isBlob) {
                val stream = ClipboardManager.readFullText(entry) ?: return@withContext null
                stream.buffered().use { readCapped(it, VIEW_FULL_TEXT_LIMIT_CHARS) }
            } else {
                entry.text to false
            }
        }
        if (result == null) {
            Toast.makeText(service, "条目文件丢失，无法查看", Toast.LENGTH_SHORT).show()
            return
        }
        // U09：读全文期间列表可能已刷新，目标已变就丢弃，避免展示/粘到错条目
        if (!isTargetIntact(entry.id, entry.sha256)) return
        val (text, truncated) = result
        val display = if (truncated) {
            "$text\n\n…（内容过大，仅显示前部分）"
        } else text
        showFullTextDialog(entry, display)
    }

    /** 限长读取流文本，返回（文本, 是否因超限截断）。 */
    private fun readCapped(stream: InputStream, limitChars: Int): Pair<String, Boolean> {
        val reader = stream.bufferedReader()
        val sb = StringBuilder()
        val buf = CharArray(8192)
        var read: Int
        var total = 0
        while (reader.read(buf).also { read = it } != -1) {
            sb.append(buf, 0, read)
            total += read
            if (total >= limitChars) return sb.toString() to true
        }
        return sb.toString() to false
    }

    private fun showFullTextDialog(entry: ClipboardEntry, text: String) {
        val scroll = ScrollView(service).apply {
            val tv = TextView(service).apply {
                setTextIsSelectable(true)
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setPadding(24, 16, 24, 16)
                setText(text)
            }
            addView(tv)
        }
        val title = if (entry.isBlob) {
            "剪贴板全文（约 ${entry.byteLen / 1048576}MB）"
        } else {
            "剪贴板全文"
        }
        val dialog = AlertDialog.Builder(service)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        // 用 IME 安全窗口类型弹出，避免在 OEM ROM 上被判定「IME 失焦」而收起输入法
        service.showDialog(dialog)
    }

    companion object {
        /** 剪贴板分页每页条数（U05：maxSize 以它为基准封顶，避免翻历史时无限驻留）。 */
        private const val PAGE_SIZE = 16

        /** U07：同一条目的同一操作在这个窗口（毫秒）内只触发一次。 */
        private const val CLICK_DEDUPE_WINDOW_MS = 400L
    }
}