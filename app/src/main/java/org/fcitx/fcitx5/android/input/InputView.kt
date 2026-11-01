/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.RecyclerView
import org.fcitx.fcitx5.android.core.CapabilityFlags
import org.fcitx.fcitx5.android.core.FcitxEvent
import org.fcitx.fcitx5.android.daemon.FcitxConnection
import org.fcitx.fcitx5.android.daemon.launchOnReady
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.bar.KawaiiBarComponent
import org.fcitx.fcitx5.android.input.calc.CalcCandidateComponent
import org.fcitx.fcitx5.android.input.broadcast.InputBroadcaster
import org.fcitx.fcitx5.android.input.broadcast.PreeditEmptyStateComponent
import org.fcitx.fcitx5.android.input.broadcast.PunctuationComponent
import org.fcitx.fcitx5.android.input.broadcast.ReturnKeyDrawableComponent
import org.fcitx.fcitx5.android.input.candidates.horizontal.HorizontalCandidateComponent
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase.DisplayMetrics
import org.fcitx.fcitx5.android.input.keyboard.KeyboardHeightPercentBase.RealSize
import org.fcitx.fcitx5.android.input.keyboard.KeyDef
import org.fcitx.fcitx5.android.input.keyboard.KeyboardWindow
import org.fcitx.fcitx5.android.input.keyboard.NumberKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.KeyView
import org.fcitx.fcitx5.android.input.popup.EmojiModifier
import org.fcitx.fcitx5.android.input.keyboard.TextKeyView
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.picker.emojiPicker
import org.fcitx.fcitx5.android.input.picker.emoticonPicker
import org.fcitx.fcitx5.android.input.picker.symbolPicker
import org.fcitx.fcitx5.android.input.popup.PopupComponent
import org.fcitx.fcitx5.android.input.preedit.PreeditComponent
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.input.wm.InputWindowManager
import org.fcitx.fcitx5.android.ui.main.HandwritingView
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.RuleImportActivity
import org.fcitx.fcitx5.android.ui.main.HandwritingActivity
import org.fcitx.fcitx5.android.data.password.PasswordGeneratorActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.utils.MbmDebug
import org.fcitx.fcitx5.android.utils.isTypeNull
import org.fcitx.fcitx5.android.utils.collectFeatureViews
import org.fcitx.fcitx5.android.utils.unset
import org.fcitx.fcitx5.android.utils.windowManager
import org.mechdancer.dependency.DynamicScope
import org.mechdancer.dependency.manager.wrapToUniqueComponent
import org.mechdancer.dependency.plusAssign
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import timber.log.Timber

