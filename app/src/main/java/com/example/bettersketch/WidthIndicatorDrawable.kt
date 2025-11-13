package com.example.bettersketch

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

class WidthIndicatorDrawable : Drawable() {

    private val paint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val path = Path()
        val verticalMargin = bounds.height() * 0.2f // Leave 20% margin on top and bottom
        path.moveTo(canvas.clipBounds.left.toFloat(), bounds.exactCenterY())
        path.lineTo(canvas.clipBounds.right.toFloat(), bounds.top.toFloat() + verticalMargin)
        path.lineTo(canvas.clipBounds.right.toFloat(), bounds.bottom.toFloat() - verticalMargin)
        path.close()
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
