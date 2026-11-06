/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: MBM Contributors
 */
package org.fcitx.fcitx5.android.input.clipboard

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayout
import com.google.android.flexbox.JustifyContent
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import splitties.dimensions.dp
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

/**
 * MBM v1.0.45：「滑动选词」面板。
 *
 * 交互（与主流输入法的拆分选词一比一）：
 *  - 正文按词典切成词块，横向流式排布、可纵向滚动；
 *  - 手指按住任意词块滑动即可连续选中（跨行自动延伸），松手保留选中态；
 *  - 按住滑动到面板上/下边缘时自动滚动并继续延伸选区，长文本也能选到后半段；
 *  - 按在空白处拖动则退化为普通滚动/惯性滑动（不选中）；
 *  - 右下角胶囊操作条：全选（勾选框）｜复制｜完成。
 *
 * 触摸模型：滚动容器永不拦截（[NoInterceptScrollView]），词块区只消费落在词块上的
 * 触摸（返回 true），空白处不消费、交还给 ScrollView 完成滚动。
 */
class WordSelectUi(
    override val ctx: Context,
    private val theme: Theme,
    /** 待拆分的完整原文 */
    private val source: String,
    /**
     * 面板内词块是否遮蔽。v1.0.74 起由调用方按
     * [WordSelectMaskPolicy.shouldMask] 计算后传入（条目敏感 && 用户偏好），
     * 不再是全局「隐藏敏感信息」开关本身。
     */
    private val maskSensitive: Boolean = false
) : Ui {

    private data class Chip(val view: TextView, val token: WordTokenizer.Token)

    private val chips = ArrayList<Chip>()
    private var result: WordTokenizer.Result = WordTokenizer.Result(source, emptyList(), false)

    /** 当前选中范围（词块下标，闭区间）；-1 表示未选中 */
    private var selStart = -1
    private var selEnd = -1

    /** 手势起始词块，滑动时选区在 [anchor, current] 间伸缩 */
    private var anchorIndex = -1

    /** 手指当前所在词块（独立于 selEnd 单独跟踪，缩小后再扩大也不会漏更新） */
    private var currentIndex = -1

    /** 正在程序化同步全选框状态，抑制监听器回写 */
    private var syncingSelectAll = false

    /** 最近一次回填给上屏预览的选区文本，避免滑动时重复触发服务层调用 */
    private var lastPreview: String? = null

    /**
     * 选区变化回调：实时把选中文本交给服务层做上屏预览。
     * null 表示当前无选区（清空/未选）。
     */
    var onSelectionChanged: ((text: String?) -> Unit)? = null

    // ---- 触摸与边缘自动滚动 ----

    private val handler = Handler(Looper.getMainLooper())
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val edgeScrollTicker = object : Runnable {
        override fun run() {
            val viewportH = scrollView.height
            if (viewportH > 0) {
                // 触点换算到可视区坐标
                val vy = lastTouchY - scrollView.scrollY
                val zone = ctx.dp(48)
                val speed = when {
                    vy < zone -> -speedFor(zone - vy)
                    vy > viewportH - zone -> speedFor(vy - (viewportH - zone))
                    else -> 0
                }
                if (speed != 0) {
                    scrollView.scrollBy(0, speed)
                    // 内容滚动了但手指没动，不会再来 MOVE 事件：主动重算指下词块延伸选区
                    val idx = chipAt(lastTouchX, lastTouchY)
                    if (idx in chips.indices && idx != currentIndex) {
                        anchorIndex = anchorIndex.coerceAtLeast(0)
                        currentIndex = idx
                        selectRange(anchorIndex, currentIndex)
                    }
                }
            }
            handler.postDelayed(this, 16L)
        }
    }

    private fun speedFor(depth: Float): Int = (depth.toInt() / 2 + 6).coerceIn(6, 36)

    private inner class ChipArea(context: Context) : FlexboxLayout(context) {
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    val idx = chipAt(event.x, event.y)
                    if (idx < 0) return false
                    anchorIndex = idx
                    currentIndex = idx
                    selectRange(idx, idx)
                    handler.removeCallbacks(edgeScrollTicker)
                    handler.post(edgeScrollTicker)
                }
                MotionEvent.ACTION_MOVE -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    val idx = chipAt(event.x, event.y)
                    // 移到空白处：维持当前选区，把事件交还给 ScrollView 做滚动/惯性
                    if (idx in chips.indices && idx != currentIndex) {
                        anchorIndex = anchorIndex.coerceAtLeast(0)
                        currentIndex = idx
                        selectRange(anchorIndex, currentIndex)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(edgeScrollTicker)
                }
            }
            return true
        }
    }

    private fun chipAt(x: Float, y: Float): Int {
        for (i in chips.indices) {
            val v = chips[i].view
            if (x >= v.left && x <= v.right && y >= v.top && y <= v.bottom) return i
        }
        return -1
    }

    /**
     * 选中 [a, b]（自动交换端点），全量刷新词块高亮，保证拖回时视觉正确。
     */
    private fun selectRange(a: Int, b: Int) {
        if (chips.isEmpty()) return
        anchorIndex = a.coerceIn(0, chips.size - 1)
        val lo = minOf(anchorIndex, b).coerceAtLeast(0)
        val hi = maxOf(anchorIndex, b).coerceAtMost(chips.size - 1)
        selStart = lo
        selEnd = hi
        for (i in chips.indices) {
            setChipSelected(i, i in lo..hi)
        }
        // 全选框联动
        syncingSelectAll = true
        selectAllBox.isChecked = lo == 0 && hi == chips.size - 1
        syncingSelectAll = false
        // 实时上屏预览：选区变化即回填服务层（背景输入框 composing 预编辑）
        val text = selectedText()
        if (text != lastPreview) {
            lastPreview = text
            onSelectionChanged?.invoke(text)
        }
    }

    private fun setChipSelected(i: Int, selected: Boolean) {
        val view = chips[i].view
        val bg = view.background as? GradientDrawable ?: return
        if (selected) {
            bg.setColor(theme.genericActiveBackgroundColor)
            view.setTextColor(theme.genericActiveForegroundColor)
        } else {
            bg.setColor(theme.clipboardEntryColor)
            view.setTextColor(theme.keyTextColor)
        }
    }

    // ---- 视图构建 ----

    private val chipArea = ChipArea(ctx).apply {
        flexWrap = FlexWrap.WRAP
        justifyContent = JustifyContent.FLEX_START
        setPaddingDp(8, 10, 8, 16)
    }

    private fun chipDisplayText(raw: String): String =
        WordSelectMaskPolicy.chipDisplayText(raw, maskSensitive)

    val truncationHint = TextView(ctx).apply {
        textSize = 11f
        setTextColor(theme.altKeyTextColor)
        setPaddingDp(14, 2, 14, 8)
        visibility = View.GONE
    }

    private val scrollView = NoInterceptScrollView(ctx).apply {
        isVerticalScrollBarEnabled = true
        addView(
            chipArea,
            ViewGroup.LayoutParams(matchParent, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }

    val selectAllBox = CheckBox(ctx).apply {
        text = ctx.getString(R.string.select_all)
        textSize = 13f
        setTextColor(theme.keyTextColor)
        buttonTintList = ColorStateList.valueOf(theme.genericActiveBackgroundColor)
        setPaddingDp(4, 0, 4, 0)
        setOnCheckedChangeListener { _, checked ->
            if (syncingSelectAll || chips.isEmpty()) return@setOnCheckedChangeListener
            if (checked) {
                selectRange(0, chips.size - 1)
            } else {
                clearSelection()
            }
        }
    }

    val copyButton = TextView(ctx).apply {
        text = ctx.getString(R.string.copy)
        textSize = 14f
        setTextColor(theme.keyTextColor)
        setPaddingDp(18, 8, 18, 8)
        // 点击行为由 WordSelectWindow 绑定（需要 service/windowManager）
    }

    val doneButton = TextView(ctx).apply {
        text = ctx.getString(R.string.done)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(theme.accentKeyTextColor)
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(20).toFloat()
            setColor(theme.accentKeyBackgroundColor)
        }
        setPaddingDp(22, 8, 22, 8)
        // 点击行为由 WordSelectWindow 绑定
    }

    private val bottomBar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        setPaddingDp(12, 6, 12, 12)
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = ctx.dp(24).toFloat()
                setColor(theme.popupBackgroundColor)
            }
            setPaddingDp(14, 4, 10, 4)
            addView(selectAllBox, lParams(wrapContent, wrapContent))
            addView(copyButton, lParams(wrapContent, wrapContent))
            addView(doneButton, lParams(wrapContent, wrapContent) {
                leftMargin = ctx.dp(6)
            })
        }
        addView(pill, lParams(wrapContent, wrapContent))
    }

    override val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        // 与 ClipboardUi 同规则：无边框样式时用 barColor 融入面板
        setBackgroundColor(
            if (!org.fcitx.fcitx5.android.data.theme.ThemeManager.prefs.keyBorder.getValue()) {
                theme.barColor
            } else {
                theme.backgroundColor
            }
        )
        addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(truncationHint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(bottomBar, LinearLayout.LayoutParams(matchParent, wrapContent))
    }

    /** 用分词结果填充词块（可重复调用，先清空） */
    fun setContent(r: WordTokenizer.Result) {
        result = r
        chipArea.removeAllViews()
        chips.clear()
        clearSelection()
        r.tokens.forEach { token ->
            val raw = r.text.substring(token.start, token.end)
            val tv = TextView(ctx).apply {
                text = chipDisplayText(raw)
                textSize = 15f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = ctx.dp(260)
                includeFontPadding = false
                setPaddingDp(12, 7, 12, 7)
                setTextColor(theme.keyTextColor)
                background = GradientDrawable().apply {
                    cornerRadius = ctx.dp(6).toFloat()
                    setColor(theme.clipboardEntryColor)
                }
                // 词块不消费点击/长按：滑动选择由 chipArea 统一处理
                isClickable = false
                isLongClickable = false
            }
            chipArea.addView(
                tv,
                FlexboxLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(ctx.dp(3), ctx.dp(4), ctx.dp(3), ctx.dp(4))
                }
            )
            chips.add(Chip(tv, token))
        }
        truncationHint.visibility = if (r.truncated) View.VISIBLE else View.GONE
        if (r.truncated) {
            truncationHint.text = ctx.getString(
                R.string.word_select_truncated, chips.size
            )
        }
    }

    fun clearSelection() {
        selStart = -1
        selEnd = -1
        anchorIndex = -1
        currentIndex = -1
        for (i in chips.indices) setChipSelected(i, false)
        syncingSelectAll = true
        selectAllBox.isChecked = false
        syncingSelectAll = false
        if (lastPreview != null) {
            lastPreview = null
            onSelectionChanged?.invoke(null)
        }
    }

    val hasSelection: Boolean get() = selStart in 0..selEnd && selEnd < chips.size

    /** 当前选中的原文（保留词块之间的原始空白/换行）；未选中返回 null */
    fun selectedText(): String? {
        if (!hasSelection) return null
        val first = chips[selStart].token
        val last = chips[selEnd].token
        return result.text.substring(first.start, last.end).ifBlank { null }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
    }

    /** 永不拦截子视图触摸的 ScrollView：把触摸裁决权完全交给词块区 */
    private class NoInterceptScrollView(context: Context) : ScrollView(context) {
        override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = false
    }
}
