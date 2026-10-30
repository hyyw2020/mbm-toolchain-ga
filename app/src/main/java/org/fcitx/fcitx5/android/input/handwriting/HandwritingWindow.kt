/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * MBM: 键盘内离线手写输入面板。
 *
 * 依赖 :lib:handwritten(Ismantic/Handwritten, Apache-2.0, NCNN INT8 MobileNetV2):
 * 全程离线(APK 无 INTERNET 权限),默认开启 13 路 TTA 集成 + top-10 候选,
 * 追求超高精度(耗电不敏感)。选字/空格/换行/退格全部走键盘同一条
 * CommonKeyActionListener 通道,保证与 fcitx 状态一致。
 *
 * MBM v0.0.02 布局重构（对齐主流手写输入法半屏手写，用户真机对比反馈"布局十分不合理"）：
 *  1. 手写板占满剩余空间 —— 旧版被 64dp 候选条 + 状态行 + 按钮行挤压成窄条，
 *     主流手写输入法同场景下手写区占面板约 3/4；现在候选条压到 72dp、状态行与拼音合并，
 *     板子拿到全部剩余高度。
 *  2. 候选条信息量对齐主流手写输入法：首候选大字(26sp 加粗) + 候选拼音（无声调），
 *     单字候选后追加关联词（「岁」→ 岁数…，见 HandwritingLexicon）。
 *  3. 右侧竖排快捷键加宽到 56dp、加大字号（⌫，。？！），触控不再误触。
 *  4. 底部功能行加「符号」键（PickerSwitchAction → 符号面板），
 *     四个键统一圆角底色；空格键智能化为"有候选时选中首候选"（与主流手写输入法一致）。
 */
package org.fcitx.fcitx5.android.input.handwriting

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.input.keyboard.CommonKeyActionListener
import org.fcitx.fcitx5.android.input.keyboard.KeyAction
import org.fcitx.fcitx5.android.input.keyboard.KeyActionListener
import org.fcitx.fcitx5.android.input.picker.PickerData
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import org.fcitx.fcitx5.android.input.wm.InputWindow
import org.fcitx.fcitx5.android.lib.handwritten.HandwritingLexicon
import org.fcitx.fcitx5.android.lib.handwritten.HandwrittenRecognizer
import org.fcitx.fcitx5.android.ui.main.HandwritingView
import org.fcitx.fcitx5.android.utils.MbmDebug
import org.mechdancer.dependency.manager.must
import timber.log.Timber

class HandwritingWindow : InputWindow.ExtendedInputWindow<HandwritingWindow>() {

    private val commonKeyActionListener: CommonKeyActionListener by manager.must()

    override val title: String
        get() = context.getString(R.string.handwriting)

    // v0.0.07：隐藏标题栏。用户反馈「手写区域太小」——标题只显示"高手手写"几个字
    // 却吃掉一整行高度；手写板自身视觉已足够表明模式，这行高度全部让给板子。
    override val showTitle: Boolean = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    // N75：识别无超时则 job.cancel() 取消不了已进入 JNI 的 recognize()，
    // 模型卡死时界面僵在「识别中…」；设上限兜底报错并允许再次书写。
    private val RECOGNIZE_TIMEOUT_MS = 8000L

    private lateinit var board: HandwritingView
    private lateinit var candidateRow: LinearLayout
    private lateinit var candidateScroll: HorizontalScrollView
    private lateinit var hintText: TextView

    /** 拼音/关联词库（assets，后台懒加载；识别已在后台线程，加载开销可忽略） */
    private var lexicon: HandwritingLexicon? = null

    /**
     * 符号网格视图缓存（v0.0.10）：早期每次点「符号」都重建 ~100 个 Button，
     * 主线程构建开销可感知；现在按分类缓存，切换分类只改可见性，零重建。
     */
    private val symbolViews = HashMap<String, View>()

    /** 最近一次识别候选：空格键"选中首候选"用（与主流手写输入法一致） */
    private var lastCandidates: List<HandwrittenRecognizer.Candidate> = emptyList()

    // v0.0.06：内嵌符号面板。手写模型字符集 3755 类里**零个符号**（训练数据无符号），
    // 所以符号不可能靠"写"被识别出来；原「符号」键走跨窗口 PickerSwitchAction，
    // 在手写窗口内不生效（点了没反应）——现改为面板内直接铺符号网格，点击即上屏。
    private var middleRowRef: LinearLayout? = null
    private var symbolPanelRef: LinearLayout? = null
    private var symbolGridHost: LinearLayout? = null

