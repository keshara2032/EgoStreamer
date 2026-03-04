package com.egostreamer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
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

    data class Feedback(
        val protocol: String = "",
        val action: String = "",
        val assistance: String = ""
    )

    private val uvaBlue = ContextCompat.getColor(context, R.color.uva_blue)
    private val uvaOrange = ContextCompat.getColor(context, R.color.uva_orange)
    private val uvaBlueTransparent = (0xCC000000.toInt() or (uvaBlue and 0x00FFFFFF))

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = uvaOrange
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        style = Paint.Style.FILL
        textSize = 36f
        color = 0xFFFFFFFF.toInt()
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val bgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = uvaBlueTransparent
        isAntiAlias = true
    }

    private val feedbackTitlePaint = Paint().apply {
        style = Paint.Style.FILL
        textSize = 26f
        color = uvaOrange
        isAntiAlias = true
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val feedbackContentPaint = Paint().apply {
        style = Paint.Style.FILL
        textSize = 34f
        color = 0xFFFFFFFF.toInt()
        isAntiAlias = true
    }

    private var boxes: List<Box> = emptyList()
    private var feedback: Feedback? = null

    fun setBoxes(newBoxes: List<Box>) {
        boxes = newBoxes
        invalidate()
    }

    fun setFeedback(newFeedback: Feedback?) {
        feedback = newFeedback
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        // Draw Bounding Boxes
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
            val bgRect = RectF(left, max(0f, top - textHeight - 12f), left + textWidth + 20f, top)
            canvas.drawRect(bgRect, bgPaint)
            canvas.drawText(label, left + 10f, top - 10f, textPaint)
        }

        // Draw Feedback Elements
        drawFeedbackElements(canvas)
    }

    private fun drawFeedbackElements(canvas: Canvas) {
        val f = feedback ?: return
        val padding = 40f
        val boxWidth = width * 0.35f
        val boxHeight = 140f

        // Protocol: Top Left
        if (f.protocol.isNotBlank()) {
            drawInfoBox(canvas, padding, padding, boxWidth, boxHeight, "PROTOCOL", f.protocol)
        }

        // Action: Bottom Left
        // Adjusted Y position to stay above the new control panel which is roughly 200dp
        val controlPanelHeight = 250f * resources.displayMetrics.density
        if (f.action.isNotBlank()) {
            drawInfoBox(canvas, padding, height - padding - boxHeight - controlPanelHeight, boxWidth, boxHeight, "ACTION", f.action)
        }

        // Assistance: Top Right
        if (f.assistance.isNotBlank()) {
            drawInfoBox(canvas, width - padding - boxWidth, padding, boxWidth, boxHeight, "ASSISTANCE", f.assistance)
        }
    }

    private fun drawInfoBox(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, title: String, content: String) {
        val rect = RectF(x, y, x + w, y + h)
        
        // Background with slight corner radius
        canvas.drawRoundRect(rect, 24f, 24f, bgPaint)
        
        // Border
        val borderPaint = Paint(boxPaint).apply { strokeWidth = 3f }
        canvas.drawRoundRect(rect, 24f, 24f, borderPaint)

        canvas.drawText(title, x + 24f, y + 48f, feedbackTitlePaint)
        
        // Simple text wrapping if content is long
        val truncatedContent = if (content.length > 35) content.take(32) + "..." else content
        canvas.drawText(truncatedContent, x + 24f, y + 105f, feedbackContentPaint)
    }

    private fun clamp(value: Float, minVal: Float, maxVal: Float): Float {
        return min(maxVal, max(minVal, value))
    }
}
