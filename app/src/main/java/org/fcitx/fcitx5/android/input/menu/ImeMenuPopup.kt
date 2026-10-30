/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.menu

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.styledColorOrDefault
import splitties.dimensions.dp
import timber.log.Timber

/**
 * IME 窗口内安全的菜单弹窗（MBM）。
 *
 * 病根（pitfalls #55 / #60）：系统 `PopupMenu` 内部是可**抢焦点**的
 * ListPopupWindow，`service.showDialog()` 的 AlertDialog 同样是可抢焦点的独立窗口。
 * 在华为等 OEM ROM 上，IME 窗口内出现抢焦点的独立窗口会被系统判定「输入法已失焦」
 * 而**强制收起输入法**，且弹窗消失后焦点虽回到 Editor，IME 却不恢复——用户看到的
 * 就是「点一下菜单/确认键，键盘自动收起」。
 *
 * 本组件用 `isFocusable = false` 的 PopupWindow 呈现菜单：不产生新的焦点窗口，
 * 输入法不会被系统收起；点菜单项、点弹窗外部均可关闭。
 */
class ImeMenuPopup(private val ctx: Context, private val theme: Theme) {

    data class Item(
        val label: String,
        @DrawableRes val icon: Int? = null,
        /** 禁用项（分组分隔/不可用动作）：灰色不可点击，仅作视觉分隔 */
        val enabled: Boolean = true,
        val action: Runnable
    ) {
        constructor(label: String, icon: Int?, action: Runnable) :
            this(label, icon, true, action)
    }

    private var window: PopupWindow? = null

    /**
     * 在 [anchor] 附近显示菜单。锚点下方空间不足时自动向上翻，避免底部条目的菜单
     * 被屏幕下缘裁切（IME 面板本身只占屏幕下半，PopupWindow 不会自行翻转）。
     *
     * @param title 可选标题（加粗强调色，不可点击）
     * @return 是否显示成功；失败时调用方应走降级路径（如 AlertDialog 或无操作）
     */
    fun show(anchor: View, title: String?, items: List<Item>): Boolean {
        dismiss()
        if (items.isEmpty()) return false
        val content = buildContent(title, items)
        val popup = buildPopup(content)
        // 预测量内容高度，用于判断锚点下方空间是否足够
        content.measure(
            View.MeasureSpec.makeMeasureSpec(
                ctx.resources.displayMetrics.widthPixels, View.MeasureSpec.AT_MOST
            ),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val location = IntArray(2)
        anchor.getLocationInWindow(location)
        val windowHeight = anchor.rootView.height.takeIf { it > 0 }
            ?: ctx.resources.displayMetrics.heightPixels
        val spaceBelow = windowHeight - (location[1] + anchor.height)
        return try {
            if (spaceBelow < content.measuredHeight && location[1] >= content.measuredHeight) {
                // 下方放不下且上方放得下：向上翻，贴在锚点上方
                popup.showAtLocation(
                    anchor,
                    Gravity.TOP or Gravity.START,
                    location[0],
                    location[1] - content.measuredHeight
                )
            } else {
                popup.showAsDropDown(anchor)
            }
            window = popup
            true
        } catch (e: Throwable) {
            // IME 窗口内弹窗在极端 OEM 上可能因窗口令牌非法而失败，交由调用方降级
            Timber.w(e, "IME 安全菜单无法显示")
            window = null
            false
        }
    }

    /**
     * 贴 [anchor] 所在窗口的底部左侧显示（用于调用方拿不到具体按键锚点的场景，
     * 如「仅一个输入法时提示添加」——病根与修复手法同 #60）。
     */
    fun showBottom(anchor: View, title: String?, items: List<Item>): Boolean {
        dismiss()
        if (items.isEmpty()) return false
        val popup = buildPopup(buildContent(title, items))
        return try {
            popup.showAtLocation(
                anchor,
                Gravity.BOTTOM or Gravity.START,
                ctx.dp(8),
                ctx.dp(8)
            )
            window = popup
            true
        } catch (e: Throwable) {
            Timber.w(e, "IME 安全菜单无法显示")
            window = null
            false
        }
    }

    private fun buildPopup(content: View): PopupWindow {
        val background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = ctx.dp(12).toFloat()
            setColor(theme.popupBackgroundColor)
        }
        return PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setBackgroundDrawable(background)
            // 关键：不抢焦点。抢焦点的弹窗会让系统判定 IME 失焦并强制收起输入法
            isFocusable = false
            // 点菜单外区域自动消失（需要非 null background 才生效）
            isOutsideTouchable = true
            animationStyle = 0
            elevation = ctx.dp(4).toFloat()
        }
    }

    private fun buildContent(title: String?, items: List<Item>): View {
        // 菜单最宽不超过屏宽减 32dp。注意：宽度上限要设在 TextView 子项上——
        // LinearLayout 没有 maxWidth 属性（历史实现在 BaseInputView 内曾意外解析到
        // 外层 ConstraintLayout 的 maxWidth 上，属于隐式接收者穿透的隐患写法）。
        val maxMenuWidth = ctx.resources.displayMetrics.widthPixels - ctx.dp(32)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(8), ctx.dp(4), ctx.dp(8), ctx.dp(4))
            if (title != null) {
                addView(TextView(ctx).apply {
                    maxWidth = maxMenuWidth
                    text = buildSpannedString {
                        bold {
                            color(
                                ctx.styledColorOrDefault(
                                    android.R.attr.colorAccent,
                                    theme.genericActiveForegroundColor
                                )
                            ) { append(title) }
                        }
                    }
                    textSize = 15f
                    setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(6))
                })
            }
            val ripple = TypedValue().also {
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            }.resourceId
            val iconTint = ctx.styledColorOrDefault(
                android.R.attr.colorControlNormal, theme.altKeyTextColor
            )
            items.forEach { item ->
                addView(TextView(ctx).apply {
                    maxWidth = maxMenuWidth
                    text = item.label
                    textSize = 15f
                    setPadding(ctx.dp(12), ctx.dp(10), ctx.dp(12), ctx.dp(10))
                    if (ripple != 0) setBackgroundResource(ripple)
                    item.icon?.let { res ->
                        val drawable =
                            ContextCompat.getDrawable(ctx, res)?.mutate()
                                ?.apply { setTint(iconTint) }
                        setCompoundDrawablesRelativeWithIntrinsicBounds(drawable, null, null, null)
                        compoundDrawablePadding = ctx.dp(12)
                    }
                    if (!item.enabled) {
                        // 禁用项：压暗文字、去掉水波纹、不响应点击
                        setTextColor(iconTint.alpha(0.4f))
                        background = null
                    } else {
                        setOnClickListener {
                            dismiss()
                            item.action.run()
                        }
                    }
                })
            }
        }
    }

    fun dismiss() {
        window?.dismiss()
        window = null
    }
}
