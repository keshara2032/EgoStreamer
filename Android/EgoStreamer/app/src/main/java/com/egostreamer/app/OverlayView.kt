package com.egostreamer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Box(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val label: String,
        val score: Float
    )

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFFFFCC00.toInt()
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        style = Paint.Style.FILL
        textSize = 36f
        color = 0xFFFFFFFF.toInt()
        isAntiAlias = true
    }

    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = 0x80000000.toInt()
        isAntiAlias = true
    }

    private var boxes: List<Box> = emptyList()

    fun setBoxes(newBoxes: List<Box>) {
        boxes = newBoxes
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        for (box in boxes) {
            val left = clamp(box.x, 0f, 1f) * viewW
            val top = clamp(box.y, 0f, 1f) * viewH
            val right = clamp(box.x + box.w, 0f, 1f) * viewW
            val bottom = clamp(box.y + box.h, 0f, 1f) * viewH

            val rect = RectF(left, top, right, bottom)
            canvas.drawRect(rect, boxPaint)

            val label = if (box.label.isNotEmpty()) {
                "${box.label} ${(box.score * 100).toInt()}%"
            } else {
                "${(box.score * 100).toInt()}%"
            }
            val textWidth = textPaint.measureText(label)
            val textHeight = textPaint.textSize
            val bgRect = RectF(left, max(0f, top - textHeight - 8f), left + textWidth + 16f, top)
            canvas.drawRect(bgRect, bgPaint)
            canvas.drawText(label, left + 8f, top - 8f, textPaint)
        }
    }

    private fun clamp(value: Float, minVal: Float, maxVal: Float): Float {
        return min(maxVal, max(minVal, value))
    }
}
