package com.example.bettersketch

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class HistoryIndicatorDrawable : Drawable() {

    private val paint = Paint().apply {
        strokeWidth = 2f
    }

    var strokeColors: IntArray = intArrayOf()
        set(value) {
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val numStrokes = strokeColors.size
        val numMarkers = numStrokes + 1 // Add one for the initial empty state
        val tickWidth = 16f

        if (numMarkers <= 1) {
            // If there are no strokes, just draw a single grey marker at the start
            paint.color = Color.LTGRAY
            val x = bounds.left.toFloat()
            canvas.drawRect(x - tickWidth / 2, bounds.top.toFloat(), x + tickWidth / 2, bounds.bottom.toFloat(), paint)
            return
        }

        val segmentWidth = bounds.width().toFloat() / numStrokes

        // Draw the first marker as gray for the initial empty state
        paint.color = Color.LTGRAY
        val startX = bounds.left.toFloat()
        canvas.drawRect(startX - tickWidth / 2, bounds.top.toFloat(), startX + tickWidth / 2, bounds.bottom.toFloat(), paint)

        // Draw a marker for each stroke with its color
        for (i in strokeColors.indices) {
            paint.color = strokeColors[i]
            val x = bounds.left + (i + 1) * segmentWidth
            canvas.drawRect(x - tickWidth / 2, bounds.top.toFloat(), x + tickWidth / 2, bounds.bottom.toFloat(), paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
