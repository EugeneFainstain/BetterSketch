package com.example.bettersketch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet

class SmoothingSlider @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : MySlider(context, attrs) {

    var color: Int = Color.BLACK
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint()
    private val path = Path()

    init {
        steps = 20
    }

    override fun drawBackground(canvas: Canvas) {
        paint.color = color
        paint.style = Paint.Style.FILL

        path.reset()
        if (isVertical) {
            path.moveTo(width / 2f, 0f)
            path.lineTo(0f, height.toFloat())
            path.lineTo(width.toFloat(), height.toFloat())
            path.close()
        } else {
            path.moveTo(0f, height / 2f)
            path.lineTo(width.toFloat(), 0f)
            path.lineTo(width.toFloat(), height.toFloat())
            path.close()
        }
        canvas.drawPath(path, paint)
    }
}
