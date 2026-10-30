/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.wm

import android.view.View
import android.widget.FrameLayout
import androidx.transition.Transition
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.dependency.UniqueViewComponent
import org.fcitx.fcitx5.android.input.dependency.context
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.must
import org.mechdancer.dependency.minusAssign
import org.mechdancer.dependency.plusAssign
import splitties.views.dsl.core.add
import splitties.views.dsl.core.frameLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import timber.log.Timber

class InputWindowManager : UniqueViewComponent<InputWindowManager, FrameLayout>(),
    InputBroadcastReceiver {

    private val context by manager.context()
    private val broadcaster: InputBroadcaster by manager.must()
    private lateinit var scope: DynamicScope

    private val essentialWindows = mutableMapOf<EssentialWindow.Key, Pair<InputWindow, View?>>()

    private var currentWindow: InputWindow? = null

    /**
     * MBM v0.0.07：当前激活窗口是否为手写面板。
     * 用类名判断避免 wm 包反向依赖 handwriting 包。
     */
    val isHandwritingActive: Boolean
        get() = currentWindow?.let { it::class.java.simpleName == "HandwritingWindow" } == true

    /**
     * MBM v0.0.07：窗口切换回调。InputView 据此在「键盘 <-> 手写」切换时
     * 重算输入面板高度（手写需要比键盘大得多的书写区域）。
     */
    var onWindowSwitched: ((InputWindow) -> Unit)? = null

    /**
     * MBM v1.0.36：当前附着窗口（内置自测 v3.2 用：收集该窗口的 bar extension，
     * 扩展视图经 KawaiiBarComponent.onWindowAttached 挂到 kawaiiBar 的 titleUi，
     * 不在窗口子树内）。
     */
    val current: InputWindow?
        get() = currentWindow

    private var currentView: View? = null

    private val disableAnimation by AppPrefs.getInstance().advanced.disableAnimation

    private fun prepareAnimation(
        exitAnimation: Transition?,
        enterAnimation: Transition?,
        remove: View,
        add: View
    ) {
        if (disableAnimation)
            return
        enterAnimation?.addTarget(add)
        exitAnimation?.addTarget(remove)
        TransitionManager.beginDelayedTransition(view, TransitionSet().apply {
            enterAnimation?.let { addTransition(it) }
            exitAnimation?.let { addTransition(it) }
            duration = 100
        })
    }

    /**
     * Associate essential window with its key and add it to scope
     * If [createView] is `true`, the view will be created immediately.
     * Otherwise, it will be created on first attach
     */
    fun <R> addEssentialWindow(
        window: R,
        createView: Boolean = false
    ) where R : InputWindow, R : EssentialWindow {
        if (window.key in essentialWindows) {
            if (essentialWindows[window.key]!!.first === window)
                Timber.d("Skip adding essential window $window")
            else
                throw IllegalStateException("${window.key} is already occupied")
        }
        scope += window
        val view = if (createView) window.onCreateView() else null
        essentialWindows[window.key] = window to view
    }

    fun getEssentialWindow(windowKey: EssentialWindow.Key) =
        essentialWindows[windowKey]?.first
            ?: throw IllegalArgumentException("Unable to find essential window associated with $windowKey")

    /**
     * Attach an essential window by key
     * IMPORTANT: the window key must be known,
     * i.e. the essential window should be added first via [addEssentialWindow].
     * Moreover, [attachWindow] can also add the essential window with key.
     */
    fun attachWindow(windowKey: EssentialWindow.Key) {
        essentialWindows[windowKey]?.let { (window, _) ->
            attachWindow(window)
        } ?: throw IllegalStateException("$windowKey is not a known essential window key")
    }

    /**
     * Remove an essential window
     */
    fun removeEssentialWindow(windowKey: EssentialWindow.Key) {
        val (window, _) = essentialWindows[windowKey]
            ?: throw IllegalStateException("$windowKey is not a known essential window key")
        if (currentWindow === window)
            throw IllegalStateException("$windowKey cannot be removed when it's active")
        // remove from scope
        scope -= window
        // remove from map
        essentialWindows.remove(windowKey)
    }

    /**
     * Attach a new window, removing the old one
     * This function initialize the view for windows and save it for essential windows.
     * [attachWindow] includes the operation done by [addEssentialWindow].
     */
    fun attachWindow(window: InputWindow) {
        if (window === currentWindow) {
            // N71：已是当前窗口则直接返回，避免重复 detach/attach 导致面板闪烁、长按大写状态被重置
            Timber.d("Skip attaching $window")
            return
        }
        val newView = if (window is EssentialWindow) {
            // keep the view for essential windows
            essentialWindows[window.key]?.second ?: window.onCreateView()
                .also { essentialWindows[window.key] = window to it }
        } else {
            // add the new window to scope, except essential windows (they are always in scope)
            scope += window
            window.onCreateView()
        }
        if (currentWindow != null) {
            val oldWindow = currentWindow!!
            val oldView = currentView!!
            prepareAnimation(
                oldWindow.exitAnimation(window),
                window.enterAnimation(oldWindow),
                oldView,
                newView
            )
            // notify the window that it will be detached
            oldWindow.onDetached()
            // remove the old window from layout
            view.removeView(oldView)
            // broadcast the old window was removed from layout
            broadcaster.onWindowDetached(oldWindow)
            Timber.d("Detach $oldWindow")
            // finally remove the old window from scope only if it's not an essential window,
            if (oldWindow !is EssentialWindow)
                scope -= oldWindow
        }
        // call before attached for essential window
        if (window is EssentialWindow)
            window.beforeAttached()
        // add the new window to layout
        view.apply { add(newView, lParams(matchParent, matchParent)) }
        currentView = newView
        Timber.d("Attach $window")
        // notify the window it was attached
        window.onAttached()
        currentWindow = window
        // MBM v0.0.07：通知 InputView 重算面板高度（手写模式自动加高）
        onWindowSwitched?.invoke(window)
        // broadcast the new window was added to layout
        broadcaster.onWindowAttached(window)
    }

    override val view: FrameLayout by lazy { context.frameLayout(R.id.input_window) }

    override fun onScopeSetupFinished(scope: DynamicScope) {
        this.scope = scope
    }

    fun isAttached(window: InputWindow) = currentWindow === window

    /** MBM v1.0.36：adb 调试——当前窗口状态摘要（供 MbmDebug.stateDump）。 */
    fun debugDump(): String {
        val name = currentWindow?.let { it::class.java.simpleName } ?: "none"
        return "window=$name handwriting=${isHandwritingActive}"
    }
}