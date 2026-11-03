/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * Recall 时间线界面：浏览、搜索、删除本机捕获的屏幕文字。
 * 数据全部来自本机 SQLCipher 加密库，不联网。
 *
 * 数据闭环（本版本新增）：
 *  - 勾选记录 →「导入到输入法」写入 fcitx5 剪贴板库，输入时在剪贴板候选可见并可上屏；
 *  - 勾选记录 →「导出为文本」落地为本地文件，便于备份/自查；
 *  - 每次导入 / 导出 / 删除 / 清空都写入「数据去向」审计日志（本地明文，随时可查）。
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.TransactionTooLargeException
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.recall.PhraseAutoWriter
import org.fcitx.fcitx5.android.recall.RecallPolicy
import org.fcitx.fcitx5.android.recall.RecallService
import org.fcitx.fcitx5.android.recall.data.RecallAuditLog
import org.fcitx.fcitx5.android.recall.data.RecallBlobStore
import org.fcitx.fcitx5.android.recall.data.RecallPreferences
import org.fcitx.fcitx5.android.recall.data.RecallRecord
import org.fcitx.fcitx5.android.recall.data.RecallRepository
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// FragmentActivity（AppCompat 家族）：供 AlertDialog / FileProvider 等组件使用。
class RecallActivity : FragmentActivity() {

    private var records: List<RecallRecord> = emptyList()
    private val selection = mutableSetOf<Long>()
    /** 搜索词流：observe 只启动一次，靠 collectLatest 切换查询，避免反复 launch 协程泄漏 */
    private val queryFlow = MutableStateFlow("")
    /** 上次渲染的记录签名，内容未变则跳过全量重建（防止 DB 高频发射导致主线程反复 render） */
    private var lastRenderedSig: String? = null

    /** MBM v1.0.23：分块渲染任务句柄（新渲染开始时取消旧任务） */
    private var renderJob: Job? = null

    // MBM v1.0.31：keyset 分页状态（历史无上限：不再整表 Flow 进内存）
    private var scrollView: ScrollView? = null
    private var pageCursorTs = Long.MAX_VALUE
    private var pageCursorId = Long.MAX_VALUE
    private var pageLoading = false
    private var pageExhausted = false

    private lateinit var listContainer: LinearLayout
    private lateinit var summary: TextView
    private lateinit var auditText: TextView
    private lateinit var guideText: TextView
    private var deleteButton: MaterialButton? = null
    private var importButton: MaterialButton? = null
    private var exportButton: MaterialButton? = null
    private var phraseButton: MaterialButton? = null

