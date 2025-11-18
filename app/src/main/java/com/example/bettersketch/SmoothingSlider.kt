package com.example.bettersketch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import kotlin.math.PI
import kotlin.math.sin

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
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.isAntiAlias = true

        path.reset()

        val w = width.toFloat()
        val h = height.toFloat()
        val halfHeight = h / 2f

        path.moveTo(0f, halfHeight)

        for (x in 0..width) {
            val xFloat = x.toFloat()
            // Calculate the angle for the sine wave, mapping x from [0, width] to [0, 2*PI]
            val angle = (xFloat / w) * (2 * PI)
            // Calculate the y-coordinate, ensuring all math is done with Floats after the sin() call
            val y = halfHeight + (sin(angle).toFloat() * halfHeight)
            path.lineTo(xFloat, y)
        }
        canvas.drawPath(path, paint)
    }
}