    /**
     * 首次识别时才加载模型(assets 读取 + NCNN 载入放在后台线程)。
     * MBM v0.0.10：引擎改为进程级单例（HandwrittenRecognizer.get）——
     * NCNN 载库 + 模型反序列化一次几百毫秒，「每次打开面板新建、关闭面板销毁」
     * 既让用户每次切入手写都干等模型加载，又迫使关闭路径在主线程等待 close()。
     * 现在与 fcitx daemon 同生命周期常驻：onAttached 后台预热，之后零成本。
     */
    private fun ensureRecognizer(): HandwrittenRecognizer? =
        HandwrittenRecognizer.get(context)

    override fun onCreateView(): View {
        val ctx = context
        val dp = { v: Int -> (v * ctx.resources.displayMetrics.density).toInt() }

        // 候选条：横向滚动。高度从旧版 64dp 略增到 72dp 以容纳"字+拼音"双行，
        // 但相比主流手写输入法同场景仍属窄条 —— 省下的高度全部给手写板。
        candidateRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(2), dp(10), dp(2))
        }
        candidateScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(
                candidateRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        // 提示行：识别前为书写引导；识别中为"识别中…"；识别后为首候选拼音（对齐主流手写输入法）；
        // 失败时红色显示原因。旧版独立状态行已合并至此。
        hintText = TextView(ctx).apply {
            textSize = 11f
            setTextColor(COLOR_HINT)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }

        // 手写板：weight=1 拿满剩余空间；四周留白 + 圆角（视觉抬升，主流手写输入法风格）
        board = HandwritingView(ctx).apply {
            setBackgroundColor(Color.WHITE)
            background = RoundedBg(dp(12))
            // v0.0.07：板子最小高度保护——总高度不足时优先压缩候选/提示区，
            // 板子低于 140dp 时笔画根本写不开（用户实测反馈）
            minimumHeight = dp(140)
            MbmDebug.markFeature(this, "handwriting.recognize")
            // 每次抬笔即识别(13 路 TTA 集成,单次推理几十毫秒级)
            onStrokeEndListener = {
                MbmDebug.logFeature("handwriting.recognize")
                recognize()
            }
        }

        // 右侧竖排快捷键：⌫ 顶部，下随常用标点（对齐主流手写输入法/主流手写输入法半屏手写）
        val rightCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(10), dp(6), dp(10))
        }
        val btnBack = makePunctButton(ctx, "⌫", 24f) {
            MbmDebug.logFeature("handwriting.backspace")
            sendAction(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace)))
        }.apply { MbmDebug.markFeature(this, "handwriting.backspace") }
        rightCol.addView(btnBack, colParams())
        listOf("，", "。", "？", "！").forEach { p ->
            rightCol.addView(
                makePunctButton(ctx, p, 18f) {
                    MbmDebug.logFeature("handwriting.punct", p)
                    sendAction(KeyAction.CommitAction(p))
                }.apply { MbmDebug.markFeature(this, "handwriting.punct") },
                colParams()
            )
        }

        // 中部：手写板（weight=1）+ 右侧快捷列
        val middleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                board,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dp(10), dp(8), dp(4), dp(8))
                }
            )
            addView(
                rightCol,
                LinearLayout.LayoutParams(dp(56), LinearLayout.LayoutParams.MATCH_PARENT)
            )
        }
        middleRowRef = middleRow

        // 底部功能行：清除 / 空格 / 换行 / 符号（统一圆角底色）
        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(2), dp(4), dp(6))
        }
        btnRow.addView(
            makeFuncButton(ctx, "清除") {
                MbmDebug.logFeature("handwriting.clear")
                board.clear()
                lastCandidates = emptyList()
                showEmptyHint()
            }.apply { MbmDebug.markFeature(this, "handwriting.clear") }, rowParams(dp)
        )
        btnRow.addView(
            makeFuncButton(ctx, "空格") {
                MbmDebug.logFeature("handwriting.space")
                // 有候选时选中首候选（与主流手写输入法一致），否则输入空格
                val first = lastCandidates.firstOrNull()
                if (first != null) {
                    commitCandidate(first.text)
                } else {
                    sendAction(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_space)))
                }
            }.apply { MbmDebug.markFeature(this, "handwriting.space") }, rowParams(dp)
        )
        btnRow.addView(
            makeFuncButton(ctx, "换行") {
                MbmDebug.logFeature("handwriting.enter")
                sendAction(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Return)))
            }.apply { MbmDebug.markFeature(this, "handwriting.enter") }, rowParams(dp)
        )
        btnRow.addView(
            makeFuncButton(ctx, "符号") {
                MbmDebug.logFeature("handwriting.symbol")
                // v0.0.06：改为面板内嵌符号网格（跨窗口 action 在手写窗口不生效）
                showSymbols()
            }.apply { MbmDebug.markFeature(this, "handwriting.symbol") }, rowParams(dp)
        )

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_PANEL_BG)
            addView(
                candidateScroll,
                LinearLayout.LayoutParams(
                    // v0.0.07：候选条 72dp -> 50dp，空间让给手写板（用户反馈板子太小）
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
                )
            )
            addView(
                hintText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(14)
                )
            )
            addView(
                middleRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            )
            symbolPanelRef = buildSymbolPanel(ctx).apply { visibility = View.GONE }
            addView(
                symbolPanelRef,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            )
            addView(btnRow)
        }
    }

    /** 圆角纯色背景（手写板悬浮感） */
    private class RoundedBg(radiusPx: Int) : android.graphics.drawable.GradientDrawable() {
        init {
            setColor(Color.WHITE)
            cornerRadius = radiusPx.toFloat()
        }
    }

    private fun colParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
    ).apply { topMargin = 8 }

    private fun makePunctButton(
        ctx: android.content.Context,
        label: String,
        sizeSp: Float,
        action: () -> Unit
    ) = Button(ctx).apply {
        text = label
        textSize = sizeSp
        setTextColor(Color.WHITE)
        setAllCaps(false)
        background = keyBg()
        setOnClickListener { action() }
    }

    private fun makeFuncButton(
        ctx: android.content.Context,
        label: String,
        action: () -> Unit
    ) = Button(ctx).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        setAllCaps(false)
        background = keyBg()
        setOnClickListener { action() }
    }

    /** 功能键圆角底色（主流手写输入法工具栏风格） */
    private fun keyBg() = GradientDrawable().apply {
        setColor(COLOR_KEY_BG)
        cornerRadius = dp8().toFloat()
    }

    private fun dp8() = (8 * context.resources.displayMetrics.density).toInt()

    private fun sendAction(action: KeyAction) {
        commonKeyActionListener.listener.onKeyAction(action, KeyActionListener.Source.Keyboard)
    }

    private fun rowParams(dp: (Int) -> Int) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(6)
        }

    private fun recognize() {
        val bmp = board.snapshot() ?: run {
            setHint("画布尚未就绪，请稍候再写", isError = true)
            return
        }
        job?.cancel()
        job = scope.launch {
            try {
                setHint("识别中…")
                // 词库懒加载：与识别同在后台线程，首次多几毫秒
                if (lexicon == null) {
                    lexicon = withContext(Dispatchers.Default) {
                        runCatching { HandwritingLexicon.get(context) }.getOrNull()
                    }
                }
                // 异常不再静默吞掉：把失败原因显示在提示行，同时写日志便于定位
                val outcome: Result<List<HandwrittenRecognizer.Candidate>> =
                    withTimeout(RECOGNIZE_TIMEOUT_MS) {
                        withContext(Dispatchers.Default) {
                            runCatching {
                                val rec = ensureRecognizer()
                                    ?: error("手写引擎初始化失败（模型或字表加载失败）")
                                val list = rec.recognize(bmp, TOP_K)
                                Timber.i(
                                    "MBM-HW: bitmap=%dx%d, candidates=%d",
                                    bmp.width, bmp.height, list.size
                                )
                                list
                            }
                        }
                    }
                outcome.onSuccess { list ->
                    if (list.isEmpty()) {
                        Timber.w("MBM-HW: empty result (画布为空或预处理未找到笔画)")
                        setHint("未识别到笔画，请重新书写", isError = true)
                        lastCandidates = emptyList()
                    } else {
                        lastCandidates = list
                        showCandidates(list)
                    }
                }.onFailure { e ->
                    Timber.e(e, "MBM-HW: recognition failed")
                    setHint("识别失败：${e.message}", isError = true)
                }
            } catch (e: TimeoutCancellationException) {
                // N75：识别超时兜底（模型卡死/超大画布），避免界面僵在「识别中…」
                Timber.w(e, "MBM-HW: recognition timed out")
                setHint("识别超时，请重试", isError = true)
            } catch (e: Throwable) {
                Timber.e(e, "MBM-HW: recognition failed")
                setHint("识别失败：${e.message}", isError = true)
            } finally {
                // N75：无论成功/失败/超时/被取消，画布副本都必须回收，
                // 否则异常或取消路径每写一笔泄漏一张全幅位图
                runCatching { bmp.recycle() }
            }
        }
    }

    /** 提示行：书写引导 / 识别中 / 首候选拼音 / 失败原因（红色）。
     *  clearCandidates=false 用于「显示拼音」场景 —— 不能把刚渲染好的候选条清空。 */
    private fun setHint(text: String, isError: Boolean = false, clearCandidates: Boolean = true) {
        if (clearCandidates) candidateRow.removeAllViews()
        hintText.text = text
        hintText.setTextColor(if (isError) COLOR_ERROR else COLOR_HINT)
    }

    private fun showEmptyHint() = setHint("请在此书写（一次写一个字）")

    /**
     * 候选渲染（对齐主流手写输入法）：前 4 个单字候选（首候选 26sp 加粗 + 拼音小字），
     * 其后追加首字的关联词（19sp）。全部横向滚动。
     */
    private fun showCandidates(list: List<HandwrittenRecognizer.Candidate>) {
        candidateRow.removeAllViews()
        val chars = list.take(CHAR_CANDIDATES)
        if (chars.isEmpty()) {
            showEmptyHint()
            return
        }
        chars.forEachIndexed { index, candidate ->
            candidateRow.addView(charCandidateView(candidate.text, index == 0))
        }
        // 首字关联词（如「岁」→ 岁数）：没有词库数据时不占位
        val words = lexicon?.wordsOf(chars[0].text[0])?.take(WORD_CANDIDATES).orEmpty()
        words.forEach { word ->
            candidateRow.addView(
                TextView(context).apply {
                    text = word
                    textSize = 19f
                    setTextColor(COLOR_CANDIDATE_WORD)
                    gravity = Gravity.CENTER
                    setPadding(dp(14), dp(4), dp(14), dp(4))
                    MbmDebug.markFeature(this, "handwriting.candidate")
                    setOnClickListener {
                        MbmDebug.logFeature("handwriting.candidate", word)
                        commitCandidate(word)
                    }
                }
            )
        }
        // 首候选拼音（无声调）显示在提示行，对齐主流手写输入法 "sui" 的位置
        val pinyin = lexicon?.pinyinOf(chars[0].text[0])
        setHint(pinyin?.takeIf { it.isNotBlank() } ?: "", clearCandidates = false)
    }

    /** 单字候选：字 + 拼音（双行垂直布局） */
    private fun charCandidateView(text: String, primary: Boolean): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(2), dp(14), dp(2))
            MbmDebug.markFeature(this, "handwriting.candidate")
            setOnClickListener {
                MbmDebug.logFeature("handwriting.candidate", text)
                commitCandidate(text)
            }
        }
        cell.addView(TextView(context).apply {
            this.text = text
            textSize = if (primary) 26f else 21f
            if (primary) setTypeface(null, Typeface.BOLD)
            setTextColor(if (primary) COLOR_CANDIDATE_PRIMARY else COLOR_CANDIDATE_CHAR)
        })
        val pinyin = lexicon?.pinyinOf(text[0])
        cell.addView(TextView(context).apply {
            this.text = pinyin ?: ""
            textSize = 10f
            setTextColor(COLOR_CANDIDATE_PINYIN)
            gravity = Gravity.CENTER
            visibility = if (pinyin.isNullOrBlank()) View.GONE else View.VISIBLE
        })
        return cell
    }

    private fun commitCandidate(text: String) {
        sendAction(KeyAction.CommitAction(text))
        board.clear()
        lastCandidates = emptyList()
        showEmptyHint()
    }

    /** 构建面板内嵌的符号视图：分类横滑切换 + 符号网格 + 返回手写 */
    private fun buildSymbolPanel(ctx: android.content.Context): LinearLayout {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_PANEL_BG)
        }
        val host = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        symbolGridHost = host
        root.addView(
            android.widget.ScrollView(ctx).apply { addView(host) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        // 分类切换条（复用 PickerData.Symbol：数字标点/括号/箭头/数学/序号/其他/全角）
        val tabRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        PickerData.Symbol.forEach { (cat, chars) ->
            tabRow.addView(
                makeFuncButton(ctx, cat.label) { renderSymbols(cat.label, chars) },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(dp(2), 0, dp(2), 0) }
            )
        }
        root.addView(
            HorizontalScrollView(ctx).apply { addView(tabRow) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(makeFuncButton(ctx, "返回手写") { showBoard() }, rowParams(::dp))
        renderSymbols(PickerData.Symbol.first().first.label, PickerData.Symbol.first().second)
        return root
    }

    /**
     * 渲染某个分类的符号网格（每行 8 个，宽度自适应）。
     * v0.0.10：按分类缓存整棵网格树——首次构建后存入 [symbolViews]，
     * 之后切换分类只是 removeAllViews + addView 缓存实例，主线程零 Button 创建。
     */
    private fun renderSymbols(key: String, chars: Array<String>) {
        val host = symbolGridHost ?: return
        host.removeAllViews()
        symbolViews[key]?.let {
            host.addView(it)
            return
        }
        val ctx = host.context
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        chars.toList().chunked(8).forEach { rowChars ->
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            rowChars.forEach { c ->
                row.addView(
                    Button(ctx).apply {
                        text = c
                        textSize = 17f
                        background = keyBg()
                        setOnClickListener { sendAction(KeyAction.CommitAction(c)) }
                    },
                    LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                        setMargins(dp(2), dp(2), dp(2), dp(2))
                    }
                )
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        symbolViews[key] = grid
        host.addView(grid)
    }

    private fun showSymbols() {
        middleRowRef?.visibility = View.GONE
        symbolPanelRef?.visibility = View.VISIBLE
        setHint("点击符号直接上屏（手写模型无符号，写出来也识别不了）")
    }

    private fun showBoard() {
        symbolPanelRef?.visibility = View.GONE
        middleRowRef?.visibility = View.VISIBLE
        showEmptyHint()
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    override fun onAttached() {
        showEmptyHint()
        // 词库 + 引擎预热：避免首次识别时候选缺拼音/关联词、模型还在加载
        scope.launch {
            if (lexicon == null) {
                lexicon = withContext(Dispatchers.Default) {
                    runCatching { HandwritingLexicon.get(context) }.getOrNull()
                }
            }
            // 引擎预热（后台加载 NCNN 模型，首次几百毫秒）：
            // 用户写第一笔时识别即可开始，无需干等；单例加载一次常驻
            withContext(Dispatchers.Default) { ensureRecognizer() }
        }
    }

    override fun onDetached() {
        job?.cancel()
        // v0.0.10：recognizer 是进程级单例，与 fcitx daemon 同生命周期常驻。
        // 早期在此 runBlocking 等待 close() —— 主线程干等 NCNN 关库，
        // 每次切回键盘都卡顿掉帧（用户报回「不能出现卡顿」）。现在只取消任务。
        scope.cancel()
    }

    companion object {
        /** 超高精度:取 top-10 候选供用户选字 */
        private const val TOP_K = 10

        /** 候选条展示的单字候选数（主流手写输入法同场景约 4~6 个） */
        private const val CHAR_CANDIDATES = 4

        /** 首字关联词展示数 */
        private const val WORD_CANDIDATES = 4

        private val COLOR_PANEL_BG = Color.parseColor("#FF12161C")
        private val COLOR_KEY_BG = Color.parseColor("#FF2A323C")
        private val COLOR_HINT = Color.parseColor("#FF8B949E")
        private val COLOR_ERROR = Color.rgb(0xE5, 0x53, 0x4B)
        private val COLOR_CANDIDATE_PRIMARY = Color.WHITE
        private val COLOR_CANDIDATE_CHAR = Color.parseColor("#FFC9D1D9")
        private val COLOR_CANDIDATE_WORD = Color.parseColor("#FFC9D1D9")
        private val COLOR_CANDIDATE_PINYIN = Color.parseColor("#FF6E7681")
    }
}