    /** v0.0.02：无障碍服务连接状态行 */
    private var serviceStatus: TextView? = null

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshServiceStatus()
        // MBM v1.0.39 裁决：删除「设备凭证门」（时间线锁定/解锁查看）。
        // 它属于虚假安全：只挡得住机主本人，挡不住拿到已解锁设备的人，
        // 却实打实地把每次查看变成一次折腾。真正的安全=SQLCipher 静态加密
        // + 不联网 + 本机审计日志，打开即看，火力全开。
        observe()
        refreshAudit()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(COLOR_BG) }
        scrollView = scroll
        // MBM v1.0.31：分页浏览的滚动监听（历史无上限）
        scroll.viewTreeObserver.addOnScrollChangedListener { loadMoreIfNeeded() }
        val root = LinearLayout(this@RecallActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        scroll.addView(root)

        root.addView(TextView(this@RecallActivity).apply {
            text = "Recall 屏幕记忆"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(COLOR_FG)
        })
        root.addView(TextView(this@RecallActivity).apply {
            text = "无障碍服务捕获 · 不使用录屏权限 · 离线 OCR · 数据仅存本机(加密)"
            textSize = 12f
            setTextColor(COLOR_DIM)
            setPadding(0, dp(2), 0, dp(10))
        })

        // 开关
        root.addView(LinearLayout(this@RecallActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "启用 Recall 捕获"
                setTextColor(COLOR_FG)
                textSize = 14f
                layoutParams = linearParams(1f)
            })
            addView(SwitchMaterial(context).apply {
                lifecycleScope.launch { isChecked = RecallPreferences.isEnabled() }
                setOnCheckedChangeListener { _, checked ->
                    lifecycleScope.launch {
                        RecallPreferences.setEnabled(checked)
                        if (checked && !RecallService.isServiceRunning()) {
                            openAccessibilitySettings()
                        }
                        refreshServiceStatus()
                    }
                }
            })
        })

        // 服务状态行（v0.0.02）：把「系统无障碍里开没开、服务连没连」直接摆出来，
        // 用户不必再去系统设置里猜。真机排查「开关开了却没反应」的第一现场。
        serviceStatus = TextView(this@RecallActivity).apply {
            textSize = 12f
            setTextColor(COLOR_DIM)
            setPadding(0, dp(2), 0, dp(6))
        }
        root.addView(serviceStatus)
        root.addView(LinearLayout(this@RecallActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "启用离线 OCR 兜底"
                setTextColor(COLOR_FG)
                textSize = 14f
                layoutParams = linearParams(1f)
            })
            addView(SwitchMaterial(context).apply {
                lifecycleScope.launch { isChecked = RecallPreferences.isOcrEnabled() }
                setOnCheckedChangeListener { _, checked ->
                    lifecycleScope.launch { RecallPreferences.setOcrEnabled(checked) }
                }
            })
        })

        root.addView(MaterialButton(this).apply {
            text = "打开系统无障碍设置"
            setOnClickListener { openAccessibilitySettings() }
        })

        // 捕获后自动写入自定义词组（无延迟：捕获即提炼入队，后台去抖落盘）
        root.addView(LinearLayout(this@RecallActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "捕获后自动写入自定义词组"
                setTextColor(COLOR_FG)
                textSize = 14f
                layoutParams = linearParams(1f)
            })
            addView(SwitchMaterial(context).apply {
                lifecycleScope.launch { isChecked = RecallPreferences.isAutoAddPhrase() }
                setOnCheckedChangeListener { _, checked ->
                    lifecycleScope.launch { RecallPreferences.setAutoAddPhrase(checked) }
                }
            })
        })

        // 捕获后自动写入剪贴板（只进输入法剪贴板库，不动系统剪贴板，不影响用户自己复制的内容）
        root.addView(LinearLayout(this@RecallActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "捕获后自动写入剪贴板"
                setTextColor(COLOR_FG)
                textSize = 14f
                layoutParams = linearParams(1f)
            })
            addView(SwitchMaterial(context).apply {
                lifecycleScope.launch { isChecked = RecallPreferences.isAutoClipboard() }
                setOnCheckedChangeListener { _, checked ->
                    lifecycleScope.launch { RecallPreferences.setAutoClipboard(checked) }
                }
            })
        })

        // 数据如何使用（引导文案，解决“收集后怎么用”的困惑）
        guideText = TextView(this@RecallActivity).apply {
            text = "这些数据怎么用？勾选下方记录 → " +
                "①「导入到输入法」写入输入法剪贴板候选，打字时点剪贴板按钮一键上屏；" +
                "②「导出为词组」把文本里的中文词组智能提炼成自定义词组（键=拼音首字母，" +
                "如“玩机技巧”→wjjq，顺序 2，输入 wjjq 时出现在候选第 2 位）；" +
                "③「导出为文本」留存到本机文件。所有数据不出本机，下方「数据去向」记录每一次去向。" +
                "开启「捕获后自动写入自定义词组」后，屏幕捕获到的中文会无需任何手动操作、自动提炼成词组并立即可用；" +
                "开启「捕获后自动写入剪贴板」后，捕获到的文本会直接进入输入法剪贴板候选（只写剪贴板库、" +
                "绝不动系统剪贴板，因此不会覆盖你自己复制的内容，也不会重复记录、不会刷屏）。"
            textSize = 12f
            setTextColor(COLOR_DIM)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(guideText)

        // MBM v1.0.26（P0-2）：「不会读取什么」可读公示（RecallPolicy 唯一事实来源）
        root.addView(TextView(this@RecallActivity).apply {
            text = getString(R.string.recall_disclosure_title)
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(COLOR_FG)
            setPadding(0, dp(8), 0, dp(4))
        })
        root.addView(TextView(this@RecallActivity).apply {
            text = RecallPolicy.disclosureLines().joinToString("\n") { "· $it" }
            textSize = 12f
            setTextColor(COLOR_DIM)
            setPadding(0, dp(2), 0, dp(10))
        })

        // MBM v1.0.32（隐私合规）：隐私政策全文入口。文本随 APK 分发
        // （assets/licenses/PRIVACY.txt，由 syncLicenseAssets 同步），离线可查。
        root.addView(MaterialButton(this).apply {
            text = getString(R.string.recall_privacy_policy)
            setOnClickListener { showPrivacyPolicy() }
        })

        // MBM v1.0.33（合规·R2）：开源许可查看器（AGPL 全文 / NOTICE / 上游清单）。
        root.addView(MaterialButton(this).apply {
            text = getString(R.string.recall_open_source_licenses)
            setOnClickListener { showLicenseChooser() }
        })

        // 搜索
        val search = EditText(this).apply {
            hint = "搜索已捕获的文本"
            setTextColor(COLOR_FG)
            setHintTextColor(COLOR_DIM)
            textSize = 13f
        }
        root.addView(search)
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                queryFlow.value = s?.toString().orEmpty()
            }
        })

        // 操作按钮行（主按钮下方的批量操作）：全选 / 全不选 / 反选 / 清空全部
        val actions = LinearLayout(this@RecallActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        actions.addView(MaterialButton(this).apply {
            text = "全选"
            textSize = 12f
            layoutParams = linearParams(1f, startMargin = 0)
            setOnClickListener { toggleAll(true) }
        })
        actions.addView(MaterialButton(this).apply {
            text = "全不选"
            textSize = 12f
            layoutParams = linearParams(1f, startMargin = dp(4))
            setOnClickListener { toggleAll(false) }
        })
        actions.addView(MaterialButton(this).apply {
            text = "反选"
            textSize = 12f
            layoutParams = linearParams(1f, startMargin = dp(4))
            setOnClickListener { invertAll() }
        })
        actions.addView(MaterialButton(this).apply {
            text = "清空全部"
            textSize = 12f
            layoutParams = linearParams(1f, startMargin = dp(4))
            setOnClickListener {
                if (records.isEmpty()) return@setOnClickListener
                lifecycleScope.launch {
                    val n = records.size
                    RecallRepository.clear(this@RecallActivity)
                    RecallAuditLog.log(RecallAuditLog.ACTION_CLEAR, "清空 $n 条")
                    selection.clear()
                }
            }
        })
        root.addView(actions)

        // 消费动作：导入 / 导出（默认隐藏，选中后显示）
        val consume = LinearLayout(this@RecallActivity).apply { orientation = LinearLayout.HORIZONTAL }
        importButton = MaterialButton(this).apply {
            text = "导入到输入法"
            layoutParams = linearParams(1f)
            visibility = View.GONE
            setOnClickListener { importSelected() }
        }
        exportButton = MaterialButton(this).apply {
            text = "导出为文本"
            layoutParams = linearParams(1f)
            visibility = View.GONE
            setOnClickListener { exportSelected() }
        }
        // 智能组词：把捕获文本里提炼出的 2~4 字中文词组写入 fcitx 自定义词组，
        // 键 = 拼音首字母（"玩机技巧" → wjjq），顺序 = 2（输入 wjjq 后候选第 2 位出现该词组）。
        phraseButton = MaterialButton(this).apply {
            text = "导出为词组"
            layoutParams = linearParams(1f)
            visibility = View.GONE
            setOnClickListener { importAsPhrases() }
        }
        consume.addView(importButton)
        consume.addView(exportButton)
        consume.addView(phraseButton)
        root.addView(consume)

        deleteButton = MaterialButton(this).apply {
            text = "删除选中项"
            visibility = View.GONE
            setOnClickListener {
                if (selection.isEmpty()) return@setOnClickListener
                lifecycleScope.launch {
                    val ids = selection.toList()
                    RecallRepository.delete(this@RecallActivity, ids)
                    RecallAuditLog.log(RecallAuditLog.ACTION_DELETE, "删除 ${ids.size} 条")
                    selection.clear()
                }
            }
        }
        root.addView(deleteButton)

        summary = TextView(this@RecallActivity).apply {
            textSize = 12f
            setTextColor(COLOR_DIM)
            setPadding(0, dp(10), 0, dp(6))
        }
        root.addView(summary)

        listContainer = LinearLayout(this@RecallActivity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listContainer)

        // 数据去向（审计日志）面板
        root.addView(TextView(this@RecallActivity).apply {
            text = "数据去向（本地审计日志）"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(COLOR_FG)
            setPadding(0, dp(12), 0, dp(4))
        })
        auditText = TextView(this@RecallActivity).apply {
            textSize = 11f
            setTextColor(COLOR_DIM)
            setPadding(0, dp(2), 0, dp(2))
        }
        root.addView(auditText)

        setContentView(scroll)
        // 适配 edge-to-edge：必须在 setContentView 之后（DecorView 就绪）再处理系统栏，
        // 公共 fitInsets 内部已用 isAttachedToWindow 兜底；此处顺序与 RuleImport/SmartDict/PasswordGenerator 一致。
        MbmActivityUi.fitInsets(this, scroll)
    }

    /**
     * 勾选记录（保持列表顺序）。MBM v1.0.31 blob 化后：
     * blob 条目的 text 列只是 preview（前 256 字符），任何「取全文」的路径
     * 都必须走 [writeFullText] / RecallBlobStore.open，不能再 map { it.text }。
     */
    private fun selectedRecords(): List<RecallRecord> =
        records.filter { selection.contains(it.id) && (it.isBlob || it.text.isNotBlank()) }

    /**
     * 把一条记录的**全文**流式写入 out：
     *  - 内联条目：直接写 text 列；
     *  - blob 条目：拷贝 blob 文件字节（100MB 也不整篇进内存）。
     * blob 文件缺失时不静默：写占位说明并返回 -1，由调用方 toast 提示。
     *
     * @param sep 是否先写记录分隔符（"\n"）
     * @return 写入的全文字节数；blob 缺失返回 -1
     */
    private fun writeFullText(record: RecallRecord, out: OutputStream, sep: Boolean): Long {
        if (sep) out.write('\n'.code)
        if (!record.isBlob) {
            val bytes = record.text.toByteArray()
            out.write(bytes)
            return bytes.size.toLong()
        }
        val sha = record.blobRef ?: return -1L
        val stream = RecallBlobStore.open(sha)
            ?: run {
                val placeholder =
                    "[Recall] 大文本文件缺失（sha256=$sha），仅存摘要：${record.text}"
                out.write(placeholder.toByteArray())
                return -1L
            }
        return stream.use { it.copyTo(out) }
    }

    private fun importSelected() {
        val selected = selectedRecords()
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先勾选要导入的记录", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            // MBM v1.0.26：独立 APK 不再直连输入法进程，改用系统剪贴板
            //（用户显式点击触发，不会自动刷屏）。
            // N56 修复：剪贴板走 Binder 事务，按 UTF-16 计费（每个码元 2 字节），
            // 不能用 UTF-8 字节数估大小（ASCII 会被低估 2 倍 → TransactionTooLargeException）。
            // 大文本（blob）全文读入内存同样危险，因此这里只用偏小的总字符数做保守估算，
            // 真正的大内容一律走文件分享，且 setPrimaryClip 必须包 try/catch 兜底。
            val parcelChars = selected.sumOf {
                if (it.isBlob) it.byteLen else it.text.length.toLong()
            }
            val parcelBytes = parcelChars * 2L
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (parcelBytes <= CLIPBOARD_SOFT_LIMIT) {
                val sb = StringBuilder()
                selected.forEachIndexed { i, rec ->
                    if (i > 0) sb.append('\n')
                    if (!rec.isBlob) {
                        sb.append(rec.text)
                    } else {
                        val sha = rec.blobRef
                        if (sha != null) {
                            RecallBlobStore.open(sha)?.use { s ->
                                sb.append(s.bufferedReader().use { it.readText() })
                            }
                        }
                    }
                }
                try {
                    cm.setPrimaryClip(ClipData.newPlainText("Recall", sb.toString()))
                    RecallAuditLog.log(
                        RecallAuditLog.ACTION_IMPORT_CLIPBOARD,
                        "复制到系统剪贴板：${selected.size} 条 / $parcelBytes 字节（UTF-16 计费，可粘贴到任意应用）"
                    )
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@RecallActivity,
                            "已复制到剪贴板：${selected.size} 条，可在任意应用中粘贴",
                            Toast.LENGTH_LONG
                        ).show()
                        refreshAudit()
                    }
                } catch (e: TransactionTooLargeException) {
                    // 估算偏保守时 Binder 仍可能超限：降级为文件分享，绝不崩溃
                    shareSelectedToFile(selected, parcelBytes)
                }
            } else {
                // 超过 Binder 单次事务上限（~1MB）：不截断、不静默，
                // 改为整篇写入缓存文件，经系统分享面板交出完整内容。
                shareSelectedToFile(selected, parcelBytes)
            }
        }
    }

    /**
     * N56 文件分享降级：把选中记录全文流式写入缓存文件并经系统分享面板交出。
     * 大文本（blob）逐字节拷贝、不整篇进内存；blob 缺失写占位说明而非静默丢弃。
     */
    private suspend fun shareSelectedToFile(
        selected: List<RecallRecord>,
        estimatedBytes: Long
    ) {
        val file = File(cacheDir, "recall_share_${System.currentTimeMillis()}.txt")
        var missing = 0
        file.outputStream().buffered().use { out ->
            selected.forEachIndexed { i, rec ->
                if (writeFullText(rec, out, i > 0) < 0) missing++
            }
        }
        val uri = FileProvider.getUriForFile(
            this@RecallActivity,
            "${packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        RecallAuditLog.log(
            RecallAuditLog.ACTION_IMPORT_CLIPBOARD,
            "选中 $estimatedBytes 字节超过剪贴板事务上限，改经文件分享交出完整内容" +
                if (missing > 0) "（$missing 条大文本文件缺失）" else ""
        )
        withContext(Dispatchers.Main) {
            startActivity(Intent.createChooser(intent, "分享 Recall 完整内容"))
            Toast.makeText(
                this@RecallActivity,
                "内容共 $estimatedBytes 字节，超过系统剪贴板单次上限，已改为文件分享（完整内容）",
                Toast.LENGTH_LONG
            ).show()
            refreshAudit()
        }
    }

    /**
     * 智能组词：把选中记录里提炼出的中文词组写入本地队列文件，
     * 再通过「.txt + FileProvider」交给主输入法导入（由主输入法推导拼音键并落盘）。
     *
     * 键 = 拼音首字母（"玩机技巧" → wjjq），顺序 = 2，与主输入法导入规则一致。
     */
    private fun importAsPhrases() {
        val selected = selectedRecords()
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先勾选要导出为词组的记录", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            // MBM v1.0.31：移除 .take(PHRASE_HARD_LIMIT) 硬截断——词组提炼无上限；
            // blob 大文本逐行流式提炼（不整篇进内存）。
            val phrases = LinkedHashSet<String>()
            var missing = 0
            selected.forEach { rec ->
                if (!rec.isBlob) {
                    phrases += PhraseAutoWriter.extractPhrases(listOf(rec.text))
                } else {
                    val sha = rec.blobRef
                    if (sha == null) {
                        missing++
                        return@forEach
                    }
                    val stream = RecallBlobStore.open(sha)
                    if (stream == null) {
                        missing++
                        return@forEach
                    }
                    stream.bufferedReader().use { br ->
                        phrases += PhraseAutoWriter.extractPhrasesFromLines(br.lineSequence())
                    }
                }
            }
            val appended = PhraseAutoWriter.appendToQueue(phrases.toList())
            val ok = if (appended > 0) handOffPhraseQueueToIme() else false
            val suffix = buildString {
                if (missing > 0) append("，$missing 条大文本文件缺失")
                append(if (ok) "，已拉起主输入法导入" else "，待主输入法导入")
            }
            RecallAuditLog.log(
                RecallAuditLog.ACTION_IMPORT_PHRASE,
                "导出词组给输入法：新增 $appended 条（键=拼音首字母，顺序=$PHRASE_ORDER）$suffix"
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@RecallActivity,
                    when {
                        appended <= 0 && missing > 0 -> "选中记录的大文本文件缺失，无词组可导出"
                        appended <= 0 -> "没有可导出的新词组"
                        ok -> "已交予主输入法导入：$appended 条"
                        else -> "已入队 $appended 条；安装主输入法后可再次点击完成导入"
                    },
                    Toast.LENGTH_LONG
                ).show()
                refreshAudit()
            }
        }
    }

    /**
     * 把词组队列文件通过 FileProvider 交给主输入法（org.fcitx.fcitx5.android）的
     * .txt 导入入口（主输入法本就声明了 .txt 的 VIEW intent-filter）。
     * 主输入法不在设备上时返回 false，不报错。
     */
    private fun handOffPhraseQueueToIme(): Boolean {
        val phrases = PhraseAutoWriter.drainQueue()
        if (phrases.isEmpty()) return false
        val file = File(cacheDir, "recall_phrase_export.txt")
        file.writeText(phrases.joinToString("\n"))
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(packageName)
        }
        return runCatching { startActivity(intent); true }.getOrDefault(false)
    }

    /**
     * 导出勾选记录为本地 .txt（MBM v1.0.31：blob 条目流式写出全文，
     * 选中总量 100MB 也不整篇进内存；文件缺失不静默——占位说明 + toast）。
     */
    private fun exportSelected() {
        val selected = selectedRecords()
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先勾选要导出的记录", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val name = "Recall-导出-$stamp.txt"
            val target = File(
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir,
                name
            )
            var bytes = 0L
            var missing = 0
            target.outputStream().buffered().use { out ->
                selected.forEachIndexed { i, rec ->
                    val n = writeFullText(rec, out, i > 0)
                    if (n < 0) missing++ else bytes += n
                }
            }
            RecallAuditLog.log(
                RecallAuditLog.ACTION_EXPORT,
                "导出 ${selected.size} 条 / $bytes 字节 → ${target.absolutePath}" +
                    if (missing > 0) "（$missing 条大文本文件缺失）" else ""
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@RecallActivity,
                    if (missing > 0) "已导出（$missing 条大文本文件缺失，详见文件内说明）：${target.absolutePath}"
                    else "已导出到：${target.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
                refreshAudit()
            }
        }
    }

    private fun openAccessibilitySettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure {
            Toast.makeText(this, R.string.recall_open_accessibility_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * MBM v1.0.32（隐私合规）：离线展示隐私政策全文。
     * 文本由 syncLicenseAssets 从仓库根目录 PRIVACY.txt 同步进 assets，
     * 随 APK 分发，不依赖外网；读取失败时退回服务描述与「不会读取什么」公示。
     */
    private fun showPrivacyPolicy() {
        val text = runCatching {
            assets.open("licenses/PRIVACY.txt").bufferedReader().use { it.readText() }
        }.getOrNull() ?: (getString(R.string.recall_service_description) + "\n\n" +
            RecallPolicy.disclosureLines().joinToString("\n") { "· $it" })
        showTextDialog(getString(R.string.recall_privacy_policy), text)
    }

    /** MBM v1.0.33（合规·R2）：开源许可查看器（AGPL 全文 / NOTICE / 上游清单，均随包分发）。 */
    private fun showLicenseChooser() {
        val entries = listOf(
            getString(R.string.recall_license_agpl) to "licenses/AGPL-3.0.txt",
            getString(R.string.recall_license_notice) to "licenses/NOTICE.txt",
            getString(R.string.recall_license_upstream) to "licenses/UPSTREAM.txt"
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.recall_open_source_licenses)
            .setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                val (title, path) = entries[which]
                val text = runCatching {
                    assets.open(path).bufferedReader().use { it.readText() }
                }.getOrNull() ?: "许可文件读取失败：$path"
                showTextDialog(title, text)
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** 可滚动、可选中复制的纯文本对话框（隐私政策 / 许可全文共用）。 */
    private fun showTextDialog(title: String, text: String) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val body = TextView(this).apply {
            this.text = text
            setTextColor(COLOR_FG)
            textSize = 12f
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun observe() {
        // 只启动一次协程：搜索词变化时切换查询（浏览=分页首屏，搜索=Flow），
        // 旧查询自动取消，避免反复 launch 导致多个 collect 并发 render（协程泄漏）。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                queryFlow.collectLatest { q ->
                    if (q.isBlank()) {
                        // MBM v1.0.31：浏览路径 = keyset 分页（无上限历史）
                        pageCursorTs = Long.MAX_VALUE
                        pageCursorId = Long.MAX_VALUE
                        pageLoading = false
                        pageExhausted = false
                        records = RecallRepository.page(
                            this@RecallActivity, pageCursorTs, pageCursorId, PAGE_SIZE
                        )
                        records.lastOrNull()?.let {
                            pageCursorTs = it.timestamp
                            pageCursorId = it.id
                        }
                        pageExhausted = records.size < PAGE_SIZE
                        render()
                    } else {
                        // 搜索路径保持 Flow（结果集通常有限；超大结果集的 FTS 化见设计文档 P3）
                        RecallRepository.search(this@RecallActivity, q).collectLatest { list ->
                            records = list
                            render()
                        }
                    }
                }
            }
        }
    }

    /** MBM v1.0.31：滚动到底部前一屏时加载下一页（历史无上限）。 */
    private fun loadMoreIfNeeded() {
        if (pageLoading || pageExhausted) return
        if (queryFlow.value.isNotBlank()) return
        val sv = scrollView ?: return
        val child = sv.getChildAt(0) ?: return
        val remaining = child.measuredHeight - (sv.height + sv.scrollY)
        if (remaining < sv.height) {
            pageLoading = true
            lifecycleScope.launch {
                val next = RecallRepository.page(
                    this@RecallActivity, pageCursorTs, pageCursorId, PAGE_SIZE
                )
                if (next.isEmpty()) {
                    pageExhausted = true
                } else {
                    records = records + next
                    next.last().let {
                        pageCursorTs = it.timestamp
                        pageCursorId = it.id
                    }
                    pageExhausted = next.size < PAGE_SIZE
                    render()
                }
                pageLoading = false
            }
        }
    }

    private fun render() {
        // 内容未变则跳过全量重建：无障碍服务后台高频写入会让 Room Flow 反复发射，
        // 不在此拦截会导致主线程反复重建全部 Row，进而触发 ANR（已踩坑）。
        // 签名同时包含 selection，保证「全选/全不选」能正确重绘勾选状态。
        // N68：签名含记录总数，避免超过 1000 条后 joinToString(limit=1000) 截断导致
        // 后续分页结果签名恒定不变、提前 return 不再渲染（滚到 1200 条界面静止的元凶）。
        val sig = "${records.size}#" + records.joinToString(",", limit = 1000) { it.id.toString() } + "#" +
            selection.sorted().joinToString(",")
        if (sig == lastRenderedSig) return
        lastRenderedSig = sig

        // MBM v1.0.23：DAO 已无上限（移除 LIMIT 300），此处改为分块渲染，
        // 每块之间 yield 让出主线程，数千条记录也不会 ANR。
        renderJob?.cancel()
        listContainer.removeAllViews()
        summary.text = "共 ${records.size} 条记录" +
            (if (selection.isNotEmpty()) " · 已选 ${selection.size}" else "") +
            (if (!pageExhausted && queryFlow.value.isBlank()) " · 上滑加载更多" else "")
        val hasSel = selection.isNotEmpty()
        deleteButton?.visibility = if (hasSel) View.VISIBLE else View.GONE
        importButton?.visibility = if (hasSel) View.VISIBLE else View.GONE
        exportButton?.visibility = if (hasSel) View.VISIBLE else View.GONE
        phraseButton?.visibility = if (hasSel) View.VISIBLE else View.GONE
        guideText.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE

        renderJob = lifecycleScope.launch {
        records.chunked(RENDER_CHUNK).forEach { chunk ->
            chunk.forEach { record ->
            // 卡片：垂直 LinearLayout 作为 listContainer 的子项，宽度必须 MATCH_PARENT，
            // 不能用 linearParams(1f)（垂直布局中 weight 只作用于高度，宽度会成 0 —— 之前列表不可见的元凶）。
            val row = LinearLayout(this@RecallActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                background = cardBg()
                setPadding(dp(12), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(6) }
            }
            val check = CheckBox(this@RecallActivity).apply { isChecked = selection.contains(record.id) }
            check.setOnCheckedChangeListener { _, checked ->
                if (checked) selection += record.id else selection -= record.id
                val nowSel = selection.isNotEmpty()
                refreshActionButtons(nowSel)
            }
            row.addView(check)

            val host = LinearLayout(this@RecallActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = linearParams(1f, startMargin = dp(8))
            }
            // 捕获词（主文本，突出显示）
            host.addView(TextView(this@RecallActivity).apply {
                text = record.text.ifBlank { "（空文本）" }
                setTextColor(COLOR_FG)
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                maxLines = 6
            })
            // 时间 + 来源应用（副文本）
            host.addView(TextView(this@RecallActivity).apply {
                text = "${dateFormat.format(Date(record.timestamp))} · ${record.appLabel} · ${record.source}"
                setTextColor(COLOR_DIM)
                textSize = 11f
                setPadding(0, dp(3), 0, 0)
            })
            row.addView(host)

            // 每条独立删除按钮
            val del = TextView(this@RecallActivity).apply {
                text = "删除"
                textSize = 12f
                setTextColor(0xFFE5534B.toInt())
                setPadding(dp(8), dp(4), dp(2), dp(4))
                isClickable = true
                isFocusable = true
            }
            del.setOnClickListener {
                lifecycleScope.launch {
                    RecallRepository.delete(this@RecallActivity, listOf(record.id))
                    RecallAuditLog.log(RecallAuditLog.ACTION_DELETE, "删除 1 条")
                    refreshAudit()
                }
            }
            row.addView(del)
            listContainer.addView(row)
            }
            // 每块渲染完让出主线程一帧，长列表分帧重建，避免卡顿/ANR
            yield()
        }
        }
    }

    /** 根据是否有选中项刷新导入/导出/删除按钮可见性，并同步 summary 文案 */
    private fun refreshActionButtons(nowSel: Boolean) {
        deleteButton?.visibility = if (nowSel) View.VISIBLE else View.GONE
        importButton?.visibility = if (nowSel) View.VISIBLE else View.GONE
        exportButton?.visibility = if (nowSel) View.VISIBLE else View.GONE
        phraseButton?.visibility = if (nowSel) View.VISIBLE else View.GONE
        summary.text = "共 ${records.size} 条记录" + if (nowSel) " · 已选 ${selection.size}" else ""
    }

    private fun refreshAudit() {
        // 审计日志是文件读取，放到 IO 线程，避免主线程 IO（StrictMode 违规 / 卡顿隐患）
        lifecycleScope.launch(Dispatchers.IO) {
            val lines = RecallAuditLog.recent(30)
            withContext(Dispatchers.Main) {
                auditText.text = if (lines.isEmpty()) {
                    "（暂无操作记录。勾选记录并导入/导出后，此处会显示每一次数据去向。）"
                } else {
                    lines.joinToString("\n")
                }
            }
        }
    }

    private fun toggleAll(select: Boolean) {
        selection.clear()
        if (select) records.forEach { selection += it.id }
        render()
    }

    private fun invertAll() {
        val newSel = mutableSetOf<Long>()
        records.forEach { if (!selection.contains(it.id)) newSel += it.id }
        selection.clear()
        selection += newSel
        render()
    }

    private fun cardBg() = GradientDrawable().apply {
        setColor(COLOR_PANEL)
        cornerRadius = dp(12).toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun linearParams(weight: Float, startMargin: Int = 0, topMargin: Int = 0) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).also {
            it.marginStart = startMargin
            it.topMargin = topMargin
        }

    /**
     * v0.0.02：无障碍服务状态自检。
     *
     * 背景：v1.0.26「Recall 独立化」把清单里的服务短名 `.recall.RecallService`
     * 带进了命名空间为 `org.fcitx.fcitx5.android.plugin.recall` 的插件模块，
     * 被解析成不存在的 `...plugin.recall.recall.RecallService`，服务永远无法
     * 实例化 —— 用户在系统设置里「已开启」，实际一个事件都收不到。
     * 现在把三层状态（已连接 / 系统已开未连接 / 未开启）直接显示在开关下方。
     */
    private fun refreshServiceStatus() {
        val connected = RecallService.isServiceRunning()
        val sysEnabled = isA11yServiceEnabledInSystem()
        val (text, color) = when {
            // MBM v1.0.58：以「系统是否真开启」为权威判定，杜绝「没开却显示已连接」的假阳性。
            // 旧逻辑仅凭静态 instance != null 判「已连接」，一旦服务被系统回收但 instance 未清，
            // onResume 仍渲染「已连接」，与用户「根本没开」的体感矛盾。现仅当系统确已开启
            // 且实例存活才显示已连接；系统未开启则一律判「未开启」。
            sysEnabled && connected -> "✓ 无障碍服务已连接，正在常驻待命：切到其他应用即自动捕获" to 0xFF3FB950.toInt()
            sysEnabled -> "⚠ 系统里已开启本服务但尚未连接：正在自动重连，或重启手机" to 0xFFD29922.toInt()
            else -> "✗ 系统无障碍里还未开启本服务：点上方「打开系统无障碍设置」找到 MBM 并开启" to 0xFFE5534B.toInt()
        }
        serviceStatus?.text = text
        serviceStatus?.setTextColor(color)
    }

    /** 系统设置里这个 AccessibilityService 是否处于启用状态 */
    private fun isA11yServiceEnabledInSystem(): Boolean {
        // API 37 stubs 起废弃包 android.accessibility.* 已从编译类路径移除，
        // canonical 服务类在 android.view.accessibility（getSystemService 实际返回的也是它）
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            ?: return false
        val cls = RecallService::class.java.name // org.fcitx.fcitx5.android.recall.RecallService
        return runCatching {
            am.getEnabledAccessibilityServiceList(android.view.accessibility.AccessibilityEvent.TYPES_ALL_MASK)
                .any { info ->
                    val id = info.id ?: return@any false
                    // 优先精确匹配组件名；退化匹配：同包且类名以 recall.RecallService 结尾，
                    // 抵御 unflattenFromString 在不同 Android 版本上的解析差异（v1.0.26 同款坑的兜底）。
                    android.content.ComponentName.unflattenFromString(id)
                        ?.let { it == android.content.ComponentName(packageName, cls) }
                        ?: (id.startsWith("$packageName/") && id.endsWith("recall.RecallService"))
                }
        }.getOrDefault(false)
    }

    override fun onResume() {
        super.onResume()
        refreshServiceStatus()
    }

    companion object {
        /** MBM v1.0.23：无上限列表的分块渲染块大小 */
        private const val RENDER_CHUNK = 300

        /** MBM v1.0.31：分页加载的每页条数（keyset 分页，历史无上限） */
        private const val PAGE_SIZE = 300

        /** 自定义词组默认顺序：order-1 即候选位置，2 = 候选第 2 位 */
        private const val PHRASE_ORDER = 2
        private const val MIN_PHRASE_LEN = 2
        /** ≤ 该长度的连续汉字段直接整段成词（应用名/短词场景，如 5 字的「快手极速版」） */
        private const val MAX_WHOLE_PHRASE_LEN = 6
        /** 更长的汉字段按此窗口切分（长句才有必要切） */
        private const val WINDOW_PHRASE_LEN = 4
        // MBM v1.0.31：移除 PHRASE_HARD_LIMIT=200（词组提炼无上限）

        private const val COLOR_BG = 0xFF0E1116.toInt()
        private const val COLOR_PANEL = 0xFF161B22.toInt()
        private const val COLOR_FG = 0xFFE6EDF3.toInt()
        private const val COLOR_DIM = 0xFF7D8590.toInt()

        /**
         * MBM 冻结方案（同进程内嵌）：RecallActivity 现为主输入法 APK 内的一个 Activity，
         * 不再跨包拉起，直接在同进程 startActivity 即可；调用方若处于非 Activity Context
         * （如 IME 的输入视图），需要 NEW_TASK 标志。
         */
        fun launch(context: Context) {
            context.startActivity(
                Intent(context, RecallActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }

        /**
         * MBM v1.0.31：系统剪贴板单次事务软上限（Binder ~1MB，留 10% 余量）。
         * 这不是内容上限：超过的内容不截断，改走「缓存文件 + 系统分享面板」交出全文。
         */
        private const val CLIPBOARD_SOFT_LIMIT = 900 * 1024
    }
}
