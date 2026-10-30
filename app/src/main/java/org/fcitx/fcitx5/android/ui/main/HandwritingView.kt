/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Mixed Bug Method Contributors
 */

package org.fcitx.fcitx5.android.ui.main

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 手写画布：白底黑笔，捕获多笔画。
 * 暴露 [getBitmap] 供识别器转为灰度输入；[clear] 清空。
 */
class HandwritingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 12f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private var bitmap: Bitmap? = null
    private var canvas: Canvas? = null
    private val path = Path()

    // N75：move 距离限流，过短的位移不入库/不重绘
    private var lastX = 0f
    private var lastY = 0f
    private val MIN_STEP = 2f

    /** 每次抬笔回调，用于触发自动识别 */
    var onStrokeEndListener: (() -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) resetCanvas(w, h)
    }

    private fun resetCanvas(w: Int, h: Int) {
        bitmap?.recycle()
        bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        canvas = Canvas(bitmap!!).apply { drawColor(Color.WHITE) }
        path.reset()
    }

    fun clear() {
        if (width > 0 && height > 0) {
            canvas?.drawColor(Color.WHITE)
            path.reset()
            invalidate()
        }
    }

    fun getBitmap(): Bitmap? = bitmap

    /**
     * 取当前画布快照。识别在后台线程读像素,而 UI 线程可能同时在新笔画上绘制,
     * Bitmap 本身不是线程安全的,故识别前先拷贝一份。
     */
    fun snapshot(): Bitmap? {
        val src = bitmap ?: return null
        return runCatching { Bitmap.createBitmap(src) }.getOrNull()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        canvas.drawPath(path, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 仅跟踪主指针（pointer 0），多指时不会把不同手指坐标乱接成一条线（N75）
                val idx = event.findPointerIndex(event.getPointerId(0))
                if (idx < 0) return true
                lastX = event.getX(idx)
                lastY = event.getY(idx)
                path.moveTo(lastX, lastY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(event.getPointerId(0))
                if (idx < 0) return true
                val x = event.getX(idx)
                val y = event.getY(idx)
                // N75：距离限流，过短的 move 不入库、不重绘，减少无谓 onDraw
                if (abs(x - lastX) >= MIN_STEP || abs(y - lastY) >= MIN_STEP) {
                    path.lineTo(x, y)
                    lastX = x
                    lastY = y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // N75：CANCEL（父视图拦截等）与 UP 同样结束本笔，避免整笔丢失
                canvas?.drawPath(path, paint)
                path.reset()
                onStrokeEndListener?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
