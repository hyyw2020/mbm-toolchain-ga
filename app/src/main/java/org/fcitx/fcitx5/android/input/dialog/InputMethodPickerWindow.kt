/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dialog

import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import org.fcitx.fcitx5.android.input.dependency.fcitx
import org.fcitx.fcitx5.android.input.dependency.inputMethodService
import org.fcitx.fcitx5.android.input.dependency.theme
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.utils.imeExceptionHandler
import org.mechdancer.dependency.manager.must
import splitties.resources.styledDrawable
import splitties.views.backgroundColor
import splitties.views.dsl.recyclerview.recyclerView
import splitties.views.recyclerview.verticalLayoutManager

/**
 * 输入法选择面板（长按中英文切换键 / 长按空格键唤起）。
 *
 * MBM v0.0.09：原实现是 [InputMethodPickerDialog]——一个由 service.showDialog 托管的
 * AlertDialog，其窗口是**可抢焦点**的独立窗口。在华为等 OEM ROM 上，IME 窗口内出现
 * 抢焦点的独立窗口会被系统判定「输入法已失焦」而强制收起输入法，用户报回
 * 「长按中英文切换键，输入法自动收起」（pitfalls #60，与 #55 同源）。
 *
 * 现改为面板内窗口（[InputWindow.ExtendedInputWindow]）：不产生任何新窗口，
 * 系统无从判定失焦；标题栏自带返回按钮，点按即回键盘；切换行为与原对话框一致
 * （fcitx 子输入法走 activateIme，其它输入法 App 走 switchInputMethod）。
 */
class InputMethodPickerWindow : InputWindow.ExtendedInputWindow<InputMethodPickerWindow>() {

    private val service: FcitxInputMethodService by manager.inputMethodService()
    private val fcitx: FcitxConnection by manager.fcitx()
    private val theme by manager.theme()
    private val windowManager: InputWindowManager by manager.must()

    private val keyBorder by ThemeManager.prefs.keyBorder

    private lateinit var recyclerView: RecyclerView

    private var listAdapter: InputMethodListAdapter? = null

    // N109：onAttached 起的加载链（等 fcitx ready → IO 解析 → 装 adapter）原先没有任何
    // 引用，onDetached 只把 adapter 置空，上一轮的协程仍会跑完并把陈旧列表写进
    // 新 attach 的面板。这里持有 Job，detach 时立即取消。
    private var loadJob: Job? = null

    override fun onCreateView(): View {
        recyclerView = context.recyclerView {
            if (!keyBorder) {
                backgroundColor = theme.barColor
            }
            layoutManager = verticalLayoutManager()
        }
        return recyclerView
    }

    override fun onAttached() {
        // N109：重新 attach 前先掐掉上一轮还没跑完的加载，避免陈旧结果回写
        loadJob?.cancel()
        loadJob = fcitx.lifecycleScope.launch(imeExceptionHandler) {
            fcitx.runOnReady {
                val api = this@runOnReady
                // MBM v0.0.10：resolve 内部走 IMMS enabledInputMethodList +
                // PackageManager.loadLabel（IPC 级查询），早期直接跑在 Main 线程，
                // 长按切换键后面板打开的瞬间会阻塞主线程掉帧。挪 IO 线程完成。
                val entries = withContext(Dispatchers.IO) {
                    InputMethodData.resolve(api, context)
                }
                val enabledIndex =
                    entries.indexOfFirst { it.uniqueName == api.inputMethodEntryCached.uniqueName }
                listAdapter = InputMethodListAdapter(entries, enabledIndex) { data ->
                    // N110：切换后立刻把勾选态移到被点击的那一行，
                    // 否则 setEnabled 没人调用，勾选框一直停在原来的位置
                    listAdapter?.setEnabled(entries.indexOf(data))
                    if (data.ime) {
                        // 切换到其它输入法 App：本输入法随之隐藏，属预期行为
                        service.switchInputMethod(data.uniqueName)
                    } else {
                        service.lifecycleScope.launch(imeExceptionHandler) {
                            api.activateIme(data.uniqueName)
                        }
                        windowManager.attachWindow(KeyboardWindow)
                    }
                }
                recyclerView.adapter = listAdapter
                // fcitx 子输入法与其它输入法 App 之间的分隔线
                val dividerIndex = entries.indexOfFirst { it.ime }
                context.styledDrawable(android.R.attr.dividerHorizontal)?.let {
                    recyclerView.addItemDecoration(SingleDividerDecoration(it, dividerIndex))
                }
            }
        }
    }

    override fun onDetached() {
        // N109：取消仍在跑的加载协程，防止它把陈旧列表写进下一次 attach 的面板
        loadJob?.cancel()
        loadJob = null
        listAdapter = null
        recyclerView.adapter = null
    }

    override val title: String by lazy {
        context.getString(R.string.choose_input_method)
    }
}
