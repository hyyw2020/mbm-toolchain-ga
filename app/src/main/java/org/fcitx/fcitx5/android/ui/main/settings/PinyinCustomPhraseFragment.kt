/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 *
 * 在原生「管理自定义词组」基础上增强（MBM v1.0.7）：
 *  - 顶部搜索框：按键或词组内容精确/模糊查找；
 *  - 每行「锁定」按钮：锁定后既不被「删除未锁定词组」删除，也不在列表多选删除中被勾选；
 *  - 工具条「删除未锁定词组」：批量清理一次性词组，保留已锁定的固定词条。
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.appcompat.widget.AppCompatButton
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.reloadPinyinCustomPhrase
import org.fcitx.fcitx5.android.data.pinyin.CustomPhraseManager
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PinyinCustomPhrase
import org.fcitx.fcitx5.android.data.pinyin.customphrase.AppPhraseImporter
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PhraseLockStore
import org.fcitx.fcitx5.android.data.pinyin.customphrase.PhrasePinStore
import org.fcitx.fcitx5.android.recall.RecallConfig
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener
import org.fcitx.fcitx5.android.ui.main.EditDeleteMenuProvider
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.utils.NaiveDustman
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.PinyinUtils
import org.fcitx.fcitx5.android.utils.MbmDebug
import org.fcitx.fcitx5.android.utils.materialTextInput
import org.fcitx.fcitx5.android.utils.onPositiveButtonClick
import org.fcitx.fcitx5.android.utils.str
import org.fcitx.fcitx5.android.data.smartdict.SmartDictManager
import splitties.views.dsl.core.add
import splitties.views.dsl.core.editText
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.core.verticalLayout
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.setPaddingDp
import kotlin.math.absoluteValue

class PinyinCustomPhraseFragment : Fragment(), OnItemChangedListener<PinyinCustomPhrase> {

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var ui: BaseDynamicListUi<PinyinCustomPhrase>

    private var searchQuery: String = ""

    // MBM（词库 UI 统一）：锁定筛选（0=全部 1=已锁定 2=未锁定）+ 筛选按钮引用
    private var lockFilter: Int = 0
    private lateinit var lockFilterButtons: List<AppCompatButton>

    // MBM v1.0.22：统一「用户词库」屏的附属状态
    private lateinit var smartListView: LinearLayout
    private lateinit var dictListView: LinearLayout
    private var dictEntries: List<SmartDictManager.DictEntry> = emptyList()
    private val dictSelection = mutableSetOf<Int>()

    // MBM v1.0.24：分帧渲染防 OOM/ANR（与 RecallActivity 同源策略，pitfalls #38/#40）。
    private var smartRenderJob: Job? = null

    private val dustman = NaiveDustman<PinyinCustomPhrase>()

    // MBM v0.0.05：应用名即时候选写入的条目全部登记在 AppPhraseImporter 的 managed
    // 集合中，管理界面过滤掉（否则几百条应用名会淹没用户自造词组，见 pitfalls #56）
    private val initialItems = run {
        // 属性初始化期 Fragment 尚未 attach，requireContext() 会抛异常，用全局 appContext
        (CustomPhraseManager.loadCached() ?: emptyArray())
            // v1.0.41：只过滤「真的是应用名条目」的托管项；与应用名同形（key=value 相同）
            // 的 Recall 屏幕记忆词条属于用户资产，要留在列表里（否则保存时会被冲掉）
            .filterNot { AppPhraseImporter.isManagedAppEntry(appContext, it) }
    }

