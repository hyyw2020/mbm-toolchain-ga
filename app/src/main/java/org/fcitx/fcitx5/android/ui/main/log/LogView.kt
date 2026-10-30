/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.log

import android.content.Context
import android.util.AttributeSet
import android.widget.HorizontalScrollView
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.Logcat
import org.fcitx.fcitx5.android.utils.imeFallbackScope
import splitties.resources.styledColor
import splitties.views.bottomPadding
import splitties.views.dsl.core.add
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.verticalLayoutManager

class LogView @JvmOverloads constructor(context: Context, attributeSet: AttributeSet? = null) :
    HorizontalScrollView(context, attributeSet) {

    private var logcat: Logcat? = null

    private val logAdapter = LogAdapter()

    private val rv = recyclerView {
        adapter = logAdapter
        layoutManager = verticalLayoutManager()
    }

    init {
        add(rv, lParams(wrapContent, matchParent))
    }

    override fun onDetachedFromWindow() {
        logcat?.shutdownLogFlow()
        super.onDetachedFromWindow()
    }

    fun append(content: String) {
        logAdapter.append(buildSpannedString {
            color(styledColor(android.R.attr.colorForeground)) { append(content) }
        })
    }

    fun setLogcat(logcat: Logcat) {
        this.logcat = logcat
        logcat.initLogFlow()
        logcat.logFlow.onEach {
            // 修复（docs/pitfalls.md #7）：logcat 输出可能含空行，
            // 旧实现 it.first() 对空字符串抛 NoSuchElementException 会杀死整个收集协程，
            // 导致之后日志不再显示、导出文件只剩头信息。空行直接跳过。
            if (it.isBlank()) return@onEach
            val color = styledColor(
                when (it.first()) {
                    'V' -> R.attr.colorLogVerbose
                    'D' -> R.attr.colorLogDebug
                    'I' -> R.attr.colorLogInfo
                    'W' -> R.attr.colorLogWarning
                    'E' -> R.attr.colorLogError
                    'F' -> R.attr.colorLogFatal
                    else -> android.R.attr.colorForeground
                }
            )
            logAdapter.append(buildSpannedString {
                color(color) { append(it) }
            })
        }.launchIn(findViewTreeLifecycleOwner()?.lifecycleScope ?: imeFallbackScope)
    }

    val currentLog: String
        get() = logAdapter.fullLogString()

    fun clear() {
        logAdapter.clear()
    }

    fun setBottomPadding(padding: Int) {
        rv.clipToPadding = false
        rv.bottomPadding = padding
    }
}