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
     * @param ocrEnabled 用户 OCR 开关
     */
    suspend fun processBitmap(context: Context, bitmap: Bitmap, ocrEnabled: Boolean): Boolean {
        if (!ocrEnabled) return false
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

    /**
     * 截图区域裁剪（MBM v1.0.60 修复）。
     *
     * 旧实现裁剪掉屏幕底部约 45%、只保留顶部 55%——初衷是「避开 IME 键盘区域、避免重复识别键盘字符」。
     * 但这一刀切把屏幕上近一半的可见文字（中下部内容区、列表下半部、评论区、底部应用名等）永久排除在
     * OCR 之外，导致 Recall 屏幕记忆「大量词组没被识别」（用户实拍复现：B 站「我的」页的「创作中心 /
     * 稿件管理 / 游戏中心 / 游戏排行榜」、应用抽屉下半部的「Agent 工具箱 / AI 翻译」等全部落在被裁掉的区域）。
     *
     * 修复：仅裁剪屏幕最底部约 4% 的系统导航条（无任何 App 文字），其余整屏交给 OCR。理由：
     *  - Recall 本意即「屏幕所见即记忆」，裁剪与初衷相悖；
     *  - OCR 由三重门控约束（区域哈希去重 + inFlight 互斥 + 100ms 限频），静止屏近乎零开销，
     *    仅在内容变化帧触发全量推理，整屏 OCR 不会显著增耗；
     *  - 键盘区 / 系统导航的纯 ASCII（QWERTY 键帽、拼音串）会被分词层 [properNounTokens] 的
     *    「必须含汉字」约束过滤，不污染词库；键盘上的少量汉字标签（如「拼音」）本就是屏幕可见内容，纳入无害。
     */
    private fun cropTypingArea(src: Bitmap): Bitmap {
        val h = src.height
        val w = src.width
        if (h < 200) return src
        // 仅裁剪最底部约 4% 系统导航区域（无 App 文字），其余整屏 OCR。
        val safeMargin = (h * 0.04f).toInt().coerceAtLeast(1)
        val topH = (h - safeMargin).coerceAtLeast(1)
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
