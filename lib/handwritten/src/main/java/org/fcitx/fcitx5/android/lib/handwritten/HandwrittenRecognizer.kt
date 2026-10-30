// SPDX-License-Identifier: AGPL-3.0-or-later
// SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors

package org.fcitx.fcitx5.android.lib.handwritten

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.shiyu.handwritten.runtime.HCCRRecognizer
import java.io.IOException

/**
 * 离线手写识别封装（基于 Ismantic/Handwritten，Apache-2.0）。
 *
 * - 完全离线，无网络依赖；模型与字表随 assets 打包。
 * - 默认返回 top-10 候选（top1≈95.5%，top10≈99.6%），追求超高精度。
 *
 * MBM v0.0.10：**进程级单例**。NCNN 载库 + 模型反序列化一次约几百毫秒，
 * 早期「每次打开手写面板新建、关闭面板销毁」的代价是：① 每次切入手写都要
 * 重新加载模型（首次识别前用户干等）；② 关闭面板时 close() 若在主线程等待
 * （早期 HandwritingWindow.onDetached 用 runBlocking）直接卡顿掉帧。
 * 现在与 fcitx daemon 同生命周期常驻：首次后台预热，之后零成本；
 * 不再提供主动 close 的调用路径（进程消亡自然释放）。
 */
class HandwrittenRecognizer private constructor(context: Context) : AutoCloseable {

    data class Candidate(val text: String, val probability: Float)

    private val recognizer: HCCRRecognizer = try {
        HCCRRecognizer.loadNativeLibrary("hccr")
        HCCRRecognizer(context.assets, PARAM, BIN, CHARSET)
    } catch (e: IOException) {
        throw IOException("手写识别引擎初始化失败: ${e.message}", e)
    }

    /** 灰度图识别：gray 为行优先 uint8，255=白底、0=笔画。返回 top-k 候选。 */
    fun recognize(gray: ByteArray, width: Int, height: Int, k: Int = 10): List<Candidate> {
        val input = FloatArray(HCCR_SIZE * HCCR_SIZE)
        if (!recognizer.preprocess(gray, width, height, input)) return emptyList()
        return recognizer.predict(input, k).map { Candidate(it.text, it.probability) }
    }

    /** Bitmap 识别：任意 ARGB_8888 位图，自动按亮度转灰度（白底黑笔）。 */
    fun recognize(bitmap: Bitmap, k: Int = 10): List<Candidate> {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val gray = ByteArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            gray[i] = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255).toByte()
        }
        return recognize(gray, w, h, k)
    }

    override fun close() = recognizer.close()

    companion object {
        private const val TAG = "HandwrittenRecognizer"

        const val PARAM = "model.ncnn.param"
        const val BIN = "model.ncnn.bin"
        const val CHARSET = "charset.json"
        const val HCCR_SIZE = 64

        @Volatile
        private var instance: HandwrittenRecognizer? = null

        /**
         * 进程内唯一实例。首次调用加载模型（几百毫秒），**必须在后台线程调用**；
         * 失败（assets 缺失/字表损坏/native 库 dlopen 失败）返回 null，
         * 由调用方决定降级与记日志。用 applicationContext，避免泄漏调用方 Context。
         *
         * v1.0.31a：失败时 Log.e 记录完整堆栈——此前 runCatching 静默吞掉，
         * 真机上手写面板只显示一句「模型或字表加载失败」，无法区分
         * dlopen 缺符号 / 模型损坏 / 字表解析失败，排障全靠猜。
         */
        fun get(context: Context): HandwrittenRecognizer? =
            instance ?: synchronized(this) {
                instance ?: runCatching { HandwrittenRecognizer(context.applicationContext) }
                    .onFailure { Log.e(TAG, "手写引擎初始化失败", it) }
                    .getOrNull()
                    ?.also { instance = it }
            }
    }
}
