/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * MBM: 离线手写取字工具(设置入口)。
 *
 * 说明:本 Activity 用于"取字/复制",真正边打字边手写请在键盘内使用
 * [org.fcitx.fcitx5.android.input.handwriting.HandwritingWindow](工具栏手写按钮)。
 * Activity 会抢走输入框焦点,因此这里只做识别 + 复制,不上屏。
 */
package org.fcitx.fcitx5.android.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.lib.handwritten.HandwrittenRecognizer

class HandwritingActivity : AppCompatActivity() {

    private lateinit var board: HandwritingView
    private lateinit var candidateRow: LinearLayout
    private val scope = CoroutineScope(Dispatchers.Main)
    private var job: Job? = null

    /**
     * 首次识别时才加载模型,避免在主线程读 assets + 载入 NCNN。
     * MBM v0.0.10：进程级单例（与键盘内 HandwritingWindow 共用同一实例）——
     * 全屏手写与键盘内手写来回切换不再反复加载/关闭 NCNN 模型；
     * onDestroy 不再 close（早期 close 会把另一入口正在用的实例关掉，native 崩溃）。
     */
    private fun ensureRecognizer(): HandwrittenRecognizer? =
        HandwrittenRecognizer.get(applicationContext)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        val title = TextView(this).apply {
            text = "离线手写识别"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        board = HandwritingView(this).apply {
            setBackgroundColor(Color.WHITE)
            // MBM v1.0.23：圆角 + 四周留白，与键盘内半屏手写一致的悬浮抬升观感
            background = object : android.graphics.drawable.GradientDrawable() {
                init {
                    setColor(Color.WHITE)
                    cornerRadius = dp(12).toFloat()
                }
            }
            onStrokeEndListener = { recognize() }
        }

        candidateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val candidateScroll = HorizontalScrollView(this).apply {
            addView(
                candidateRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val btnRecognize = Button(this).apply {
            text = "识别"
            setOnClickListener { recognize() }
        }
        val btnClear = Button(this).apply {
            text = "清除"
            setOnClickListener { board.clear(); candidateRow.removeAllViews() }
        }
        val btnClose = Button(this).apply {
            text = "关闭"
            setOnClickListener { finish() }
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnRecognize, rowParams(dp))
            addView(btnClear, rowParams(dp))
            addView(btnClose, rowParams(dp))
        }

        // MBM v0.0.08：横屏改「左板右控」布局。
        // 用户实测反馈横屏效果不佳——竖屏布局直接旋转后：屏幕矮，板子 weight 只剩一条，
        // 三个按钮却平铺全宽（每个按钮拉得超宽），高度全浪费在标题/候选条/按钮行上。
        // 横屏标准做法（主流手写输入法同款）：手写板占左侧大块方形区，右侧窄列放标题/候选/按钮。
        val isLandscape =
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val root = LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#FF0D1117"))
        }
        if (isLandscape) {
            root.orientation = LinearLayout.HORIZONTAL
            root.setPadding(dp(8), dp(8), dp(8), dp(8))
            // 左：手写板占满剩余宽与全高（横屏下拿到最大书写面积）
            root.addView(
                board,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(dp(8), dp(8), dp(8), dp(8))
                }
            )
            // 右：固定宽窄列 = 标题 + 候选（弹性）+ 按钮竖排
            val controls = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                addView(
                    title,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(6) }
                )
                addView(
                    candidateScroll,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
                )
                val vBtns = LinearLayout(this@HandwritingActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(btnRecognize, vRowParams(dp))
                    addView(btnClear, vRowParams(dp))
                    addView(btnClose, vRowParams(dp))
                }
                addView(
                    vBtns,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            root.addView(
                controls,
                LinearLayout.LayoutParams(dp(210), LinearLayout.LayoutParams.MATCH_PARENT)
            )
        } else {
            root.orientation = LinearLayout.VERTICAL
            root.setPadding(dp(12), dp(8), dp(12), dp(8))
            root.addView(
                title,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            )
            root.addView(
                board,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    // MBM v1.0.23：四周留白抬升（原方案贴边铺满，观感差于主流手写输入法）
                    setMargins(dp(16), dp(12), dp(16), dp(12))
                }
            )
            root.addView(
                candidateScroll,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(72))
            )
            root.addView(
                btnRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(root)
    }

    /** 横屏右列的竖排按钮参数 */
    private fun vRowParams(dp: (Int) -> Int) =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) }

    private fun rowParams(dp: (Int) -> Int) =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            rightMargin = dp(6)
        }

    private fun recognize() {
        val bmp = board.snapshot() ?: return
        job?.cancel()
        job = scope.launch {
            // snapshot() 返回画布副本：识别完必须回收，否则每写一笔泄漏一张全幅位图
            // （键盘内 HandwritingWindow 同源 bug 已于 v0.0.02 修复，此入口当时漏掉）
            val candidates = withContext(Dispatchers.Default) {
                try {
                    val rec = ensureRecognizer()
                        ?: return@withContext null
                    runCatching { rec.recognize(bmp, TOP_K) }.getOrNull()
                } finally {
                    runCatching { bmp.recycle() }
                }
            }
            if (candidates == null) {
                // 与 HandwritingWindow 对齐：模型加载失败要可见，不能谎称「未识别到笔画」
                Toast.makeText(
                    this@HandwritingActivity,
                    "手写引擎初始化失败（模型或字表加载失败）",
                    Toast.LENGTH_LONG
                ).show()
                candidateRow.removeAllViews()
                return@launch
            }
            showCandidates(candidates)
        }
    }

    private fun showCandidates(list: List<HandwrittenRecognizer.Candidate>) {
        candidateRow.removeAllViews()
        if (list.isEmpty()) {
            candidateRow.addView(TextView(this).apply {
                text = "未识别到笔画"
                setTextColor(Color.GRAY)
            })
            return
        }
        list.forEach { candidate ->
            val btn = Button(this).apply {
                text = candidate.text
                textSize = 20f
                setTextColor(Color.WHITE)
                setOnClickListener { copyText(candidate.text) }
            }
            candidateRow.addView(
                btn,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = (6 * resources.displayMetrics.density).toInt() }
            )
        }
    }

    private fun copyText(text: String) {
        ContextCompat.getSystemService(this, ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("handwriting", text))
        Toast.makeText(this, "已复制: $text", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
        job?.cancel()
        // v0.0.10：recognizer 为进程级单例，不在此 close（详见 ensureRecognizer 注释）
        scope.cancel()
    }

    companion object {
        /** MBM v1.0.36：当前前台实例引用——自测 settings 层点击进入后经它返回 */
        @Volatile
        var current: HandwritingActivity? = null
        /** 超高精度:取 top-10 候选 */
        private const val TOP_K = 10

        fun launch(context: Context) {
            context.startActivity(Intent(context, HandwritingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
