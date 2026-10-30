/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.status

import android.os.Build
import android.view.View
import android.widget.Toast
import androidx.core.text.buildSpannedString
import timber.log.Timber
import androidx.core.text.color
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Action
import org.fcitx.fcitx5.android.core.SubtypeManager
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.bar.ui.ToolButton
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcastReceiver
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.editorinfo.EditorInfoWindow
import org.fcitx.fcitx5.android.input.menu.ImeMenuPopup
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.InputMethod
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.Keyboard
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.ReloadConfig
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.SelfTest
import org.fcitx.fcitx5.android.input.status.StatusAreaEntry.Android.Type.ThemeList
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.alpha
import org.fcitx.fcitx5.android.utils.MbmDebug
import org.mechdancer.dependency.manager.must
import splitties.dimensions.dp
import splitties.resources.styledColor
import splitties.views.backgroundColor
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalLayout
import splitties.views.dsl.core.lParams
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.gridLayoutManager

class StatusAreaWindow : InputWindow.ExtendedInputWindow<StatusAreaWindow>(),
    InputBroadcastReceiver {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val fcitx: FcitxConnection by manager.fcitx()
    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()

    private val editorInfoInspector by AppPrefs.getInstance().internal.editorInfoInspector

    private val staticEntries by lazy {
        arrayOf(
            StatusAreaEntry.Android(
                context.getString(R.string.theme),
                R.drawable.ic_baseline_palette_24,
                ThemeList
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.input_method_options),
                R.drawable.ic_baseline_language_24,
                InputMethod
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.reload_config),
                R.drawable.ic_baseline_sync_24,
                ReloadConfig
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.virtual_keyboard),
                R.drawable.ic_baseline_keyboard_24,
                Keyboard
            ),
            StatusAreaEntry.Android(
                context.getString(R.string.mbm_self_test),
                R.drawable.ic_baseline_check_circle_24,
                SelfTest
            )
        )
    }

    private fun activateAction(action: Action) {
        fcitx.launchOnReady {
            it.activateAction(action.id)
        }
    }

    /**
     * MBM v0.0.10：状态区子菜单原用系统 PopupMenu（内部是可抢焦点的 ListPopupWindow）。
     * 与 pitfalls #55 / #60 同源——IME 窗口内弹抢焦点独立窗口会被 OEM ROM 判定
     * 「输入法已失焦」而强制收起。改用 [ImeMenuPopup]（isFocusable=false）。
     * 分组分隔在旧实现里对无原生 divider 的平台就是注入禁用文本项，此处统一该行为。
     */
    private var actionMenu: ImeMenuPopup? = null

    private val adapter: StatusAreaAdapter by lazy {
        object : StatusAreaAdapter() {
            override fun onItemClick(view: View, entry: StatusAreaEntry) {
                MbmDebug.markFeature(view, "status.action")
                MbmDebug.logFeature("status.action", entry::class.simpleName.orEmpty())
                // v3.5：SelfTest 条目给独立标记，自测可定位点击（防递归守卫已加）
                if (entry is StatusAreaEntry.Android && entry.type == StatusAreaEntry.Android.Type.SelfTest) {
                    MbmDebug.markFeature(view, "status.selftest")
                }
                when (entry) {
                    is StatusAreaEntry.Fcitx -> {
                        val actions = entry.action.menu
                        if (actions.isNullOrEmpty()) {
                            activateAction(entry.action)
                            return
                        }
                        // 分组分隔统一为禁用文本项（旧实现无原生 divider 平台的降级样式）
                        val separatorLabel = buildSpannedString {
                            color(context.styledColor(android.R.attr.colorForeground).alpha(0.4f)) {
                                append("──────────")
                            }
                        }.toString()
                        val items = actions.map {
                            if (it.isSeparator) {
                                ImeMenuPopup.Item(separatorLabel, null, false) {}
                            } else {
                                ImeMenuPopup.Item(it.shortText, null) { activateAction(it) }
                            }
                        }
                        actionMenu?.dismiss()
                        val popup = ImeMenuPopup(context, theme)
                        actionMenu = if (popup.show(view, null, items)) popup else null
                        if (actionMenu == null) {
                            // 极端 OEM 上弹窗失败（窗口令牌非法）：仅记录，不再回退抢焦点弹窗
                            Timber.w("状态区子菜单在 IME 窗口内无法显示")
                        }
                    }
                    is StatusAreaEntry.Android -> when (entry.type) {
                        InputMethod -> fcitx.runImmediately { inputMethodEntryCached }.let {
                            AppUtil.launchMainToInputMethodConfig(
                                context, it.uniqueName, it.displayName
                            )
                        }
                        ReloadConfig -> fcitx.launchOnReady { f ->
                            f.reloadConfig()
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                SubtypeManager.syncWith(f.enabledIme())
                            }
                            service.lifecycleScope.launch {
                                Toast.makeText(service, R.string.done, Toast.LENGTH_SHORT).show()
                            }
                        }
                        Keyboard -> AppUtil.launchMainToKeyboard(context)
                        ThemeList -> AppUtil.launchMainToThemeList(context)
                        SelfTest -> {
                            MbmDebug.logFeature("status.selftest")
                            if (service.mbmSelfTestRunning) {
                                // v3.5：防递归——自测运行中再次点击入口只打日志，不重入
                            } else {
                                service.runSelfTest { report ->
                                    val path = MbmDebug.lastExportPath
                                    val where = if (path != null) "报告已导出到 $path"
                                    else "报告在 files/mbm_selftest.txt"
                                    Toast.makeText(
                                        service,
                                        "自动测试完成：" + report.summary + "；" + where +
                                            "（全文同时在剪贴板，可直接粘贴）",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                }
            }

            override val theme = this@StatusAreaWindow.theme
        }
    }

    private val keyBorder by ThemeManager.prefs.keyBorder

    val view by lazy {
        context.recyclerView {
            if (!keyBorder) {
                backgroundColor = theme.barColor
            }
            layoutManager = gridLayoutManager(4)
            adapter = this@StatusAreaWindow.adapter
        }
    }

    override fun onStatusAreaUpdate(actions: Array<Action>) {
        adapter.entries = arrayOf(
            *staticEntries,
            *Array(actions.size) { StatusAreaEntry.fromAction(actions[it]) }
        )
    }

    override fun onCreateView() = view

    private val editorInfoButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_info_24, theme).apply {
            contentDescription = context.getString(R.string.editor_info_inspector)
            MbmDebug.markFeature(this, "status.editorinfo")
            setOnClickListener {
                MbmDebug.logFeature("status.editorinfo")
                windowManager.attachWindow(EditorInfoWindow())
            }
        }
    }

    private val settingsButton by lazy {
        ToolButton(context, R.drawable.ic_baseline_settings_24, theme).apply {
            contentDescription = context.getString(R.string.open_input_method_settings)
            MbmDebug.markFeature(this, "status.settings")
            setOnClickListener {
                MbmDebug.logFeature("status.settings")
                AppUtil.launchMain(context)
            }
        }
    }

    private val barExtension by lazy {
        context.horizontalLayout {
            if (editorInfoInspector) {
                add(editorInfoButton, lParams(dp(40), dp(40)))
            }
            add(settingsButton, lParams(dp(40), dp(40)))
        }
    }

    override fun onCreateBarExtension() = barExtension

    override fun onAttached() {
        fcitx.launchOnReady {
            val data = it.statusArea()
            service.lifecycleScope.launch {
                onStatusAreaUpdate(data)
            }
        }
    }

    override fun onDetached() {
        actionMenu?.dismiss()
        actionMenu = null
    }
}
