package com.example.bettersketch

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class HistoryIndicatorDrawable : Drawable() {

    private val paint = Paint().apply {
        color = Color.LTGRAY
        strokeWidth = 2f
    }

    var numMarkers = 0
        set(value) {
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (numMarkers <= 1) return

        val segmentWidth = bounds.width().toFloat() / (numMarkers - 1)

        for (i in 0 until numMarkers) {
            val x = bounds.left + i * segmentWidth
            canvas.drawLine(x, bounds.top.toFloat(), x, bounds.bottom.toFloat(), paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