    private var keyLabel = KEY
    private var orderLabel = ORDER
    private var phraseLabel = PHRASE

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        lifecycleScope.launch {
            viewModel.fcitx.runOnReady {
                keyLabel = translate(KEY, CHINESE_ADDONS_DOMAIN)
                orderLabel = translate(ORDER, CHINESE_ADDONS_DOMAIN)
                phraseLabel = translate(PHRASE, CHINESE_ADDONS_DOMAIN)
            }
        }
        val initialEntries = initialItems.toList()
        ui = object : BaseDynamicListUi<PinyinCustomPhrase>(
            requireContext(),
            Mode.FreeAdd("", converter = { PinyinCustomPhrase("", 1, "") }),
            initialItems.toList(),
            enableOrder = true,
            initCheckBox = { entry ->
                isChecked = entry.enabled
                setOnCheckedChangeListener { _, checked ->
                    ui.updateItem(ui.indexItem(entry), entry.copyEnabled(checked))
                }
            },
            initSettingsButton = { entry ->
                // 锁定/解锁按钮：显示当前锁状态，点击切换
                applyLockIcon(this, entry)
                setOnClickListener { toggleLock(entry, this) }
            },
            initPinButton = { entry ->
                // 置顶/取消置顶按钮：显示当前置顶状态，点击切换（最高优先级档，order=1）
                applyPinIcon(this, entry)
                setOnClickListener { togglePin(entry, this) }
            }
        ) {
            override fun showEntry(x: PinyinCustomPhrase): String {
                // 主文本：词组原文（不再展示原始的「key,order=value」序列化串，避免布局凌乱）
                return x.value
            }

            override fun showSubEntry(x: PinyinCustomPhrase): String? {
                // 副文本：键 + 拼音首字母 + 全拼 + 排序值 + 状态，统一展示，便于核对/搜索/排序
                val ini = PinyinUtils.initials(x.value).orEmpty()
                val full = PinyinUtils.fullPinyin(x.value).orEmpty()
                val pinned = PhrasePinStore.isPinned(requireContext(), x)
                return buildString {
                    append("键 ")
                    append(x.key)
                    if (ini.isNotBlank()) append(" · 首字母 $ini")
                    if (full.isNotBlank() && full != ini) append(" · 全拼 $full")
                    append(" · 排序 ${x.order}")
                    if (pinned) append(" · 已置顶")
                    if (!x.enabled) append(" · 已禁用")
                    if (PhraseLockStore.isLocked(requireContext(), x)) append(" · 已锁定")
                }.takeIf { it.isNotBlank() }
            }

            override fun showEditDialog(
                title: String,
                entry: PinyinCustomPhrase?,
                block: (PinyinCustomPhrase) -> Unit
            ) {
                val (keyLayout, keyField) = materialTextInput {
                    hint = keyLabel
                }
                keyField.apply {
                    isSingleLine = true
                    filters = arrayOf(
                        InputFilter { source, _, _, _, _, _ ->
                            source.filter { it.code in 'A'.code..'Z'.code || it.code in 'a'.code..'z'.code }
                        }
                    )
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                    inputType =
                        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                val (orderLayout, orderField) = materialTextInput {
                    hint = orderLabel
                }
                orderField.apply {
                    isSingleLine = true
                    inputType =
                        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                }
                val (phraseLayout, phraseField) = materialTextInput {
                    hint = phraseLabel
                }
                phraseField.apply {
                    isSingleLine = false
                    maxLines = 8
                }
                entry?.apply {
                    keyField.setText(key)
                    orderField.setText(order.absoluteValue.toString(10))
                    phraseField.setText(value)
                }
                val layout = verticalLayout {
                    setPaddingDp(20, 10, 20, 0)
                    add(keyLayout, lParams(matchParent))
                    add(orderLayout, lParams(matchParent))
                    add(phraseLayout, lParams(matchParent))
                }
                AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(layout)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                    .onPositiveButtonClick onClick@{
                        val key = keyField.str
                        if (key.isBlank()) {
                            keyField.error = getString(R.string._cannot_be_empty, keyLabel)
                            keyField.requestFocus()
                            return@onClick false
                        } else {
                            keyField.error = null
                        }
                        val order = orderField.str.toIntOrNull() ?: 1
                        val phrase = phraseField.str
                        if (phrase.isEmpty()) {
                            phraseField.error = getString(R.string._cannot_be_empty, phraseLabel)
                            phraseField.requestFocus()
                            return@onClick false
                        } else {
                            phraseField.error = null
                        }
                        block(PinyinCustomPhrase(key, order, phrase))
                        return@onClick true
                    }
                    .setCanceledOnTouchOutside(false)
            }
        }
        ui.addOnItemChangedListener(this)
        ui.addTouchCallback()
        // 锁定项不可被删除（多选删除与「删除未锁定」都会跳过）
        ui.removable = { !PhraseLockStore.isLocked(requireContext(), it) }
        resetDustman()
        ui.setViewModel(viewModel)

        val ctx = requireContext()

        // ============ 顶部三段切换：自定义词组 / 智能候选词 / 已安装词库 ============
        val segCustom = AppCompatButton(ctx).apply { text = "自定义词组" }
        val segSmart = AppCompatButton(ctx).apply { text = "智能候选词" }
        val segDict = AppCompatButton(ctx).apply { text = "已安装词库" }
        val segBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(4))
            listOf(segCustom, segSmart, segDict).forEach { b ->
                add(b, lParams(0, wrapContent) { weight = 1f; marginStart = dp(4); marginEnd = dp(4) })
            }
        }
        fun highlightSeg(sel: Int) {
            listOf(segCustom, segSmart, segDict).forEachIndexed { i, b ->
                val on = i == sel
                b.setBackgroundColor(if (on) SEG_ON_BG else SEG_OFF_BG)
                b.setTextColor(SEG_FG)
            }
        }

        // ---- 自定义词组容器（保留原有全部功能：搜索 / 锁定 / 多选删除 / 100MB 上限）----
        val searchBox = ctx.editText {
            hint = "搜索（键 / 词组内容 / 拼音首字母，如 wjjq）"
            setTextColor(ctx.styledColor(android.R.attr.editTextColor))
            setHintTextColor(ctx.styledColor(android.R.attr.textColorHint))
            textSize = 14f
            addTextChangedListener { s ->
                searchQuery = s?.toString().orEmpty()
                applyFilter()
            }
        }
        val delUnlockedBtn = AppCompatButton(ctx).apply {
            text = "删除未锁定词组"
            textSize = 13f
            setOnClickListener { deleteUnlocked() }
        }
        // MBM（词库 UI 统一）：锁定筛选（全部 / 已锁定 / 未锁定）+ 排序说明
        val filterAll = AppCompatButton(ctx).apply { text = "全部"; textSize = 13f }
        val filterLocked = AppCompatButton(ctx).apply { text = "已锁定"; textSize = 13f }
        val filterUnlocked = AppCompatButton(ctx).apply { text = "未锁定"; textSize = 13f }
        lockFilterButtons = listOf(filterAll, filterLocked, filterUnlocked)
        val lockFilterRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            listOf(filterAll to 0, filterLocked to 1, filterUnlocked to 2).forEach { (b, v) ->
                add(b, lParams(0, wrapContent) { weight = 1f; marginStart = dp(4); marginEnd = dp(4) })
                b.setOnClickListener { setLockFilter(v) }
            }
        }
        val sortHint = android.widget.TextView(ctx).apply {
            text = "排序：数字越小越靠前，置顶=1（最高优先级，压过应用名与 Recall 词）；点 ⬆ 置顶，点 🔒 锁定防误删"
            textSize = 11f
            setTextColor(ctx.styledColor(android.R.attr.textColorSecondary))
            setPadding(dp(8), dp(2), dp(8), dp(2))
        }
        // MBM v0.0.05：应用名快捷输入开关 + 手动刷新（pitfalls #56）
        // MBM v0.0.06 自检行：用户反馈「qmqz 完全不出全民枪战」时，先让用户能自己确认
        // 到底读到了几个应用、有没有真的写进词库，避免黑盒排障。
        val appPhraseStats = android.widget.TextView(ctx).apply {
            textSize = 12f
            setTextColor(ctx.styledColor(android.R.attr.textColorSecondary))
            setPadding(dp(8), dp(2), dp(8), dp(6))
            text = "应用名条目：统计中…"
        }
        fun refreshAppStats() {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val (apps, entries) = AppPhraseImporter.stats(ctx)
                val imported = AppPhraseImporter.isImported(ctx)
                val enabled = AppPhraseImporter.isEnabled(ctx)
                val msg = when {
                    !enabled -> "应用名快捷输入：已关闭"
                    apps == 0 -> "⚠ 读到 0 个应用：本 ROM 可能限制了包可见性，将在下次启动自动重试"
                    !imported -> "⚠ 已读到 $apps 个应用 / $entries 条候选，但尚未写入词库（下次启动自动重试）"
                    else -> "✓ 已导入 $apps 个应用 / $entries 条候选（在「搜索应用」等界面输入首字母即出）"
                }
                withContext(Dispatchers.Main) { appPhraseStats.text = msg }
            }
        }
        refreshAppStats()
        val appPhraseSwitch = SwitchMaterial(ctx).apply {
            text = "应用名快捷输入：输入首字母直接出应用名（如 qmqz→全民枪战2）"
            textSize = 13f
            isChecked = AppPhraseImporter.isEnabled(ctx)
            setOnCheckedChangeListener { _, checked ->
                AppPhraseImporter.setEnabled(ctx, checked)
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.fcitx.runOnReady { reloadPinyinCustomPhrase() }
                    AppPhraseImporter.refresh(ctx, viewModel.fcitx)
                }
            }
        }
        val appPhraseRefreshBtn = AppCompatButton(ctx).apply {
            text = "刷新应用名单"
            textSize = 13f
            setOnClickListener {
                viewLifecycleOwner.lifecycleScope.launch {
                    Toast.makeText(ctx, "正在刷新应用名单…", Toast.LENGTH_SHORT).show()
                    AppPhraseImporter.refresh(ctx, viewModel.fcitx)
                    refreshAppStats()
                    Toast.makeText(ctx, "应用名单已刷新", Toast.LENGTH_SHORT).show()
                }
            }
        }
        val appPhraseRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(appPhraseSwitch, lParams(0, wrapContent) { weight = 1f })
            addView(appPhraseRefreshBtn, lParams(wrapContent, wrapContent) {
                leftMargin = dp(8)
            })
        }
        val customContainer = ctx.verticalLayout {
            setBackgroundColor(ctx.styledColor(android.R.attr.colorBackground))
            add(searchBox, lParams(matchParent) {
                leftMargin = dp(8); rightMargin = dp(8); topMargin = dp(8); bottomMargin = dp(4)
            })
            add(delUnlockedBtn, lParams(matchParent) {
                leftMargin = dp(8); rightMargin = dp(8); bottomMargin = dp(4)
            })
            add(appPhraseRow, lParams(matchParent) {
                leftMargin = dp(8); rightMargin = dp(8); bottomMargin = dp(4)
            })
            add(appPhraseStats, lParams(matchParent) {
                leftMargin = dp(8); rightMargin = dp(8); bottomMargin = dp(4)
            })
            add(lockFilterRow, lParams(matchParent) {
                leftMargin = dp(8); rightMargin = dp(8); bottomMargin = dp(4)
            })
            add(sortHint, lParams(matchParent) {
                leftMargin = dp(8); rightMargin = dp(8); bottomMargin = dp(4)
            })
            add(ui.root, lParams(matchParent, 0) { weight = 1f })
        }

        // ---- 智能候选词容器（本地统计生成，可转存为自定义词组）----
        val smartPromoteAll = AppCompatButton(ctx).apply {
            text = "一键全部转存"; textSize = 13f; setOnClickListener { promoteAll() }
        }
        val smartClear = AppCompatButton(ctx).apply {
            text = "清空本地统计"; textSize = 13f; setOnClickListener { clearStats() }
        }
        val smartToolbar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            add(smartPromoteAll, lParams(0, wrapContent) { weight = 1f; marginStart = dp(8); marginEnd = dp(4) })
            add(smartClear, lParams(0, wrapContent) { weight = 1f; marginStart = dp(4); marginEnd = dp(8) })
        }
        val smartList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        smartListView = smartList
        val smartScroll = ScrollView(ctx).apply { add(smartList, lParams(matchParent)) }
        val smartContainer = ctx.verticalLayout {
            setBackgroundColor(ctx.styledColor(android.R.attr.colorBackground))
            add(smartToolbar, lParams(matchParent) {
                leftMargin = dp(8); rightMargin = dp(8); topMargin = dp(8); bottomMargin = dp(4)
            })
            add(smartScroll, lParams(matchParent, 0) { weight = 1f })
        }
        smartContainer.visibility = View.GONE

        // ---- 已安装词库容器（Rime / 拼音 / 码表，可勾选删除）----
        val dictSelectAll = AppCompatButton(ctx).apply {
            text = "全选"; textSize = 13f; setOnClickListener { selectAllDicts() }
        }
        val dictSelectNone = AppCompatButton(ctx).apply {
            text = "全不选"; textSize = 13f; setOnClickListener { clearDictSelection() }
        }
        val dictDelete = AppCompatButton(ctx).apply {
            text = "删除选中项"; textSize = 13f; setOnClickListener { deleteSelectedDicts() }
        }
        val dictToolbar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            add(dictSelectAll, lParams(0, wrapContent) { weight = 1f; marginStart = dp(8); marginEnd = dp(2) })
            add(dictSelectNone, lParams(0, wrapContent) { weight = 1f; marginStart = dp(2); marginEnd = dp(2) })
            add(dictDelete, lParams(0, wrapContent) { weight = 1f; marginStart = dp(2); marginEnd = dp(8) })
        }
        val dictList = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        dictListView = dictList
        val dictScroll = ScrollView(ctx).apply { add(dictList, lParams(matchParent)) }
        val dictContainer = ctx.verticalLayout {
            setBackgroundColor(ctx.styledColor(android.R.attr.colorBackground))
            add(dictToolbar, lParams(matchParent) {
                leftMargin = dp(8); rightMargin = dp(8); topMargin = dp(8); bottomMargin = dp(4)
            })
            add(dictScroll, lParams(matchParent, 0) { weight = 1f })
        }
        dictContainer.visibility = View.GONE

        val root = ctx.verticalLayout {
            setBackgroundColor(ctx.styledColor(android.R.attr.colorBackground))
            add(segBar, lParams(matchParent))
            add(customContainer, lParams(matchParent, 0) { weight = 1f })
            add(smartContainer, lParams(matchParent, 0) { weight = 1f })
            add(dictContainer, lParams(matchParent, 0) { weight = 1f })
        }

        fun showSeg(sel: Int) {
            highlightSeg(sel)
            customContainer.visibility = if (sel == 0) View.VISIBLE else View.GONE
            smartContainer.visibility = if (sel == 1) View.VISIBLE else View.GONE
            dictContainer.visibility = if (sel == 2) View.VISIBLE else View.GONE
            if (sel == 1) loadSmartWords()
            if (sel == 2) loadDicts()
        }
        segCustom.setOnClickListener { showSeg(0) }
        segSmart.setOnClickListener { showSeg(1) }
        segDict.setOnClickListener { showSeg(2) }
        showSeg(0)
        // 预拉取另两段数据，切换时无需等待
        loadSmartWords()
        loadDicts()
        return root
    }

    /**
     * MBM（词库 UI 统一）：用「单一列表 + 过滤器」取代原先的 去重视图 / 搜索结果 三套渲染。
     * 搜索与锁定筛选都只是对同一个 ui.root 列表的过滤——绝不会因切视图而让锁/删/排序按钮消失，
     * 也绝不会丢数据（过滤器只影响 _shown 视图，保存始终以 _all 为准）。
     */
    private fun applyFilter() {
        val q = searchQuery.trim()
        val ctx = requireContext()
        val pred: (PinyinCustomPhrase) -> Boolean = { e ->
            (q.isBlank() || e.key.contains(q, ignoreCase = true) ||
                e.value.contains(q, ignoreCase = true) ||
                PinyinUtils.initials(e.value)?.contains(q, ignoreCase = true) == true) &&
                when (lockFilter) {
                    1 -> PhraseLockStore.isLocked(ctx, e)
                    2 -> !PhraseLockStore.isLocked(ctx, e)
                    else -> true
                }
        }
        ui.setFilter(pred)
    }

    /** 切换「全部 / 已锁定 / 未锁定」筛选并高亮当前选项 */
    private fun setLockFilter(mode: Int) {
        lockFilter = mode
        lockFilterButtons.forEachIndexed { i, b ->
            b.setBackgroundColor(if (i == mode) SEG_ON_BG else SEG_OFF_BG)
            b.setTextColor(SEG_FG)
        }
        applyFilter()
    }

    /** 置顶/取消置顶（最高优先级档 order=1，稳定压过应用名与 Recall 热/冷词） */
    private fun togglePin(entry: PinyinCustomPhrase, btn: ImageButton) {
        val ctx = requireContext()
        val idx = ui.indexItem(entry)
        if (idx < 0) return
        if (PhrasePinStore.isPinned(ctx, entry)) {
            val orig = PhrasePinStore.getOriginalOrder(ctx, entry, USER_DEFAULT_ORDER)
            PhrasePinStore.unpin(ctx, entry)
            ui.updateItem(idx, entry.copy(order = orig))
        } else {
            PhrasePinStore.pin(ctx, entry, entry.order)
            ui.updateItem(idx, entry.copy(order = RecallConfig.PIN_ORDER))
        }
        applyPinIcon(btn, entry)
        applyFilter()
    }

    private fun applyPinIcon(btn: ImageButton, entry: PinyinCustomPhrase) {
        val pinned = PhrasePinStore.isPinned(requireContext(), entry)
        btn.setImageResource(R.drawable.ic_baseline_arrow_upward_24)
        val color = if (pinned) 0xFF2F81F7.toInt()
        else requireContext().styledColor(android.R.attr.colorControlNormal)
        btn.imageTintList = android.content.res.ColorStateList.valueOf(color)
        btn.contentDescription = if (pinned) "已置顶，点击取消置顶" else "未置顶，点击置顶"
    }

    /** 切换锁定并更新该行图标与可删状态 */
    private fun toggleLock(entry: PinyinCustomPhrase, btn: ImageButton) {
        PhraseLockStore.toggle(requireContext(), entry)
        applyLockIcon(btn, entry)
        // 重新绑定该行，使 removable 立即生效（锁定后不可被多选删除）
        val idx = ui.indexItem(entry)
        if (idx >= 0) ui.notifyItemChanged(idx)
        // 若当前正处于「已锁定 / 未锁定」筛选，锁定状态变化后即时刷新列表
        applyFilter()
    }

    private fun applyLockIcon(btn: ImageButton, entry: PinyinCustomPhrase) {
        val locked = PhraseLockStore.isLocked(requireContext(), entry)
        btn.setImageResource(
            if (locked) android.R.drawable.ic_lock_lock
            else android.R.drawable.ic_lock_idle_lock
        )
        btn.contentDescription = if (locked) "已锁定，点击解锁" else "未锁定，点击锁定"
    }

    /** 批量删除未锁定的词组（保留锁定项），支持 snackbar 撤销 */
    private fun deleteUnlocked() {
        val ctx = requireContext()
        // 在「全部」视图下计算待删集合，确保筛选状态下也不会漏删/受搜索过滤影响
        ui.clearFilter()
        val unlocked = ui.entries.filter { !PhraseLockStore.isLocked(ctx, it) }
        if (unlocked.isEmpty()) {
            Toast.makeText(ctx, "没有可删除的未锁定词组", Toast.LENGTH_SHORT).show()
            applyFilter()
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle("删除未锁定词组")
            .setMessage(
                "将删除 ${unlocked.size} 条未锁定自定义词组，已锁定的会保留。" +
                    "删除后可在下方撤销提示中恢复。"
            )
            .setPositiveButton("删除") { _, _ ->
                unlocked.mapNotNull { ui.indexItem(it).takeIf { i -> i >= 0 } }
                    .sortedDescending()
                    .forEach { ui.removeItem(it) }
                applyFilter()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> applyFilter() }
            .show()
    }

    // ============ 智能候选词（本地统计）交互 ============

    private fun loadSmartWords() {
        smartRenderJob?.cancel()
        lifecycleScope.launch {
            // MBM v1.0.23：无上限 —— 返回全部本地统计，不再只取前 50
            val words = withContext(Dispatchers.IO) { SmartDictManager.topWords() }
            if (!isAdded) return@launch
            renderSmart(words)
        }
    }

    /** MBM v1.0.24：分帧渲染，修复无上限全量词在主线程一次性建 View 导致的 OOM/ANR */
    private fun renderSmart(words: List<Pair<String, Int>>) {
        if (!::smartListView.isInitialized) return
        smartRenderJob?.cancel()
        smartRenderJob = lifecycleScope.launch {
            val ctx = context ?: return@launch
            smartListView.removeAllViews()
            // 头部总数（无上限后用户需要知道规模）
            smartListView.addView(TextView(ctx).apply {
                text = "共 ${words.size} 条（无上限 · 本地统计）"
                textSize = 12f
                setTextColor(DIM)
                setPadding(dp(12), dp(6), dp(12), dp(2))
            })
            if (words.isEmpty()) {
                smartListView.addView(TextView(ctx).apply {
                    text = "暂无本地统计（仅在非密码输入下记录；正常打字后此处会出现高频词）"
                    textSize = 13f
                    setTextColor(DIM)
                    setPadding(dp(12), dp(16), dp(12), dp(16))
                })
                return@launch
            }
            // 分帧构建，块间让出主线程（同 RecallActivity，pitfalls #38/#40）
            words.chunked(300).forEach { chunk ->
                if (!isAdded) return@launch
                chunk.forEach { (w, c) -> smartListView.addView(buildSmartRow(ctx, w, c)) }
                yield()
            }
        }
    }

    private fun buildSmartRow(ctx: Context, w: String, c: Int): LinearLayout {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(PANEL_BG)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        val host = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(6)
            }
        }
        host.addView(TextView(ctx).apply { text = w; textSize = 15f; setTextColor(FG) })
        host.addView(TextView(ctx).apply { text = "出现 $c 次"; textSize = 11f; setTextColor(DIM) })
        row.addView(host)
        row.addView(AppCompatButton(ctx).apply {
            text = "保存"; textSize = 12f
            setOnClickListener { promoteWord(w) }
        })
        row.addView(AppCompatButton(ctx).apply {
            text = "忽略"; textSize = 12f
            setOnClickListener { ignoreWord(w) }
        })
        return row
    }

    /** 将一条本地统计词转存为自定义词组（与手动词组共享同一 100MB 词典） */
    private fun promoteWord(word: String) {
        if (ui.allItems().any { it.value == word }) {
            Toast.makeText(requireContext(), "「$word」已在自定义词组中", Toast.LENGTH_SHORT).show()
            return
        }
        val key = PinyinUtils.fullPinyin(word)?.takeIf { it.isNotBlank() }
            ?: PinyinUtils.initials(word)?.takeIf { it.isNotBlank() } ?: word
        ui.addItem(item = PinyinCustomPhrase(key, 1, word))
        saveConfig()
        Toast.makeText(requireContext(), "已保存「$word」到自定义词组", Toast.LENGTH_SHORT).show()
    }

    private fun promoteAll() {
        lifecycleScope.launch {
            val words = withContext(Dispatchers.IO) { SmartDictManager.topWords() }
            val initialCount = ui.allItems().size
            var added = 0
            words.forEach { (w, _) ->
                if (ui.allItems().any { it.value == w }) return@forEach
                val key = PinyinUtils.fullPinyin(w)?.takeIf { it.isNotBlank() }
                    ?: PinyinUtils.initials(w)?.takeIf { it.isNotBlank() } ?: w
                ui.addItem(item = PinyinCustomPhrase(key, 1, w))
                added++
            }
            saveConfig()
            loadSmartWords()
            Toast.makeText(requireContext(), "已转存 $added 条到自定义词组", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ignoreWord(word: String) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { SmartDictManager.removeWord(word) }
            loadSmartWords()
        }
    }

    private fun clearStats() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { SmartDictManager.clearUsage() }
            loadSmartWords()
            Toast.makeText(requireContext(), "已清空本地统计", Toast.LENGTH_SHORT).show()
        }
    }

    // ============ 已安装词库（Rime / 拼音 / 码表）交互 ============

    private fun loadDicts() {
        lifecycleScope.launch {
            dictEntries = withContext(Dispatchers.IO) { SmartDictManager.listAll() }
            renderDicts()
        }
    }

    private fun renderDicts() {
        val ctx = requireContext()
        dictListView.removeAllViews()
        dictEntries.forEachIndexed { index, entry ->
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(PANEL_BG)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            }
            val check = CheckBox(ctx).apply { isChecked = dictSelection.contains(index) }
            check.setOnCheckedChangeListener { _, checked ->
                if (checked) dictSelection += index else dictSelection -= index
            }
            val host = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(6)
                }
            }
            host.addView(TextView(ctx).apply {
                text = entry.name; textSize = 13f; setTextColor(FG); setSingleLine(true)
            })
            host.addView(TextView(ctx).apply {
                text = "${entry.kind.display} · ${"%.1f".format(entry.sizeBytes / 1024f / 1024f)} MB"
                textSize = 11f; setTextColor(DIM)
            })
            row.addView(check)
            row.addView(host)
            dictListView.addView(row)
        }
    }

    private fun selectAllDicts() {
        dictSelection.clear()
        dictSelection += dictEntries.indices
        renderDicts()
    }

    private fun clearDictSelection() {
        dictSelection.clear()
        renderDicts()
    }

    private fun deleteSelectedDicts() {
        val targets = dictSelection.mapNotNull { dictEntries.getOrNull(it) }
        if (targets.isEmpty()) return
        lifecycleScope.launch {
            val n = withContext(Dispatchers.IO) { SmartDictManager.delete(targets) }
            dictSelection.clear()
            loadDicts()
            Toast.makeText(requireContext(), "已删除 $n 项", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // MBM v1.0.36：用户词库页根打 pinyin.phrase 标记（自测 settings 层经
        // smartdict 入口进入本页后收集，验证「用户词库」功能可达）
        MbmDebug.markFeature(view, "pinyin.phrase")
        viewModel.toolbarButton.value =
            if (ui.allItems().isNotEmpty()) ButtonMode.EDIT else ButtonMode.NONE
        requireActivity().addMenuProvider(
            EditDeleteMenuProvider(
                buttonMode = viewModel.toolbarButton,
                editButtonAction = { ui.enterMultiSelect(requireActivity().onBackPressedDispatcher) },
                deleteButtonAction = { ui.deleteSelected(); ui.exitMultiSelect() },
                menuHost = requireActivity(),
                lifecycleOwner = viewLifecycleOwner,
            ),
            viewLifecycleOwner,
            Lifecycle.State.STARTED
        )
    }

    override fun onItemAdded(idx: Int, item: PinyinCustomPhrase) {
        dustman.addOrUpdate(item.serialize(), item)
    }

    override fun onItemRemoved(idx: Int, item: PinyinCustomPhrase) {
        dustman.remove(item.serialize())
    }

    override fun onItemRemovedBatch(indexed: List<Pair<Int, PinyinCustomPhrase>>) {
        batchRemove(indexed)
    }

    override fun onItemUpdated(idx: Int, old: PinyinCustomPhrase, new: PinyinCustomPhrase) {
        dustman.remove(old.serialize())
        dustman.addOrUpdate(new.serialize(), new)
    }

    override fun onDestroyView() {
        // MBM v1.0.24：离开页面取消进行中的分帧渲染，协程内已用 isAdded/context 双保险
        smartRenderJob?.cancel()
        super.onDestroyView()
    }

    private fun saveConfig() {
        if (!dustman.dirty) return
        val items = ui.allItems().toTypedArray()
        // MBM v1.0.31「词库大小无上限」：移除 100MB 落盘裁剪（pitfalls #70）。
        // 估算值只用于**告警**（超过历史风险区时提示），不再裁剪、不丢用户数据；
        // 保存失败要精确可观测（条目数/字节数），不静默、不崩溃。
        val estimated = PinyinCustomPhrase.estimateBytes(items)
        val oversized = estimated > PinyinCustomPhrase.STORE_WARN_BYTES
        // MBM v0.0.05：合并应用名条目后再落盘——用户保存的是过滤后的词条，
        // 不合并会把 managed 条目全部冲掉（pitfalls #56）
        val appEntries = AppPhraseImporter.freshEntries(requireContext())
        val merged: List<PinyinCustomPhrase> = items.toList() + appEntries
        val finalItems = merged.toTypedArray()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { CustomPhraseManager.saveCached(finalItems) }.isSuccess
            }
            // 保存失败不 reset dirty：留在页面里的内容仍标记未保存，下次 onStop 会重试
            if (ok) {
                resetDustman()
                viewModel.fcitx.runOnReady {
                    reloadPinyinCustomPhrase()
                }
            }
            // 用可空 context，避免 Fragment 已 detach（如 onDestroy 调用 saveConfig）时 requireContext() 抛异常。
            context?.let {
                Toast.makeText(
                    it,
                    when {
                        !ok -> "词库保存失败：${finalItems.size} 条 / 约 ${estimated / 1048576} MB。" +
                            "请分批删除后再试（内容未丢失，可重试保存）"
                        oversized -> "词库约 ${estimated / 1048576} MB / ${finalItems.size} 条，已全量保存"
                        else -> return@let
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun resetDustman() {
        dustman.reset(ui.allItems().associateBy { it.serialize() })
    }

    override fun onStart() {
        super.onStart()
        // MBM v1.0.22：统一「用户词库」屏，标题不再仅指自定义词组
        viewModel.setToolbarTitle("用户词库")
    }

    override fun onStop() {
        saveConfig()
        ui.exitMultiSelect()
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeItemChangedListener()
        super.onDestroy()
    }

    companion object {
        const val CHINESE_ADDONS_DOMAIN = "fcitx5-chinese-addons"
        const val KEY = "Key"
        const val ORDER = "Order"
        const val PHRASE = "Phrase"
        const val MANAGE_CUSTOM_PHRASE = "Manage Custom Phrase"
        // 用户自定义词组未置顶时的默认排序档（高于 Recall 冷词/应用名普通档，低于应用名搜索档）
        const val USER_DEFAULT_ORDER = 5
        // 三段切换高亮配色（深色，固定值避免主题属性解析风险）
        private val SEG_ON_BG = 0xFF2F81F7.toInt()
        private val SEG_OFF_BG = 0xFF21262D.toInt()
        private val SEG_FG = 0xFFE6EDF3.toInt()
        private val PANEL_BG = 0xFF161B22.toInt()
        private val FG = 0xFFE6EDF3.toInt()
        private val DIM = 0xFF7D8590.toInt()
    }

}
