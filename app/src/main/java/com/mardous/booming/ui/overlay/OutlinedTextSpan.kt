package com.mardous.booming.ui.overlay

import android.graphics.Canvas
import android.graphics.Paint
import android.text.style.ReplacementSpan
import kotlin.math.roundToInt

internal class OutlinedTextSpan(
    private val textColor: Int
) : ReplacementSpan() {
    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int = paint.measureText(text, start, end).roundToInt()

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val oldColor = paint.color
        val oldStyle = paint.style
        val oldStrokeWidth = paint.strokeWidth
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = 0xCC000000.toInt()
        canvas.drawText(text, start, end, x, y.toFloat(), paint)
        paint.style = Paint.Style.FILL
        paint.strokeWidth = oldStrokeWidth
        paint.color = textColor
        canvas.drawText(text, start, end, x, y.toFloat(), paint)
        paint.color = oldColor
        paint.style = oldStyle
        paint.strokeWidth = oldStrokeWidth
    }
}