@SuppressLint("ViewConstructor")
class InputView(
    service: FcitxInputMethodService,
    fcitx: FcitxConnection,
    theme: Theme
) : BaseInputView(service, fcitx, theme) {

    private val keyBorder by ThemeManager.prefs.keyBorder

    private val customBackground = imageView {
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private val placeholderOnClickListener = OnClickListener { }

    // use clickable view as padding, so MotionEvent can be split to padding view and keyboard view
    private val leftPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val rightPaddingSpace = view(::View) {
        setOnClickListener(placeholderOnClickListener)
    }
    private val bottomPaddingSpace = view(::View) {
        // height as keyboardBottomPadding
        // bottomMargin as WindowInsets (Navigation Bar) offset
        setOnClickListener(placeholderOnClickListener)
    }

    private val scope = DynamicScope()
    private val broadcaster = InputBroadcaster()
    private val popup = PopupComponent()
    private val punctuation = PunctuationComponent()
    private val returnKeyDrawable = ReturnKeyDrawableComponent()
    private val preeditEmptyState = PreeditEmptyStateComponent()
    private val preedit = PreeditComponent()
    private val commonKeyActionListener = CommonKeyActionListener()
    private val windowManager = InputWindowManager()
    private val kawaiiBar = KawaiiBarComponent()

    /** MBM v1.0.36：adb 调试——当前输入窗口状态（供 MbmDebug.stateDump）。 */
    fun debugWindowDump(): String = windowManager.debugDump()

    /**
     * MBM v1.0.36：输入法内置全功能自动测试（v3.2：PASS/FAIL 二元判定 + 源码级失败原因 + 窗口相位「子树+bar extension」合并收集）。
     * 相位：键盘（含候选前置拼音输入）→候选（经 service 兄弟窗，含候选展开/收起）→状态区→手写（合成笔画）→编辑→剪贴板（预置条目+滑动/长按）→
     * 符号/表情/颜文字→危险键后置（quickphrase/语言/符号/布局 + bar.menu/bar.hide/status.settings）→daemon 行为层（v3.3）。
     * v3.1：功能标记 tag 双轨（防 contentDescription 被业务覆写）；开窗前 ensureToolbar；候选前拼音输入。
     * v3.2：扩展视图（status.settings/editorinfo、picker 分类 Tab、编辑区剪贴板按钮）经 KawaiiBarComponent.onWindowAttached
     * 挂到 kawaiiBar 兄弟节点的 titleUi，窗口相位改用 collectWindowWithBarExtension() 合并收集；
     * 候选前置多音节 ni→hao→shi 轮试 + fcitx.currentIme() 引擎诊断；status.editorinfo 自测临时置位
     * editorInfoInspector 开关测完恢复；候选相位补测 bar.candidate_expand 展开/收起。
     * v3.2.1：编排隔离——bar.candidate_expand 判定权归 candidates 相位（键盘相位排除其点击）；
     * key.longpress 检查前与候选相位开头兜底重附键盘窗（键盘相位 key.return 等开窗副作用会换出键盘视图，
     * 导致后续相位 collect() 误报「无空格键」）。
     * v3.3：新增 daemon 行为层——不经 UI，直接经 fcitx daemon 程序化断言（runImmediately 短调用，顺带覆盖
     * fcitx 设置页的快捷键轮换/修饰键时限 250ms/默认激活/共享输入状态/预编辑等全局配置项的回读）。
     * 每个注册功能 id 恰好一条判定：PASS（真实点击/手势触发并观测到日志）或
     * FAIL（附具体原因 + 源码定位，无 SKIP 态）。必须主线程执行；要求键盘窗口已打开。
     */
    suspend fun runMbmSelfTest(): MbmDebug.SelfTestReport {
        val t0 = SystemClock.elapsedRealtime()
        val results = mutableListOf<MbmDebug.FeatureResult>()
        val diagnostics = mutableListOf<String>()
        val tested = mutableSetOf<String>()
        fun add(r: MbmDebug.FeatureResult) {
            results.add(r)
            tested.add(r.id)
        }
        fun pass(id: String, phase: String, cause: String = "") =
            add(MbmDebug.FeatureResult.pass(id, phase, cause))
        fun fail(id: String, phase: String, cause: String, source: String = "") =
            add(MbmDebug.FeatureResult.fail(id, phase, cause, source))

        // v2 修复：从整个 IME 视图树收集（kawaiiBar + windowManager.view 为兄弟节点）。
        val root: View = this

        /** v3.1：窗口相位只收集当前附着窗口子树（attachWindow 已把旧窗口视图换出 windowManager.view），
          *  避免 kawaiiBar 兄弟节点的开窗按钮被他相位误点——v3 中 status 相位曾点 bar.cursor/clipboard/handwriting
          *  连带开三个窗口并消费开闸人，导致 editing/handwriting/clipboard 相位开窗落空。
          *  v3.2：需含扩展视图的相位改用 collectWindowWithBarExtension()（下方定义）。 */
        fun collectWindow(): List<Pair<View, String>> = windowManager.view.collectFeatureViews()

        // 「开窗按钮」在各自相位真实点击
        val openers = setOf("bar.cursor", "bar.clipboard", "bar.more", "bar.handwriting")
        // 危险键：后置（改变布局/IME 状态、跳离 IME）
        val dangerous = setOf(
            "bar.hide", "bar.menu", "status.settings",
            "key.layout", "key.sym", "key.lang", "key.quickphrase"
        )

        suspend fun collect(): List<Pair<View, String>> = root.collectFeatureViews()

        /** 合成长按手势：DOWN → 持 650ms → UP（触发 View 长按分发，比 performLongClick 更真实）。 */
        suspend fun longPressGesture(v: View) {
            val tg = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(tg, tg, MotionEvent.ACTION_DOWN, 0f, 0f, 0)
            runCatching { v.dispatchTouchEvent(down) }
            down.recycle()
            delay(650)
            val up = MotionEvent.obtain(tg, tg + 650, MotionEvent.ACTION_UP, 0f, 0f, 0)
            runCatching { v.dispatchTouchEvent(up) }
            up.recycle()
            delay(250)
        }

        /** 合成横向滑动手势（右→左，验证滑动删除类动作）。
         *  v3.6：事件必须发给条目所在的 RecyclerView——ItemTouchHelper 挂在
         *  RecyclerView（attachToRecyclerView），发给条目视图（v）的事件不经过
         *  RecyclerView 的 onInterceptTouchEvent/onTouchEvent，onSwiped 不触发
         *  （真机 clipboard.swipe_delete 恒 FAIL 根因）。坐标用条目在 RV 中的中心。
         *  v3.8：多 MOVE 步平滑滑动——ItemTouchHelper 需 MOVE 逐步更新滑动位移，
         *  且位移超 touchSlop 后 onInterceptTouchEvent 才返回 true 拦截、后续事件
         *  才进 RV.onTouchEvent（onTouchEvent 才是 onSwiped 触发路径）；单次大位移
         *  MOVE 可能被条目 child 消费、RV.onTouchEvent 不达。加诊断打 target 类型。 */
        suspend fun swipeGesture(v: View) {
            val rv = generateSequence(v.parent) { it?.parent }
                .filterIsInstance<androidx.recyclerview.widget.RecyclerView>().firstOrNull()
            val target = rv ?: v
            diagnostics += "swipeGesture: target=${target.javaClass.simpleName} 条目w=${v.width} top=${v.top} h=${v.height}"
            val tg = SystemClock.uptimeMillis()
            val w = v.width.toFloat().coerceAtLeast(1f)
            val cy = v.top + v.height.toFloat().coerceAtLeast(1f) * 0.5f
            fun ev(type: Int, x: Float, dt: Long) = MotionEvent.obtain(tg, tg + dt, type, x, cy, 0)
            val evs = ArrayList<MotionEvent>()
            evs += ev(MotionEvent.ACTION_DOWN, w * 0.92f, 0)
            val steps = 8
            for (i in 1..steps) {
                val frac = 0.92f - (0.82f * i / steps)
                evs += ev(MotionEvent.ACTION_MOVE, w * frac, 30L * i)
            }
            evs += ev(MotionEvent.ACTION_UP, w * 0.1f, 30L * (steps + 1))
            runCatching { evs.forEach { target.dispatchTouchEvent(it) } }
            evs.forEach { it.recycle() }
            delay(300)
        }

        suspend fun clickTargets(phase: String, targets: List<Pair<View, String>>) {
            diagnostics += "$phase 相位: 点击 ${targets.size} 个控件 [${targets.joinToString(",") { it.second }}]"
            for ((v, id) in targets) {
                if (!v.isEnabled) {
                    fail(id, phase, "控件被禁用 (isEnabled=false)，点击被拒绝：前置状态不满足（如无撤销历史）",
                        "InputView.kt:runMbmSelfTest/clickTargets")
                    continue
                }
                MbmDebug.clearFeatureLog()
                runCatching { v.performClick() }
                delay(120)
                if (MbmDebug.hasRecentFeature(id)) pass(id, phase, "真实点击触发日志")
                else fail(id, phase, "点击已执行但 feature=$id 日志未打出：按压回调未触发或标记与日志不一致",
                    "InputView.kt:runMbmSelfTest/clickTargets + BaseKeyboard.kt:createKeyView")
            }
        }

        suspend fun clickPhase(phase: String, exclude: Set<String> = emptySet(), scope: View = root) {
            val targets = scope.collectFeatureViews().distinctBy { it.second }
                .filter { it.second !in exclude && it.second !in tested }
            clickTargets(phase, targets)
        }

        /** v3.2：当前窗口子树 + 该窗口 bar extension 合并收集。
         *  扩展视图经 KawaiiBarComponent.onWindowAttached 挂到 kawaiiBar 兄弟节点的
         *  titleUi，不在当前窗口子树内（status.settings/editorinfo、picker 分类 Tab、
         *  编辑区剪贴板按钮均在其中）；status/handwriting/editing/picker 相位改用之。 */
        fun collectWindowWithBarExtension(): List<Pair<View, String>> {
            val base = windowManager.view.collectFeatureViews()
            val ext = (windowManager.current as? InputWindow.ExtendedInputWindow<*>)?.onCreateBarExtension()
            return if (ext != null) base + ext.collectFeatureViews() else base
        }

        suspend fun clickPhaseWindow(phase: String, exclude: Set<String> = emptySet()) {
            val targets = collectWindowWithBarExtension().distinctBy { it.second }
                .filter { it.second !in exclude && it.second !in tested }
            clickTargets(phase, targets)
        }

        suspend fun clickOne(id: String, phase: String, scope: View = root) {
            if (id in tested) return
            val v = scope.collectFeatureViews().firstOrNull { it.second == id }?.first
            if (v == null) {
                fail(id, phase, "控件不在当前视图树：无 mbm:feature=$id 节点（未渲染/隐藏/该键型不在当前布局）",
                    "InputView.kt:runMbmSelfTest/clickOne + MbmDebug.markFeature 打标点缺失")
                return
            }
            MbmDebug.clearFeatureLog()
            runCatching { v.performClick() }
            delay(300)
            if (MbmDebug.hasRecentFeature(id)) pass(id, phase, "真实点击触发日志")
            else fail(id, phase, "点击已执行但 feature=$id 日志未打出",
                "InputView.kt:runMbmSelfTest/clickOne")
        }

        suspend fun clickOpener(id: String, phase: String) {
            if (id in tested) return
            val v = collect().firstOrNull { it.second == id }?.first
            if (v == null) {
                fail(id, phase, "入口按钮未找到：mbm:feature=$id 未渲染（工具栏未展开或该功能当前不可用）",
                    "InputView.kt:runMbmSelfTest/clickOpener + KawaiiBarComponent.kt:buttonsUi 打标点缺失")
                return
            }
            MbmDebug.clearFeatureLog()
            runCatching { v.performClick() }
            delay(400)
            if (MbmDebug.hasRecentFeature(id)) pass(id, phase, "开窗入口点击触发日志")
            else fail(id, phase, "点击已执行但 feature=$id 日志未打出",
                "InputView.kt:runMbmSelfTest/clickOpener")
        }

        /** v3.1：把工具栏展开到 Toolbar 态，使开窗按钮（buttonsUi）可见。
         *  开窗按钮仅在 IdleUi 的 Toolbar 态渲染；空态/剪贴板态/数字行态/内联建议态下点击
         *  bar.menu 会直接切到 Toolbar（KawaiiBarComponent.kt:299-302）。
         *  本助手不写 pass/fail——bar.menu 的判定仍由危险键相位的 clickOne 负责。 */
        suspend fun ensureToolbar(phase: String) {
            var tries = 0
            var menu = collect().firstOrNull { it.second == "bar.menu" }?.first
            while (menu != null && tries < 2 && collect().none { it.second in openers }) {
                runCatching { menu.performClick() }
                delay(500)
                tries++
                menu = collect().firstOrNull { it.second == "bar.menu" }?.first
            }
            if (tries > 0) {
                diagnostics += "$phase 相位前置: 点击 bar.menu ${tries} 次展开工具栏（开窗按钮仅 Toolbar 态可见）"
            }
        }

        /** 状态区条目：直接点 RecyclerView 子视图（点击时打 status.action 标并断言），只点首个可点条目。 */
        suspend fun clickStatusEntries() {
            var found: RecyclerView? = null
            fun findRv(v: View) {
                if (found != null) return
                when {
                    v is RecyclerView -> found = v
                    v is ViewGroup -> for (i in 0 until v.childCount) findRv(v.getChildAt(i))
                }
            }
            findRv(windowManager.view)
            val recycler = found
            if (recycler == null) {
                fail("status.action", "status", "状态区无 RecyclerView（状态窗口未渲染或结构变化），无法点击条目",
                    "InputView.kt:clickStatusEntries + StatusAreaWindow.kt:view")
                return
            }
            val n = recycler.childCount
            // 静态项索引 0/1/3/4（主题/输入法选项/虚拟键盘/自测递归入口）会启动应用或递归——跳过
            diagnostics += "status 相位: ${n} 个条目（跳过 0/1/3/4 跳应用/递归项）"
            for (i in 0 until n) {
                if (i in intArrayOf(0, 1, 3, 4)) continue
                MbmDebug.clearFeatureLog()
                runCatching { recycler.getChildAt(i).performClick() }
                delay(150)
                if ("status.action" !in tested) {
                    if (MbmDebug.hasRecentFeature("status.action")) {
                        pass("status.action", "status", "状态区条目 (idx=$i) 点击触发日志")
                    } else {
                        fail("status.action", "status", "点击条目 (idx=$i) 未打出 status.action 日志",
                            "StatusAreaWindow.kt:107-108 onItemClick mark+log")
                    }
                }
                break
            }
        }

        /** 手写识别：对已标记的 board（handwriting.recognize）合成 1 笔斜线笔画触发抬笔回调。 */
        suspend fun synthesizeHandwritingStroke() {
            val board = collectWindow().firstOrNull { it.second == "handwriting.recognize" }?.first
                as? HandwritingView
            if (board == null || board.width <= 0 || board.height <= 0) {
                fail("handwriting.recognize", "handwriting",
                    "手写板视图缺失/无尺寸/类型不符，无法合成笔画触发识别",
                    "InputView.kt:synthesizeHandwritingStroke + HandwritingWindow.kt markFeature(handwriting.recognize)")
                return
            }
            val w = board.width.toFloat()
            val h = board.height.toFloat()
            val t0s = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(t0s, t0s, MotionEvent.ACTION_DOWN, 0.2f * w, 0.8f * h, 0)
            val move = MotionEvent.obtain(t0s, t0s + 90, MotionEvent.ACTION_MOVE, 0.5f * w, 0.4f * h, 0)
            val up = MotionEvent.obtain(t0s, t0s + 180, MotionEvent.ACTION_UP, 0.8f * w, 0.3f * h, 0)
            MbmDebug.clearFeatureLog()
            board.onTouchEvent(down)
            board.onTouchEvent(move)
            board.onTouchEvent(up)
            down.recycle()
            move.recycle()
            up.recycle()
            delay(700)
            diagnostics += "handwriting 相位: 合成笔画 1 笔（斜线，板 ${board.width}x${board.height}）"
            if (MbmDebug.hasRecentFeature("handwriting.recognize")) {
                pass("handwriting.recognize", "handwriting", "合成笔画触发抬笔识别日志")
            } else {
                fail("handwriting.recognize", "handwriting",
                    "合成斜线笔画抬笔后未打出 handwriting.recognize 日志：抬笔回调未触发或手写引擎未就绪",
                    "HandwritingWindow.kt onStrokeEndListener → MbmDebug.logFeature")
            }
        }

        /** 剪贴板相位：首条目点击粘贴；同条目滑动（swipe_delete）；同条目长按（longpress 菜单）。 */
        suspend fun clipboardPhase() {
            val items = collectWindow().filter { it.second == "clipboard.paste" }
            if (items.isEmpty()) {
                fail("clipboard.paste", "clipboard",
                    "剪贴板窗口子树无条目（collectWindow 收集 0 个 clipboard.paste）：开窗成功但预置系统剪贴板条目未同步到面板（fcitx 剪贴板监听→Room 异步落库），或面板未渲染",
                    "InputView.kt:clipboardPhase + ClipboardAdapter.kt:87 markFeature + ClipboardWindow 条目数据源")
                return
            }
            val (v, id) = items.first()
            if (id in tested) {
                diagnostics += "clipboard 相位: clipboard.paste 已在键盘相位（闲时态粘贴 chip）判定，跳过面板粘贴点击"
            } else {
                MbmDebug.clearFeatureLog()
                runCatching { v.performClick() }
                delay(400)
                if (MbmDebug.hasRecentFeature(id)) pass(id, "clipboard", "剪贴板条目粘贴（面板）触发日志")
                else fail(id, "clipboard", "点击已执行但 feature=$id 日志未打出",
                    "ClipboardAdapter.kt 条目点击回调")
            }

            MbmDebug.clearFeatureLog()
            swipeGesture(v)
            if (MbmDebug.hasRecentFeature("clipboard.swipe_delete")) {
                pass("clipboard.swipe_delete", "clipboard", "条目滑动手势触发滑动删除日志")
            } else {
                fail("clipboard.swipe_delete", "clipboard",
                    "条目横向滑动手势未打出 clipboard.swipe_delete 日志：该路径无 logFeature(\"clipboard.swipe_delete\") 埋点（代码 grep 无调用）或滑动回调未绑定",
                    "MbmDebug.features:clipboard.swipe_delete + 剪贴板滑动删除回调未埋点")
            }

            MbmDebug.clearFeatureLog()
            longPressGesture(v)
            if (MbmDebug.hasRecentFeature("clipboard.longpress")) {
                pass("clipboard.longpress", "clipboard", "条目长按手势触发长按菜单日志")
            } else {
                fail("clipboard.longpress", "clipboard",
                    "条目长按手势未打出 clipboard.longpress 日志：条目长按路径无 logFeature(\"clipboard.longpress\") 调用（未埋点）",
                    "MbmDebug.features:clipboard.longpress + ClipboardAdapter 条目长按路径未埋点")
            }

            // v3.5：剪贴板功能按钮（clear/import 在扩展栏）与启用监听状态断言
            val clipExt = collectWindowWithBarExtension().distinctBy { it.second }
            for (btnId in listOf("clipboard.clear", "clipboard.import")) {
                val btn = clipExt.firstOrNull { it.second == btnId }?.first
                if (btn == null) {
                    fail(btnId, "clipboard", "剪贴板扩展栏无 ${btnId.replace("clipboard.", "")} 按钮（标记缺失或未渲染）",
                        "ClipboardUi.kt:${if (btnId == "clipboard.clear") "deleteAllButton" else "importButton"} markFeature")
                } else {
                    MbmDebug.clearFeatureLog()
                    runCatching { btn.performClick() }
                    delay(300)
                    if (MbmDebug.hasRecentFeature(btnId)) pass(btnId, "clipboard", "剪贴板${btnId.replace("clipboard.", "")}按钮点击触发日志")
                    else fail(btnId, "clipboard", "点击已执行但 feature=$btnId 日志未打出",
                        "ClipboardWindow.kt:${btnId.replace("clipboard.", "")} logFeature")
                }
            }
            val listening = AppPrefs.getInstance().clipboard.clipboardListening.getValue()
            if (listening) {
                pass("clipboard.enable", "clipboard", "剪贴板监听已启用（clipboardListening=true），启用开关功能生效")
            } else {
                val enableBtn = clipExt.firstOrNull { it.second == "clipboard.enable" }?.first
                if (enableBtn == null) {
                    fail("clipboard.enable", "clipboard", "剪贴板监听未启用且面板无启用按钮（Enable 指令未渲染）",
                        "ClipboardInstructionUi.kt:Enable enableButton markFeature")
                } else {
                    MbmDebug.clearFeatureLog()
                    runCatching { enableBtn.performClick() }
                    delay(300)
                    if (MbmDebug.hasRecentFeature("clipboard.enable")) {
                        pass("clipboard.enable", "clipboard", "启用按钮点击触发日志（监听已启用）")
                    } else {
                        fail("clipboard.enable", "clipboard", "点击已执行但 feature=clipboard.enable 日志未打出",
                            "ClipboardWindow.kt:enableButton logFeature")
                    }
                }
            }
        }

        /** 拾取器相位：点 1 条目上屏 + 长按（最多 3 条目轮试，首条目常为占位监听 null）+ 点 1 分类 Tab。
         *  v3.2：merged=true 时用「窗口子树 + bar extension」合并收集（分类 Tab 栏在
         *  PickerWindow 的 bar extension 即 titleUi 里，窗口子树内找不到）。 */
        suspend fun clickPickerPhase(phase: String, scope: View = root, merged: Boolean = false) {
            val all = if (merged) collectWindowWithBarExtension() else scope.collectFeatureViews()
            val views = all.distinctBy { it.second }
            var itemViews = all.filter { it.second == "picker.item" }.map { it.first }
            // v3.9：Emoji 默认分类全是笑脸（无肤色 modifier base，produceSkinTones 全 null
            // → 长按无 popup 绑定恒 FAIL）；可肤色的 👋👍 在第二个「人/手势」分类。切到
            // 该分类 Tab 后重新收集条目，保证长按可命中可肤色 emoji。
            if (phase == "picker:Emoji") {
                val tabs = all.filter { it.second == "picker.tab" }
                if (tabs.size > 1) {
                    runCatching { tabs[1].first.performClick() }
                    delay(450)
                    itemViews = (if (merged) collectWindowWithBarExtension() else scope.collectFeatureViews())
                        .filter { it.second == "picker.item" }.map { it.first }
                }
            }
            val item = itemViews.firstOrNull()
            if (item == null) {
                fail("picker.item", phase,
                    "$phase 面板无条目（无 picker.item 标记：该分类数据为空或面板未渲染）",
                    "InputView.kt:clickPickerPhase + PickerPageUi.kt:186,214 markFeature")
            } else {
                MbmDebug.clearFeatureLog()
                runCatching { item.performClick() }
                delay(200)
                if (MbmDebug.hasRecentFeature("picker.item")) pass("picker.item", phase, "条目上屏触发日志")
                else fail("picker.item", phase, "点击已执行但 feature=picker.item 日志未打出",
                    "PickerPageUi.kt:188,250 logFeature")

                MbmDebug.clearFeatureLog()
                var lpHit = false
                // v3.5：Emoji 分类长按仅对可肤色修改的条目绑定 popup（EmojiPickerPolicy.popup），
                // 前 3 个条目可能均不可修改 → 遍历扩大到全部（Emoji）或 3 个（其余分类）
                val maxTries = if (phase == "picker:Emoji") itemViews.size.coerceAtMost(12) else 3
                // v3.6：Emoji 优先可肤色修改条目——produceSkinTones 非空才绑长按 popup，
                // 排序让可修改 emoji 在前，避免前 12 个都不可修改导致长按恒打不出日志。
                // v3.8：排序取 emoji 必须用 TextKeyView.mainText.text（实际条目文本，
                // = policy.transform(item)），不能用 def.displayText——所有条目共享同一
                // keyAppearance def，displayText 对所有条目相同，排序形同失效（真机 v3.7
                // picker.longpress:Emoji 恒 FAIL 根因）。
                val ordered = if (phase == "picker:Emoji") {
                    itemViews.sortedByDescending { iv ->
                        val em = (iv as? TextKeyView)?.mainText?.text?.toString().orEmpty()
                        runCatching {
                            EmojiModifier.produceSkinTones(em, EmojiModifier.SkinTone.Default) != null
                        }.getOrDefault(false)
                    }
                } else itemViews
                for (iv in ordered.take(maxTries)) {
                    longPressGesture(iv)
                    if (MbmDebug.hasRecentFeature("picker.longpress")) {
                        lpHit = true
                        break
                    }
                }
                if (lpHit) {
                    pass("picker.longpress", phase, "条目长按手势触发日志")
                } else {
                    fail("picker.longpress", phase,
                        "依次试按 ${itemViews.size.coerceAtMost(maxTries)} 个条目长按均未打出 picker.longpress 日志：条目长按监听仅在 policy.popup(item) 非空时绑定，当前条目无 popup 绑定或监听为 null",
                        "PickerPageUi.kt:219-223 (setOnLongClickListener 条件绑定) + 194 (占位项 setOnLongClickListener(null))")
                }
            }
            val tab = views.firstOrNull { it.second == "picker.tab" }
            if (tab == null) {
                fail("picker.tab", phase, "$phase 未找到分类 Tab 控件（Tab 栏未渲染或无标记）",
                    "InputView.kt:clickPickerPhase + PickerTabsUi.kt:55 markFeature")
            } else {
                MbmDebug.clearFeatureLog()
                runCatching { tab.first.performClick() }
                delay(200)
                if (MbmDebug.hasRecentFeature("picker.tab")) pass("picker.tab", phase, "分类 Tab 点击触发日志")
                else fail("picker.tab", phase, "点击已执行但 feature=picker.tab 日志未打出",
                    "PickerTabsUi.kt:68 logFeature")
            }
        }

        /** 预置一条系统剪贴板条目，保证剪贴板窗口有内容可测。 */
        fun prefillSystemClipboard() {
            runCatching {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(
                    ClipData.newPlainText(
                        "mbm-selftest",
                        "MBM 自动测试剪贴板条目 ${System.currentTimeMillis()}"
                    )
                )
            }.onFailure { diagnostics += "剪贴板预置失败: ${it.message}" }
        }

        /** v3.7：settings-Activity 层——设置页 5 个入口逐项真实点击验证（跨 Activity 自测）。
         *  前置：status.settings 相位已点击跳转，MainActivity（设置主页）已在前台。逐项：
         *  滚动设置页主列表到底（保证入口项绑定并打标记）→ 收集入口标记 → 点击（onClick 内
         *  logFeature 断言跳转响应）→ 返回（用户词库页 navigateUp；其余新 Activity 经伴生 current
         *  onBackPressed 返回）。pinyin.phrase 为「用户词库」页根标记，经 smartdict 进入后收集。 */
        suspend fun runSettingsLayer(tested: MutableSet<String>) {
            val entries = listOf(
                "ruleimport.action" to "规则导入页（RuleImportActivity）",
                "smartdict.action" to "用户词库入口（SettingsRoute.PinyinCustomPhrase）",
                "password.bridge" to "隐私与密码页（PasswordGeneratorActivity）",
                "handwriting.settings" to "手写设置页（HandwritingActivity）",
                "pinyin.phrase" to "用户词库页根标记（PinyinCustomPhraseFragment）",
            )
            if (entries.none { it.first !in tested }) return
            // 设置页应已在前台（status.settings 相位已点击跳转）；等其完全渲染
            delay(700)
            withContext(Dispatchers.Main) {
                val act = MainActivity.current
                if (act == null) {
                    entries.forEach { (id, why) ->
                        if (id !in tested) {
                            tested += id
                            fail(id, "settings层", "设置页不可达（MainActivity.current==null）",
                                "MainActivity.kt:current 在 onResume 设置；status.settings 相位先跳设置页")
                        }
                    }
                    return@withContext
                }
                fun find(id: String): View? =
                    act.window.decorView.collectFeatureViews().firstOrNull { it.second == id }?.first

                // 滚动设置页主列表到底，让所有入口项绑定并打标记
                runCatching {
                    var targetRv: RecyclerView? = null
                    fun walkRv(v: View) {
                        if (v is RecyclerView && (targetRv == null || v.childCount > targetRv!!.childCount)) targetRv = v
                        if (v is ViewGroup) for (i in 0 until v.childCount) walkRv(v.getChildAt(i))
                    }
                    walkRv(act.window.decorView)
                    val rv = targetRv ?: return@runCatching
                    rv.layoutManager?.scrollToPosition((rv.adapter?.itemCount ?: 1) - 1)
                }
                delay(900)

                // 1) 用户词库：点击 smartdict 入口 → 进入用户词库页 → 收集 pinyin.phrase → navigateUp 返回
                if ("smartdict.action" !in tested || "pinyin.phrase" !in tested) {
                    val sv = find("smartdict.action")
                    if (sv == null) {
                        if ("smartdict.action" !in tested) {
                            tested += "smartdict.action"
                            fail("smartdict.action", "settings层", "设置页无 smartdict 入口标记（滚动到底后仍未绑定）",
                                "MainFragment.kt:132 addPreference(\"smartdict.action\")")
                        }
                    } else {
                        MbmDebug.clearFeatureLog()
                        sv.performClick()
                        delay(1000)
                        if (MbmDebug.hasRecentFeature("smartdict.action")) {
                            tested += "smartdict.action"
                            pass("smartdict.action", "settings层", "用户词库入口点击触发日志（跳转用户词库页）")
                        } else if ("smartdict.action" !in tested) {
                            tested += "smartdict.action"
                            fail("smartdict.action", "settings层", "点击已执行但日志未打出",
                                "MainFragment.kt:132 onClick 内 addPreference 包装 logFeature")
                        }
                        val pv = find("pinyin.phrase")
                        if (pv != null) {
                            tested += "pinyin.phrase"
                            pass("pinyin.phrase", "settings层", "用户词库页可达（PinyinCustomPhraseFragment 根标记）")
                        } else if ("pinyin.phrase" !in tested) {
                            tested += "pinyin.phrase"
                            fail("pinyin.phrase", "settings层", "进入用户词库页但未见 pinyin.phrase 根标记",
                                "PinyinCustomPhraseFragment.kt:onViewCreated markFeature")
                        }
                        runCatching { act.onBackPressedDispatcher.onBackPressed() }
                        delay(700)
                    }
                }

                // 2) 其余三个跳转入口：点击 → 断言日志 → 返回（新 Activity 经伴生 current）
                listOf("ruleimport.action", "password.bridge", "handwriting.settings").forEach { id ->
                    if (id !in tested) {
                        val v = find(id)
                        if (v == null) {
                            tested += id
                            fail(id, "settings层", "设置页无 $id 入口标记（滚动到底后仍未绑定）",
                                "MainFragment.kt:128-184 addPreference(\"$id\")")
                        } else {
                            MbmDebug.clearFeatureLog()
                            v.performClick()
                            delay(1000)
                            if (MbmDebug.hasRecentFeature(id)) {
                                tested += id
                                pass(id, "settings层", "入口点击触发日志（跳转对应页面）")
                            } else {
                                tested += id
                                fail(id, "settings层", "点击已执行但日志未打出",
                                    "MainFragment.kt onClick 内 addPreference 包装 logFeature")
                            }
                            // 返回：新 Activity（伴生 current）finish；否则导航回退
                            (RuleImportActivity.current ?: PasswordGeneratorActivity.current
                                ?: HandwritingActivity.current)?.let {
                                runCatching { it.finish() }
                            }
                            delay(700)
                        }
                    }
                }
            }
        }

        /** v3.3：daemon 行为层——不经 UI，直接经 fcitx daemon 程序化断言（短调用，主线程 runImmediately 可接受）。
         *  覆盖 IME 引擎查询/枚举与 fcitx 全局配置（Hotkey/Behavior 组）的回读，对应 fcitx 设置页的
         *  快捷键轮换、修饰键 250ms 时限、默认激活、共享输入状态、预编辑显示等项。只读为主；枚举做
         *  前后一步往返还原，不留引擎态副作用。 */
        suspend fun runDaemonLayer() {
            // 1) IME 引擎查询（引擎态无关）
            runCatching { fcitx.runImmediately { currentIme() } }.fold(
                onSuccess = { ime ->
                    diagnostics += "daemon 层: fcitx.currentIme()=" + (ime?.uniqueName ?: "<空>")
                    if (ime.uniqueName.isNotBlank()) {
                        pass("daemon.ime.current", "daemon", "fcitx 返回当前输入法 uniqueName=${ime.uniqueName}")
                    } else {
                        fail("daemon.ime.current", "daemon",
                            "fcitx.currentIme() 返回空 uniqueName（daemon 未就绪，inputMethodStatus 失败回退占位条目）",
                            "Fcitx.kt:138-139 currentIme → inputMethodStatus() ?: inputMethodEntryCached")
                    }
                },
                onFailure = { e ->
                    fail("daemon.ime.current", "daemon",
                        "fcitx.currentIme() 抛异常：${e.message}（daemon 未就绪/断开）",
                        "Fcitx.kt:138-139 currentIme + FcitxDaemon.kt runImmediately")
                }
            )

            // 2) 已启用/可用输入法列表
            val currentName = runCatching { fcitx.runImmediately { currentIme().uniqueName } }.getOrDefault("")
            runCatching { fcitx.runImmediately { enabledIme() } }.fold(
                onSuccess = { list ->
                    diagnostics += "daemon 层: enabledIme()=${list.size} 个 [${list.joinToString(",") { it.uniqueName }}]"
                    if (list.isNotEmpty() && (currentName.isEmpty() || list.any { it.uniqueName == currentName })) {
                        pass("daemon.ime.enabled", "daemon", "fcitx 返回 ${list.size} 个已启用输入法，且含当前引擎")
                    } else {
                        fail("daemon.ime.enabled", "daemon",
                            "enabledIme() 为空或未包含当前引擎（当前=$currentName，共${list.size}个）",
                            "Fcitx.kt:127-128 enabledIme → listInputMethods")
                    }
                },
                onFailure = { e ->
                    fail("daemon.ime.enabled", "daemon", "fcitx.enabledIme() 抛异常：${e.message}",
                        "Fcitx.kt:127-128 enabledIme")
                }
            )

            runCatching { fcitx.runImmediately { availableIme() } }.fold(
                onSuccess = { list ->
                    if (list.isNotEmpty()) {
                        pass("daemon.ime.available", "daemon", "fcitx 返回 ${list.size} 个可用输入法")
                    } else {
                        fail("daemon.ime.available", "daemon", "availableIme() 返回空列表",
                            "Fcitx.kt:124-125 availableIme → availableInputMethods")
                    }
                },
                onFailure = { e ->
                    fail("daemon.ime.available", "daemon", "fcitx.availableIme() 抛异常：${e.message}",
                        "Fcitx.kt:124-125 availableIme")
                }
            )

            // 3) 输入法枚举行为：前一步 + 后一步往返还原（≥2 个已启用引擎才可真正枚举）
            val n = runCatching { fcitx.runImmediately { enabledIme().size } }.getOrDefault(0)
            if (n < 2) {
                fail("daemon.ime.enumerate", "daemon",
                    "已启用输入法仅 $n 个（需 ≥2 才能验证枚举往返），非枚举 API 缺陷",
                    "InputView.kt:runDaemonLayer + Fcitx.kt:135-136 enumerateIme")
            } else {
                val before = runCatching { fcitx.runImmediately { currentIme().uniqueName } }.getOrDefault("")
                val fwd = runCatching { fcitx.runImmediately { enumerateIme(true) } }.isSuccess
                delay(300)
                runCatching { fcitx.runImmediately { currentIme().uniqueName } }.getOrDefault("").let { mid ->
                    runCatching { fcitx.runImmediately { enumerateIme(false) } }.isSuccess
                    delay(300)
                    val after = runCatching { fcitx.runImmediately { currentIme().uniqueName } }.getOrDefault("")
                    diagnostics += "daemon 层: enumerate 往返 before=$before fwdCall=$fwd mid=$mid after=$after"
                    if (fwd && after == before) {
                        pass("daemon.ime.enumerate", "daemon",
                            "枚举前跳+后跳往返还原到原引擎（$before），枚举 API 可用且可逆")
                    } else {
                        fail("daemon.ime.enumerate", "daemon",
                            "枚举未往返还原（before=$before mid=$mid after=$after fwdCall=$fwd）",
                            "Fcitx.kt:135-136 enumerateIme + Fcitx.kt:125 nextInputMethod")
                    }
                }
            }

            // 4) 全局配置回读（与 fcitx 设置页同一导出路径：getGlobalConfig → cfg 值子树）
            //    全量覆盖 Hotkey/Behavior 两组，逐键一条判定：PRESENT + 值格式合法（bool/int/enum/keylist 分型校验，宽容空默认值）。
            val cfg = runCatching { fcitx.runImmediately { getGlobalConfig() } }
                .map { it.findByName("cfg") }.getOrNull()
            val hotkeyG = cfg?.findByName("Hotkey")
            val behaviorG = cfg?.findByName("Behavior")
            diagnostics += "daemon 层: getGlobalConfig() cfg=${cfg?.subItems?.size ?: -1} 组 " +
                "Hotkey=${hotkeyG != null} Behavior=${behaviorG != null}"

            // 值格式校验器（宽容：KeyList 空默认/未设视为合法；bool/int 收标准 token）
            val vPresent: (String?) -> Boolean = { v -> v != null }
            val vNonBlank: (String?) -> Boolean = { v -> !v.isNullOrBlank() }
            val vBool: (String?) -> Boolean = { v ->
                v != null && (v.equals("true", true) || v.equals("false", true) || v == "1" || v == "0")
            }
            fun vInt(range: IntRange): (String?) -> Boolean =
                { v -> v?.toIntOrNull()?.let { it in range } == true }

            fun check(
                id: String, groupLabel: String, key: String, label: String, ok: (String?) -> Boolean
            ) {
                val g = if (groupLabel == "Hotkey") hotkeyG else behaviorG
                val value = g?.findByName(key)?.value
                if (ok(value)) pass(id, "daemon", "回读 $groupLabel.$key=$value（$label）")
                else fail(id, "daemon",
                    "$groupLabel 组缺 $key 或值非法（value=${value ?: "<缺失>"}，$label）",
                    "globalconfig.cpp $groupLabel 段 + Fcitx.kt:141-143 getGlobalConfig")
            }

            // Hotkey 组：快捷键/轮换（TriggerKeys…ModifierOnlyKeyTimeout）
            check("daemon.hotkey.toggle", "Hotkey", "TriggerKeys", "切换输入法热键", vPresent)
            check("daemon.hotkey.activate", "Hotkey", "ActivateKeys", "激活输入法热键", vPresent)
            check("daemon.hotkey.deactivate", "Hotkey", "DeactivateKeys", "停用输入法热键", vPresent)
            check("daemon.hotkey.alt_toggle", "Hotkey", "AltTriggerKeys", "临时切换输入法热键", vPresent)
            check("daemon.hotkey.enumerate_fwd", "Hotkey", "EnumerateForwardKeys", "向前枚举输入法", vPresent)
            check("daemon.hotkey.enumerate_bwd", "Hotkey", "EnumerateBackwardKeys", "向后枚举输入法", vPresent)
            check("daemon.hotkey.enumerate_trigger", "Hotkey", "EnumerateWithTriggerKeys", "修饰键持按枚举开关", vBool)
            check("daemon.hotkey.consume_redundant", "Hotkey", "ConsumeRedundantActivateKeys", "消费冗余激活键开关", vBool)
            check("daemon.hotkey.skip_first", "Hotkey", "EnumerateSkipFirst", "枚举跳过首个输入法开关", vBool)
            check("daemon.hotkey.enumerate_group", "Hotkey", "EnumerateGroupForwardKeys", "向前分组枚举输入法", vPresent)
            check("daemon.hotkey.paging", "Hotkey", "PrevPage", "回退翻页键", vPresent)
            check("daemon.hotkey.candidate_nav", "Hotkey", "PrevCandidate", "回退候选导航键", vPresent)
            check("daemon.hotkey.toggle_preedit", "Hotkey", "TogglePreedit", "切换内嵌预编辑热键", vPresent)
            check("daemon.hotkey.modifier_timeout", "Hotkey", "ModifierOnlyKeyTimeout", "修饰键快捷键时限(ms)", vInt(-1..5000))

            // Behavior 组：行为（ActiveByDefault…AutoSavePeriod）
            check("daemon.behavior.active_default", "Behavior", "ActiveByDefault", "默认激活输入法开关", vBool)
            check("daemon.behavior.reset_focus", "Behavior", "resetStateWhenFocusIn", "焦点进入重置状态策略", vNonBlank)
            check("daemon.behavior.share_state", "Behavior", "ShareInputState", "共享输入状态策略", vNonBlank)
            check("daemon.behavior.show_preedit", "Behavior", "PreeditEnabledByDefault", "程序内显示预编辑开关", vBool)
            check("daemon.behavior.ime_info", "Behavior", "ShowInputMethodInformation", "切换输入法显示信息开关", vBool)
            check("daemon.behavior.compact_info", "Behavior", "CompactInputMethodInformation", "紧凑输入法信息开关", vBool)
            check("daemon.behavior.page_size", "Behavior", "DefaultPageSize", "默认候选每页数", vInt(1..10))
            check("daemon.behavior.password", "Behavior", "AllowInputMethodForPassword", "密码字段允许输入法开关", vBool)
            check("daemon.behavior.password_preedit", "Behavior", "ShowPreeditForPassword", "密码字段显示预编辑开关", vBool)
            check("daemon.behavior.autosave", "Behavior", "AutoSavePeriod", "自动保存间隔(分钟)", vInt(0..1440))
        }

        fun viewTreeSample(): String {
            val sb = StringBuilder("视图树样本(windowManager.view): ")
            fun walk(v: View, depth: Int) {
                if (depth > 3) return
                sb.append(v.javaClass.simpleName).append(" ")
                if (v is ViewGroup) for (i in 0 until minOf(v.childCount, 10)) walk(v.getChildAt(i), depth + 1)
            }
            walk(windowManager.view, 0)
            return sb.toString()
        }

        /** 候选前置：向当前键盘窗口输入拼音音节，使浮动候选条产生候选词。
         *  v3.2：多音节 ni→hao→shi 轮试（单音节在部分引擎/词库下无候选），
         *  全部失败附 fcitx.currentIme() 引擎诊断。返回产生候选的音节；null = 未产生。 */
        suspend fun typePinyinForCandidates(): String? {
            // v3.4：引擎守卫——当前引擎非拼音时先经 fcitx 切到 pinyin，
            // 避免候选相位因 keyboard-us 等无候选引擎产生伪 FAIL（未切引擎即触发自测）
            val curIme = runCatching { fcitx.runImmediately { currentIme().uniqueName } }.getOrDefault("")
            if (curIme != "pinyin") {
                runCatching { fcitx.runImmediately { activateIme("pinyin") } }
                delay(400)
                val after = runCatching { fcitx.runImmediately { currentIme().uniqueName } }.getOrDefault("")
                diagnostics += "candidates 引擎守卫: 原引擎=$curIme → activateIme(pinyin) → 现引擎=$after"
                if (after != "pinyin") {
                    diagnostics += "candidates 引擎守卫: 切换到 pinyin 失败（引擎未就绪），按原引擎继续"
                }
            }
            // v3.6：候选相位前置二——强制字母布局（键盘相位可能已切到符号/数字布局，
            // 字母键不在当前布局则拼音无法输入、hit=0）+ 聚焦输入上下文（自测无真实
            // 焦点时 fcitx IC 可能未激活，拼音引擎不组词）
            runCatching { keyboardWindow.switchLayout(TextKeyboard.Name) }
            delay(300)
            runCatching { fcitx.runImmediately { focus(true) } }
            delay(200)
            // v3.8：清 IC preedit 残留（键盘相位可能残留提交/组合状态，污染拼音组词）
            // + 读当前 editorInfo 诊断 IC 是否可组词（onStartInput 仅 !isNullType 才
            // focus；若自测触发字段为 NULL type，IC 无 preedit，拼音不组词是环境限制）
            runCatching { fcitx.runImmediately { reset() } }
            delay(150)
            val ei = runCatching { service.currentInputEditorInfo }.getOrNull()
            diagnostics += "candidates 前置: editorInfo pkg=${ei?.packageName} type=${ei?.inputType} nullType=${ei?.isTypeNull()}"
            // v3.9：重激活输入上下文——v3.8 诊断显示备忘录正文（CLASS_TEXT，nullType=false）
            // IC 应可组词但拼音仍不组词；用 currentInputBinding.uid + editorInfo.packageName
            // 主动 activate（postFcitxJob lambda 内 FcitxAPI 方法，自测触发时 IC 可能未激活/失焦，
            // 拼音引擎无 preedit 不组词）
            runCatching {
                val binding = service.currentInputBinding
                if (binding != null) {
                    val pkg = ei?.packageName ?: "?"
                    service.postFcitxJob { activate(binding.uid, pkg) }
                    delay(250)
                    diagnostics += "candidates 前置: 重激活 IC uid=${binding.uid} pkg=$pkg"
                } else {
                    diagnostics += "candidates 前置: 无 currentInputBinding（IC 未绑定），无法重激活"
                }
            }
            val layoutNow = runCatching { keyboardWindow.currentLayoutName }.getOrDefault("?")
            diagnostics += "candidates 前置: 字母布局复位=${TextKeyboard.Name} 现布局=$layoutNow"
            fun backspaceView() = collectWindow().firstOrNull { it.second == "key.backspace" }?.first
            fun labelOf(v: View): String {
                val app = (v as? KeyView)?.def
                return if (app is KeyDef.Appearance.Text) app.displayText else ""
            }
            // v3.5：多候选音节优先（单音节同音词多 → 候选多页，prev/next/tab 可测）
            var totalHit = 0
            var bestHit = 0
            var bestSyllable = ""
            for (syllable in listOf("shi", "yi", "wo", "ni", "hao", "xie", "da", "men", "zhi", "qu")) {
                // 清残留拼音前缀：退格若干次
                backspaceView()?.let { bs ->
                    repeat(3) {
                        runCatching { bs.performClick() }
                        delay(60)
                    }
                }
                val letterViews = collectWindow().filter { it.second == "key.press" }.map { it.first }
                var hit = 0
                for (ch in syllable) {
                    val key = letterViews.firstOrNull { labelOf(it).equals(ch.toString(), ignoreCase = true) }
                    if (key != null) {
                        runCatching { key.performClick() }
                        delay(120)
                        hit++
                    }
                }
                totalHit += hit
                if (hit > bestHit) { bestHit = hit; bestSyllable = syllable }
                delay(500)
                val cand = service.selfTestCandidateView()?.collectFeatureViews().orEmpty()
                val produced = cand.any { it.second == "candidate.select" } ||
                    cand.any { it.second == "candidate.prev" || it.second == "candidate.next" }
                if (produced) {
                    diagnostics += "candidates 前置: 拼音输入 '$syllable' 产生候选（命中 $hit/${syllable.length} 键）"
                    return syllable
                }
            }
            val ime = runCatching { fcitx.runImmediately { currentIme() } }.getOrNull()
            diagnostics += "candidates 前置: 多音节 10 个轮试均未产生候选；当前引擎 " +
                "fcitx.currentIme()=" + (ime?.uniqueName ?: "<查询失败>") +
                "；字母键命中 累计$totalHit 最佳[$bestSyllable]=$bestHit；现布局=$layoutNow；" +
                "候选窗 visible=" + (service.selfTestCandidateView()?.visibility == android.view.View.VISIBLE)
            return null
        }

        /** 候选相位：候选条是 contentView 下键盘 InputView 的兄弟窗（键盘树内收集不到），经 service 引用触达。
         *  v3.2：前置拼音多音节轮试；候选上屏前先测候选条展开/收起（bar.candidate_expand）。 */
        suspend fun candidatePhase() {
            // v3.2.1：进入候选相位前重附键盘窗（键盘相位点击/回车 emoji 菜单可能已换出键盘视图），
            // 拼音输入与展开按钮测试需键盘窗处于附着态
            windowManager.attachWindow(KeyboardWindow)
            delay(300)
            val candidatesTyped = typePinyinForCandidates()
            val candView = service.selfTestCandidateView()
            if (candView == null) {
                for (id in listOf("candidate.select", "candidate.longpress", "candidate.prev", "candidate.next")) {
                    fail(id, "candidates", "浮动候选视图不可达：service.candidatesView 为 null（视图未创建）",
                        "FcitxInputMethodService.kt:selfTestCandidateView")
                }
                diagnostics += "candidates 相位: 候选视图 null"
                return
            }
            val views = candView.collectFeatureViews().distinctBy { it.second }
            diagnostics += "candidates 相位: 拼音 '${candidatesTyped ?: "无"}' 后候选视图 ${views.size} 个标记控件 " +
                "[${views.map { it.second }.distinct().joinToString(",")}] visible=${candView.visibility == View.VISIBLE}"
            // 候选展开/收起：候选可见时展开按钮（candidateUi.expandButton）可见可点；
            // 点 1 次展开候选窗，再点 1 次收起回键盘窗（两次均打 bar.candidate_expand 日志）
            if (views.any { it.second == "candidate.select" }) {
                MbmDebug.clearFeatureLog()
                val expandBtn = collect().firstOrNull { it.second == "bar.candidate_expand" }?.first
                if (expandBtn == null) {
                    fail("bar.candidate_expand", "candidates",
                        "候选条无展开按钮：candidateUi.expandButton 未打点/未渲染（候选态工具栏未切换）",
                        "KawaiiBarComponent.kt:candidateUi markFeature + CandidateUi expandButton")
                } else {
                    runCatching { expandBtn.performClick() }
                    delay(300)
                    if (MbmDebug.hasRecentFeature("bar.candidate_expand")) {
                        pass("bar.candidate_expand", "candidates", "展开按钮点击正确触发候选展开/收起日志")
                        runCatching { expandBtn.performClick() }
                        delay(300)
                    } else {
                        fail("bar.candidate_expand", "candidates",
                            "点击已执行但 feature=bar.candidate_expand 日志未打出（展开监听未绑定或日志缺失）",
                            "KawaiiBarComponent.kt:setExpandButtonToAttach/ToDetach logFeature")
                    }
                }
            }
            // 顺序：翻页 → 长按 → 选中上屏（select 提交后候选消失，放最后）
            // v3.5：candidate.tab（候选 Tab 动作按钮，PaginationUi 内）一并实测
            for (id in listOf("candidate.prev", "candidate.next", "candidate.tab")) {
                val v = views.firstOrNull { it.second == id }?.first
                if (v == null) {
                    fail(id, "candidates", "无翻页控件：候选仅单页 (hasPrev/hasNext=false)，PaginationUi 未生成，无 $id 标记",
                        "PagedCandidatesUi.kt:55-71 (翻页/翻页标记仅多页时生成)")
                } else {
                    MbmDebug.clearFeatureLog()
                    runCatching { v.performClick() }
                    delay(200)
                    if (MbmDebug.hasRecentFeature(id)) pass(id, "candidates", "候选翻页触发日志")
                    else fail(id, "candidates", "点击已执行但 feature=$id 日志未打出",
                        "PagedCandidatesUi.kt:65-70 onPrevPage/onNextPage → CandidatesView.kt:111-117 logFeature")
                }
            }
            val chip = views.firstOrNull { it.second == "candidate.select" }
            if (chip == null) {
                val reason = "无候选词控件：拼音输入 '${candidatesTyped ?: "ni/hao/shi 轮试"}' 未产生候选结果" +
                    "（当前输入法可能非拼音/引擎未就绪）或候选条未渲染 (evaluateVisibility()=false)"
                fail("candidate.select", "candidates", reason,
                    "CandidatesView.kt:140-145 evaluateVisibility + PagedCandidatesUi.kt:84 markFeature")
                fail("candidate.longpress", "candidates", "无候选词控件：拼音输入未产生候选结果，长按动作不可测",
                    "CandidatesView.kt:140-145 evaluateVisibility + PagedCandidatesUi.kt:88-91")
            } else {
                MbmDebug.clearFeatureLog()
                longPressGesture(chip.first)
                if (MbmDebug.hasRecentFeature("candidate.longpress")) {
                    pass("candidate.longpress", "candidates", "候选长按动作菜单触发日志")
                } else {
                    fail("candidate.longpress", "candidates", "候选词长按手势未打出 candidate.longpress 日志",
                        "CandidatesView.kt:107-108 onCandidateAction → logFeature")
                }
                MbmDebug.clearFeatureLog()
                runCatching { chip.first.performClick() }
                delay(300)
                if (MbmDebug.hasRecentFeature("candidate.select")) {
                    pass("candidate.select", "candidates", "候选词上屏触发日志")
                } else {
                    fail("candidate.select", "candidates", "点击已执行但 feature=candidate.select 日志未打出",
                        "CandidatesView.kt:103-104 onCandidateClick → logFeature")
                }
            }
        }

        /** 收尾：各相位未覆盖的注册 id → 统一 FAIL，按「为何未执行」给源码级原因（无 SKIP 态）。 */
        fun sweepFail(id: String) {
            if (id.startsWith("daemon.")) {
                fail(id, "sweep",
                    "daemon 行为层未执行该断言（runDaemonLayer 短路/异常跳过）",
                    "InputView.kt:runDaemonLayer + FcitxDaemon.kt:55-66 runImmediately")
                return
            }
            when (id) {
            "status.selftest" -> fail(id, "sweep",
                "递归触发点：该按钮是自测入口，再次点击将递归触发自测，设计上排除自动点击",
                "StatusAreaWindow.kt SelfTest 分支 + InputView.kt:runMbmSelfTest 排除逻辑")
            "ime.switch" -> fail(id, "sweep",
                "系统层功能：输入法切换入口在 Android 系统设置/InputMethodManager，不在 IME 窗口视图树，内置自测不可达",
                "MbmDebug.features:ime.switch（Android 系统 UI 范畴）")
            "ime.picker" -> fail(id, "sweep",
                "系统层功能：输入法选择器为系统 UI (InputMethodPicker)，不在 IME 窗口视图树，内置自测不可达",
                "MbmDebug.features:ime.picker（Android 系统 UI 范畴）")
            "smartdict.action", "ruleimport.action", "pinyin.phrase", "password.bridge", "handwriting.settings" ->
                fail(id, "sweep", "设置页功能：入口在 MBM 设置 Activity，不在当前 InputView 树，内置自测不可达",
                    "MbmDebug.features:$id + ui/main/settings 设置页入口")
            "bar.candidate_expand" -> fail(id, "sweep",
                "候选相位未执行展开按钮测试：拼音输入未产生候选词（引擎未就绪），候选条未渲染、展开按钮不可见不可测",
                "InputView.kt:candidatePhase + KawaiiBarComponent.kt expandButton markFeature/logFeature")
            "candidate.tab" -> fail(id, "sweep",
                "无标记埋点：代码中不存在 markFeature(\"candidate.tab\") 调用，PagedCandidatesUi 仅埋 prev/next/select",
                "MbmDebug.features:candidate.tab + PagedCandidatesUi.kt:64-84")
            "status.editorinfo" -> fail(id, "sweep",
                "控件未渲染：状态窗口 barExtension 生成时未包含 editorInfoButton（editorInfoInspector 临时置位失效或合并收集遗漏扩展视图）",
                "InputView.kt status 相位 editorInfoInspector 临时开启 + StatusAreaWindow.kt:219-226")
            "clipboard.enable" -> fail(id, "sweep",
                "面板无控件：剪贴板同步开关入口在设置页，剪贴板面板内无对应按钮",
                "MbmDebug.features:clipboard.enable（设置页入口，未埋点）")
            "clipboard.clear" -> fail(id, "sweep",
                "面板无控件：清空剪贴板入口在设置页/面板工具栏，面板条目无对应标记",
                "MbmDebug.features:clipboard.clear（入口未埋点）")
            "clipboard.import" -> fail(id, "sweep",
                "面板无控件：导入剪贴板入口在设置页，剪贴板面板内无对应按钮",
                "MbmDebug.features:clipboard.import（设置页入口，未埋点）")
            else -> fail(id, "sweep",
                "本窗口未找到：当前视图树无 mbm:feature=$id 控件（当前布局/状态不含该控件），无法测试",
                "InputView.kt:runMbmSelfTest（无该标记节点）")
        }
        }

        var statusSettingsView: View? = null

        // 0) 回到键盘窗口
        windowManager.attachWindow(KeyboardWindow)
        delay(400)

        // 前置门：0 个标记控件 → 单条 FAIL + 诊断，直接返回（避免同因 FAIL 刷屏）
        var kbd = collect()
        val initialIds = kbd.map { it.second }.distinct()
        diagnostics += "键盘窗口: 找到 ${kbd.size} 个标记控件 [${initialIds.joinToString(",")}] " +
            "${windowManager.debugDump()}"
        if (kbd.none { it.second in openers }) {
            ensureToolbar("keyboard")
            kbd = collect()
        }
        if (kbd.isEmpty()) {
            fail("precondition", "engine",
                "键盘窗口无任何标记控件（窗口未打开/未渲染/标记丢失），后续相位未执行",
                "InputView.kt:runMbmSelfTest 前置门 + MbmDebug.collectFeatureViews")
            diagnostics += viewTreeSample()
            return MbmDebug.SelfTestReport(results, diagnostics, SystemClock.elapsedRealtime() - t0)
        }

        // 1) 键盘窗口：按键/工具栏（开窗与危险按钮留给各自相位）
        // v3.2.1：bar.candidate_expand 判定权归 candidates 相位（展开按钮点击可能附带候选展开窗，
        // 在键盘相位点击会污染后续 key.longpress 的视图树收集）
        clickPhase("keyboard", openers + dangerous + setOf("key.longpress", "bar.candidate_expand"))

        // 1b) key.longpress：对空格键合成长按（SpaceKey 绑定 LongPress 行为 → 打 key.longpress 日志）
        // v3.2.1：兜底重附键盘窗——键盘相位点击 key.return（回车 Emoji 菜单开启）等可能附带弹开
        // emoji picker 窗，键盘视图被换出，重附后再收集避免误报「当前布局无空格键」
        windowManager.attachWindow(KeyboardWindow)
        delay(300)
        val spaceView = collect().distinctBy { it.second }.firstOrNull { it.second == "key.space" }?.first
        if (spaceView == null) {
            fail("key.longpress", "keyboard", "当前布局无空格键（键盘视图树无 key.space 标记），无法执行长按",
                "InputView.kt:key.longpress + BaseKeyboard.kt:createKeyView featureId(SpaceKey)")
        } else {
            MbmDebug.clearFeatureLog()
            longPressGesture(spaceView)
            if (MbmDebug.hasRecentFeature("key.longpress")) {
                pass("key.longpress", "keyboard", "空格键长按手势触发 key.longpress 日志")
            } else {
                fail("key.longpress", "keyboard",
                    "空格键合成长按手势 (ACTION_DOWN 持 650ms) 未打出 key.longpress 日志：LongPress 行为回调未触发",
                    "BaseKeyboard.kt:createKeyView LongPress 分支 → MbmDebug.logFeature")
            }
        }

        // 1c) 浮动候选（键盘相位拼音输入后，候选条可能出现；经 service 触达兄弟窗）
        candidatePhase()

        // 2) 状态区（v3.2：窗口子树 + bar extension 合并收集；临时开启 editorInfoInspector
        //    使状态窗扩展视图含 editorInfoButton，测完恢复原值；扩展视图 lazy、窗口每次新建须先置 pref）
        ensureToolbar("status")
        val inspectorPref: ManagedPreference<Boolean> = AppPrefs.getInstance().internal.editorInfoInspector
        val inspectorBefore = inspectorPref.getValue()
        inspectorPref.setValue(true)
        try {
            clickOpener("bar.more", "status")
            delay(300)
            clickStatusEntries()
            statusSettingsView = collectWindowWithBarExtension().firstOrNull { it.second == "status.settings" }?.first
            clickPhaseWindow("status", dangerous)
            // v3.5：自测入口——点击验证 status.selftest 日志（防递归守卫保证自测运行中不重入）。
            // v3.6：仅当 clickPhaseWindow 未实测过（status.selftest 不在 tested）才走专用逻辑，
            // 否则重复判定（相位先 PASS 后专用逻辑又 FAIL → 双计，TOTAL 虚增）。
            if ("status.selftest" !in tested) {
                val selfTestView = collectWindowWithBarExtension().firstOrNull { it.second == "status.selftest" }?.first
                if (selfTestView == null) {
                    fail("status.selftest", "status",
                        "状态区无自测条目（status.selftest 标记缺失：SelfTest 类型条目未渲染或标记未打）",
                        "StatusAreaWindow.kt:onItemClick SelfTest 标记 + StatusAreaEntry.Android.Type.SelfTest")
                } else {
                    MbmDebug.clearFeatureLog()
                    runCatching { selfTestView.performClick() }
                    delay(300)
                    if (MbmDebug.hasRecentFeature("status.selftest")) {
                        pass("status.selftest", "status", "自测入口点击触发日志（防递归守卫生效，未重入）")
                    } else {
                        fail("status.selftest", "status", "点击已执行但 feature=status.selftest 日志未打出",
                            "StatusAreaWindow.kt SelfTest 分支 logFeature")
                    }
                }
            }
        } finally {
            inspectorPref.setValue(inspectorBefore)
        }
        windowManager.attachWindow(KeyboardWindow)
        delay(300)

        // 3) 手写窗口（v3.2：合并收集）
        ensureToolbar("handwriting")
        clickOpener("bar.handwriting", "handwriting")
        delay(500)
        synthesizeHandwritingStroke()
        clickPhaseWindow("handwriting")
        windowManager.attachWindow(KeyboardWindow)
        delay(300)

        // 4) 文本编辑窗口（v3.2：合并收集——剪贴板按钮等扩展视图在 TextEditingWindow 的 bar extension）
        ensureToolbar("editing")
        clickOpener("bar.cursor", "editing")
        delay(400)
        clickPhaseWindow("editing")
        windowManager.attachWindow(KeyboardWindow)
        delay(300)

        // 5) 剪贴板窗口（先预置系统剪贴板条目并留足同步时间，再 粘贴/滑动/长按）
        prefillSystemClipboard()
        delay(1500)
        ensureToolbar("clipboard")
        clickOpener("bar.clipboard", "clipboard")
        delay(500)
        clipboardPhase()
        windowManager.attachWindow(KeyboardWindow)
        delay(300)

        // 6) 符号/表情/颜文字面板（v3.2：合并收集——分类 Tab 栏在 PickerWindow 的 bar extension/titleUi）
        for (key in arrayOf(PickerWindow.Key.Symbol, PickerWindow.Key.Emoji, PickerWindow.Key.Emoticon)) {
            windowManager.attachWindow(key)
            delay(500)
            clickPickerPhase("picker:$key", scope = windowManager.view, merged = true)
            windowManager.attachWindow(KeyboardWindow)
            delay(300)
        }

        // 7) 危险键最后：先测当前布局仍找得到的键（quickphrase/语言），再符号面板（key.sym 开符号
        //    面板会换出键盘窗口）与布局切换（key.layout 重建键盘视图），最后菜单/隐藏/设置（跳离 IME）
        // v3.5：数字键盘键位——key.number（NumPadKey）与 key.quickphrase（QuickPhraseKey，已接进
        //    NumberKeyboard 第一行）仅在 NumberKeyboard 布局存在，先切过去逐一断言再切回
        windowManager.attachWindow(KeyboardWindow)
        delay(300)
        runCatching { keyboardWindow.switchLayout(NumberKeyboard.Name) }
        delay(500)
        clickOne("key.number", "危险键")
        clickOne("key.quickphrase", "危险键")
        runCatching { keyboardWindow.switchLayout(TextKeyboard.Name) }
        delay(500)
        windowManager.attachWindow(KeyboardWindow)
        delay(300)
        clickOne("key.lang", "危险键")
        // v3.5：ime.switch/ime.picker——key.lang 点击=切换语言（LangSwitchAction→ime.switch），
        //        长按=输入法选择器（showInputMethodPicker→ime.picker）。选择器为面板内窗口
        //        （MBM v0.0.09，不抢焦点），attachWindow(KeyboardWindow) 即可切回。
        val langView = collect().firstOrNull { it.second == "key.lang" }?.first
        if (langView == null) {
            fail("ime.switch", "危险键", "无 key.lang 标记视图（语言键未渲染），切换语言不可测",
                "CommonKeyActionListener.kt:123-124 LangSwitchAction + TextKeyboard langKey markFeature")
            fail("ime.picker", "危险键", "无 key.lang 标记视图（语言键未渲染），输入法选择器不可测",
                "CommonKeyActionListener.kt:87-88 showInputMethodPicker")
        } else {
            // clickOne("key.lang") 已触发 LangSwitchAction → ime.switch 日志（同帧未清）
            if (MbmDebug.hasRecentFeature("ime.switch")) {
                pass("ime.switch", "危险键", "语言键点击触发切换日志（LangSwitchAction）")
            } else {
                fail("ime.switch", "危险键", "key.lang 点击已执行但 ime.switch 日志未打出",
                    "CommonKeyActionListener.kt:123-124 LangSwitchAction → logFeature(\"ime.switch\")")
            }
            MbmDebug.clearFeatureLog()
            longPressGesture(langView)
            delay(400)
            if (MbmDebug.hasRecentFeature("ime.picker")) {
                pass("ime.picker", "危险键", "语言键长按触发输入法选择器日志（showInputMethodPicker）")
            } else {
                fail("ime.picker", "危险键", "key.lang 长按已执行但 ime.picker 日志未打出",
                    "CommonKeyActionListener.kt:87-88 showInputMethodPicker → logFeature(\"ime.picker\")")
            }
            windowManager.attachWindow(KeyboardWindow)
            delay(400)
        }
        clickOne("key.sym", "危险键")
        windowManager.attachWindow(KeyboardWindow)
        delay(400)
        clickOne("key.layout", "危险键")
        if (collect().none { it.second == "bar.menu" || it.second == "bar.hide" }) {
            diagnostics += "危险键相位前置: bar.menu/bar.hide 不在视图树，重挂键盘窗口再扫"
            windowManager.attachWindow(KeyboardWindow)
            delay(600)
        }
        clickOne("bar.menu", "危险键")
        clickOne("bar.hide", "危险键")
        val settingsView = statusSettingsView
        if (settingsView == null) {
            fail("status.settings", "危险键", "状态窗口无设置按钮（status 相位未捕获到 status.settings 标记，barExtension 未渲染）",
                "StatusAreaWindow.kt:211 markFeature + InputView.kt status 相位捕获")
        } else {
            MbmDebug.clearFeatureLog()
            runCatching { settingsView.performClick() }
            delay(300)
            if (MbmDebug.hasRecentFeature("status.settings")) {
                pass("status.settings", "危险键", "设置入口点击触发日志（跳设置页）")
            } else {
                fail("status.settings", "危险键", "点击已执行但 feature=status.settings 日志未打出（视图可能已脱离）",
                    "StatusAreaWindow.kt:212-214 settingsButton onClick → logFeature")
            }
        }

        // 8) daemon 行为层（v3.3）：不经 UI，直接经 fcitx daemon 程序化断言（IME 查询/枚举 + 全局配置回读）
        runDaemonLayer()

        // 8.5) settings-Activity 层（v3.7）：设置页 5 个入口逐项点击验证（跨 Activity 自测）
        runSettingsLayer(tested)

        // 9) 收尾：注册表中未实测的 id → FAIL（按类给源码级原因，无 SKIP 态）
        MbmDebug.features.keys.filter { it !in tested }.forEach { sweepFail(it) }

        return MbmDebug.SelfTestReport(results, diagnostics, SystemClock.elapsedRealtime() - t0)
    }

    private val horizontalCandidate = HorizontalCandidateComponent()
    private val keyboardWindow = KeyboardWindow()

    /** MBM v1.0.39：全局计算器候选条（跨窗口常驻，宿主在 InputView 键盘区上方）。 */
    private val calcCandidate = CalcCandidateComponent()
    private val symbolPicker = symbolPicker()
    private val emojiPicker = emojiPicker()
    private val emoticonPicker = emoticonPicker()

    private fun setupScope() {
        scope += this@InputView.wrapToUniqueComponent()
        scope += service.wrapToUniqueComponent()
        scope += fcitx.wrapToUniqueComponent()
        scope += theme.wrapToUniqueComponent()
        scope += themedContext.wrapToUniqueComponent()
        scope += broadcaster
        scope += popup
        scope += punctuation
        scope += returnKeyDrawable
        scope += preeditEmptyState
        scope += preedit
        scope += commonKeyActionListener
        scope += windowManager
        scope += kawaiiBar
        scope += horizontalCandidate
        scope += calcCandidate
        broadcaster.onScopeSetupFinished(scope)
    }

    private val keyboardPrefs = AppPrefs.getInstance().keyboard

    private val focusChangeResetKeyboard by keyboardPrefs.focusChangeResetKeyboard

    private val keyboardHeightPercent = keyboardPrefs.keyboardHeightPercent
    private val keyboardHeightPercentLandscape = keyboardPrefs.keyboardHeightPercentLandscape
    private val keyboardSidePadding = keyboardPrefs.keyboardSidePadding
    private val keyboardSidePaddingLandscape = keyboardPrefs.keyboardSidePaddingLandscape
    private val keyboardBottomPadding = keyboardPrefs.keyboardBottomPadding
    private val keyboardBottomPaddingLandscape = keyboardPrefs.keyboardBottomPaddingLandscape

    private val advancedPrefs = AppPrefs.getInstance().advanced
    private val keyboardHeightPercentBase = advancedPrefs.keyboardHeightPercentBase

    private val keyboardSizePrefs = listOf(
        keyboardHeightPercent,
        keyboardHeightPercentLandscape,
        keyboardSidePadding,
        keyboardSidePaddingLandscape,
        keyboardBottomPadding,
        keyboardBottomPaddingLandscape,
        keyboardHeightPercentBase,
    )

    private val keyboardHeightPx: Int
        get() {
            val baseType = keyboardHeightPercentBase.getValue()
            val base = when (baseType) {
                DisplayMetrics -> resources.displayMetrics.heightPixels
                RealSize -> Point().also {
                    @Suppress("DEPRECATION")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        context.display
                    } else {
                        context.windowManager.defaultDisplay
                    }.getRealSize(it)
                }.y
            }
            val percent = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardHeightPercentLandscape
                else -> keyboardHeightPercent
            }.getValue()
            Timber.d("keyboardHeightPx get(): baseType=${baseType}, base=${base}, percent=${percent}")
            return base * percent / 100
        }

    private val keyboardSidePaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardSidePaddingLandscape
                else -> keyboardSidePadding
            }.getValue()
            return dp(value)
        }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> keyboardBottomPaddingLandscape
                else -> keyboardBottomPadding
            }.getValue()
            return dp(value)
        }

    @Keep
    private val onKeyboardSizeChangeListener = ManagedPreferenceProvider.OnChangeListener { key ->
        if (keyboardSizePrefs.any { it.key == key }) {
            updateKeyboardSize()
        }
    }

    val keyboardView: View

    /**
     * MBM v1.0.43：IME「可见 / 可触摸区域」的上沿（窗口坐标）。
     *
     * 计算条（[calcCandidate]）与预编辑条（[preedit]）是 keyboardView 的**兄弟节点**，
     * 布局在它上方（`above(keyboardView)`）。而 onComputeInsets 只能拿到一条
     * visibleTopInsets —— 旧实现只写 keyboardView 的顶部，于是这两条就落在了
     * IME 声明的可触摸区域**之外**：点击其上的候选项会被系统判定为「点击 IME 外部」，
     * 直接收起键盘，候选项的 onClick 根本不会执行
     * （现象＝点了计算候选、输入法立刻收起、结果也上不了屏）。
     *
     * 这里把当前可见的浮层一并纳入，返回最靠上的那条的顶部。
     */
    fun touchableTopY(): Int {
        val loc = IntArray(2)
        var top = Int.MAX_VALUE
        fun consider(v: View?) {
            if (v == null || v.visibility != View.VISIBLE) return
            // 尚未完成布局时 getLocationInWindow 会给出 (0,0)，若据此把上沿取 0
            // 会让整屏都变成 IME 可触摸区（App 点不动）——坐标不可信的一律跳过
            if (v.width <= 0 || v.height <= 0) return
            v.getLocationInWindow(loc)
            if (loc[1] <= 0) return
            if (loc[1] < top) top = loc[1]
        }
        consider(keyboardView)
        consider(preedit.ui.root)
        consider(calcCandidate.view)
        if (top == Int.MAX_VALUE) {
            keyboardView.getLocationInWindow(loc)
            top = loc[1]
        }
        return top
    }

    init {
        // MUST call before any operation
        setupScope()

        // restore punctuation mapping in case of InputView recreation
        fcitx.launchOnReady {
            punctuation.updatePunctuationMapping(it.statusAreaActionsCached)
        }

        // make sure KeyboardWindow's view has been created before it receives any broadcast
        windowManager.addEssentialWindow(keyboardWindow, createView = true)
        windowManager.addEssentialWindow(symbolPicker)
        windowManager.addEssentialWindow(emojiPicker)
        windowManager.addEssentialWindow(emoticonPicker)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        broadcaster.onImeUpdate(fcitx.runImmediately { inputMethodEntryCached })

        customBackground.imageDrawable = theme.backgroundDrawable(keyBorder)

        keyboardView = constraintLayout {
            // allow MotionEvent to be delivered to keyboard while pressing on padding views.
            // although it should be default for apps targeting Honeycomb (3.0, API 11) and higher,
            // but it's not the case on some devices ... just set it here
            isMotionEventSplittingEnabled = true
            add(customBackground, lParams {
                centerVertically()
                centerHorizontally()
            })
            add(kawaiiBar.view, lParams(matchParent, dp(KawaiiBarComponent.HEIGHT)) {
                topOfParent()
                centerHorizontally()
            })
            add(leftPaddingSpace, lParams {
                below(kawaiiBar.view)
                startOfParent()
                bottomOfParent()
            })
            add(rightPaddingSpace, lParams {
                below(kawaiiBar.view)
                endOfParent()
                bottomOfParent()
            })
            add(windowManager.view, lParams {
                below(kawaiiBar.view)
                above(bottomPaddingSpace)
                /**
                 * set start and end constrain in [updateKeyboardSize]
                 */
            })
            add(bottomPaddingSpace, lParams {
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
                bottomOfParent()
            })
        }

        // MBM v0.0.07：窗口切换（键盘<->手写）时重算面板高度
        windowManager.onWindowSwitched = { updateKeyboardSize() }

        updateKeyboardSize()

        add(preedit.ui.root, lParams(matchParent, wrapContent) {
            above(keyboardView)
            centerHorizontally()
        })
        // MBM v1.0.39：全局计算器候选条——跨键盘窗口（含符号/表情 Picker）常驻；
        // 与拼音预编辑互斥（组件侧 preeditEmpty 门控），不会同时出现。
        add(calcCandidate.view, lParams(matchParent, wrapContent) {
            above(keyboardView)
            centerHorizontally()
        })
        add(keyboardView, lParams(matchParent, wrapContent) {
            centerHorizontally()
            bottomOfParent()
        })
        add(popup.root, lParams(matchParent, matchParent) {
            centerVertically()
            centerHorizontally()
        })

        keyboardPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
        advancedPrefs.registerOnChangeListener(onKeyboardSizeChangeListener)
    }

    /**
     * MBM v0.0.07：手写模式的面板最低高度（屏幕高的 52%）。
     * 用户反馈「手写区域太小」：键盘高度百分比是按打字需求设计的（往往 ~30%），
     * 手写板继承同一高度只剩一条缝。手写窗口激活时取 max(用户设置, 52%)，
     * 切回键盘恢复用户设置，互不影响。
     */
    private val handwritingMinHeightPx: Int
        get() = resources.displayMetrics.heightPixels * 52 / 100

    private fun updateKeyboardSize() {
        windowManager.view.updateLayoutParams {
            height = if (windowManager.isHandwritingActive)
                maxOf(keyboardHeightPx, handwritingMinHeightPx)
            else keyboardHeightPx
        }
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
        val sidePadding = keyboardSidePaddingPx
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = GONE
            rightPaddingSpace.visibility = GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = VISIBLE
            rightPaddingSpace.visibility = VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        kawaiiBar.view.setPadding(sidePadding, 0, sidePadding, 0)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        return insets
    }

    /**
     * called when [InputView] is about to show, or restart
     */
    fun startInput(info: EditorInfo, capFlags: CapabilityFlags, restarting: Boolean = false) {
        broadcaster.onStartInput(info, capFlags)
        returnKeyDrawable.updateDrawableOnEditorInfo(info)
        if (focusChangeResetKeyboard || !restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    override fun onStartHandleFcitxEvent() {
        val inputPanelData = fcitx.runImmediately { inputPanelCached }
        val inputMethodEntry = fcitx.runImmediately { inputMethodEntryCached }
        val statusAreaActions = fcitx.runImmediately { statusAreaActionsCached }
        arrayOf(
            FcitxEvent.InputPanelEvent(inputPanelData),
            FcitxEvent.IMChangeEvent(inputMethodEntry),
            FcitxEvent.StatusAreaEvent(
                FcitxEvent.StatusAreaEvent.Data(statusAreaActions, inputMethodEntry)
            )
        ).forEach { handleFcitxEvent(it) }
    }

    override fun handleFcitxEvent(it: FcitxEvent<*>) {
        when (it) {
            is FcitxEvent.CandidateListEvent -> {
                broadcaster.onCandidateUpdate(it.data)
            }
            is FcitxEvent.ClientPreeditEvent -> {
                preeditEmptyState.updatePreeditEmptyState(clientPreedit = it.data)
                broadcaster.onClientPreeditUpdate(it.data)
            }
            is FcitxEvent.InputPanelEvent -> {
                preeditEmptyState.updatePreeditEmptyState(preedit = it.data.preedit)
                broadcaster.onInputPanelUpdate(it.data)
            }
            is FcitxEvent.IMChangeEvent -> {
                broadcaster.onImeUpdate(it.data)
            }
            is FcitxEvent.StatusAreaEvent -> {
                punctuation.updatePunctuationMapping(it.data.actions)
                broadcaster.onStatusAreaUpdate(it.data.actions)
            }
            else -> {}
        }
    }

    fun updateSelection(start: Int, end: Int) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean {
        return kawaiiBar.handleInlineSuggestions(response)
    }

    override fun onDetachedFromWindow() {
        advancedPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        keyboardPrefs.unregisterOnChangeListener(onKeyboardSizeChangeListener)
        // N62：解注册 KawaiiBarComponent 的监听器，避免旧视图树泄漏
        kawaiiBar.onDetachedFromWindow()
        // clear DynamicScope, implies that InputView should not be attached again after detached.
        scope.clear()
        super.onDetachedFromWindow()
    }

}
