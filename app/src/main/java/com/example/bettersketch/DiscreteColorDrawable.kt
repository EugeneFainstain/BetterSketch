package com.example.bettersketch

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class DiscreteColorDrawable(private val colors: IntArray) : Drawable() {

    private val paint = Paint()

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
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int {
        return if (paint.alpha == 255) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT
    }
}
