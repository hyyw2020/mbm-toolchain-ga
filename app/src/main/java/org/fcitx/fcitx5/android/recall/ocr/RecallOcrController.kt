/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 *
 * OCR 兜底层（冻结方案 OCR）：截图 → 区域 diff → 打字区裁剪 → 离线 OCR → 文本回灌。
 *
 *  - 仅在节点树取不到文字时启用（Canvas / 游戏 / 图片化内容），不替代主路径；
 *  - 区域 diff：对裁剪区做轻量哈希，未变化的静止屏不重复 OCR（省电省算力）；
 *  - 打字区裁剪：裁剪掉屏幕底部约 45%（IME 键盘区域），避免重复识别键盘字符；
 *  - 双开关：ocrEnabled 与 API≥34（无障碍自有 takeScreenshot 能力）任一不满足即不触发；
 *  - 有界：同一时刻仅一笔 OCR 在飞（AtomicBoolean CAS），超时/失败自动复位，不卡死。
 */
package org.fcitx.fcitx5.android.recall.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import org.fcitx.fcitx5.android.recall.RecallConfig
import org.fcitx.fcitx5.android.recall.RecallPolicy
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

class RecallOcrController(private val onText: (String) -> Unit) {

    private val inFlight = AtomicBoolean(false)
    @Volatile
    private var lastOcrAt = 0L
    @Volatile
    private var lastRegionHash = 0

    /**
     * 处理一次截图（位图）。返回 true 表示发起了 OCR 并处理了文本。
     * 位图所有权归本方法：返回 / 抛异常前统一回收裁剪产物（P0-1 修复：提前返回路径
     * 也必须 recycle，否则静止屏每帧泄漏一块 native 位图，最终打掉 IME 进程）。
     * @param isPrivate 当前窗口是否整屏隐私（是则直接跳过 OCR 兜底，P0-2 修复：不再硬编码 false）
     * @param ocrEnabled 用户 OCR 开关
     */
    suspend fun processBitmap(context: Context, bitmap: Bitmap, isPrivate: Boolean, ocrEnabled: Boolean): Boolean {
        if (!ocrEnabled || isPrivate) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastOcrAt < RecallConfig.OCR_MIN_INTERVAL_MS) return false
        if (!inFlight.compareAndSet(false, true)) return false
        lastOcrAt = now
        // 裁剪产物（createBitmap/createScaledBitmap 均为新对象）由本块独占，
        // 无论 return / 异常路径都在 finally 无条件回收，杜绝 native 堆泄漏。
        val cropped = cropTypingArea(bitmap)
        try {
            val regionHash = cheapHash(cropped)
            if (regionHash == lastRegionHash) {
                Timber.d("MBM OCR: region unchanged, skip")
                return false
            }
            lastRegionHash = regionHash

            val lines = OcrEngine.recognize(context, cropped, true)
            val joined = lines.joinToString("\n") { it.text }.trim()
            if (joined.isBlank()) return false
            onText(joined)
            return true
        } catch (t: Throwable) {
            Timber.w(t, "MBM OCR process failed")
            return false
        } finally {
            if (cropped !== bitmap && !cropped.isRecycled) cropped.recycle()
            inFlight.set(false)
        }
    }

    /** 裁剪屏幕底部约 45%（IME 键盘区域），只保留上方内容区。 */
    private fun cropTypingArea(src: Bitmap): Bitmap {
        val h = src.height
        val w = src.width
        if (h < 200) return src
        val topH = (h * 0.55f).toInt().coerceAtLeast(1)
        return try {
            Bitmap.createBitmap(src, 0, 0, w, topH)
        } catch (t: Throwable) {
            Timber.w(t, "MBM OCR crop failed")
            src
        }
    }

    /** 轻量级区域哈希：缩小到 24×24 后逐像素异或累加，用于静止屏去重。 */
    private fun cheapHash(src: Bitmap): Int {
        val size = 24
        val small = if (src.width == size && src.height == size) src
        else Bitmap.createScaledBitmap(src, size, size, true)
        val pixels = IntArray(size * size)
        small.getPixels(pixels, 0, size, 0, 0, size, size)
        if (small !== src) small.recycle()
        var h = 0x811c9dc5.toInt()
        for (p in pixels) {
            h = h xor (p and 0xFFFF)
            h *= 0x01000193
        }
        return h
    }
}
