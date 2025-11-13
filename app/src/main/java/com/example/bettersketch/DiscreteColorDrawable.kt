package com.example.bettersketch

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class DiscreteColorDrawable(private val colors: IntArray) : Drawable() {

    private val paint = Paint()
    private val separatorPaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val borderPaint = Paint().apply {
        color = Color.GRAY
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val numColors = colors.size
        if (numColors == 0) return

        // The "-1" is needed to compensate the extension beyond the bounds by 1/2 from each side
        val segmentWidth = bounds.width().toFloat() / (numColors.toFloat() - 1)

        for (i in 0 until numColors) {
            paint.color = colors[i]

            val left = bounds.left + i * segmentWidth
            val right = left + segmentWidth

            // Offset the drawing by half a segment to center the stops
            val adjustedLeft = left - segmentWidth / 2f
            val adjustedRight = right - segmentWidth / 2f

            canvas.drawRect(adjustedLeft, bounds.top.toFloat(), adjustedRight, bounds.bottom.toFloat(), paint)

            // Draw a border around the white swatch to make it visible
            if (colors[i] == Color.WHITE) {
                canvas.drawRect(adjustedLeft, bounds.top.toFloat(), adjustedRight, bounds.bottom.toFloat(), borderPaint)
            }

            // Draw separator line after each color block (except the last one)
            if (i < numColors - 1) {
                canvas.drawLine(adjustedRight, bounds.top.toFloat(), adjustedRight, bounds.bottom.toFloat(), separatorPaint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        separatorPaint.alpha = alpha
        borderPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        separatorPaint.colorFilter = colorFilter
        borderPaint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int {
        return PixelFormat.TRANSLUCENT
    }
}
