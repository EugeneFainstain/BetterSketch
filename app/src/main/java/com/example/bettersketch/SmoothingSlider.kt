package com.example.bettersketch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import kotlin.math.PI
import kotlin.math.pow
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
        paint.strokeWidth = 8f
        paint.isAntiAlias = true

        path.reset()

        val w = width.toFloat()
        val h = height.toFloat()
        val halfHeight = h / 2f
        val amplitude = halfHeight * 0.9f

        path.moveTo(0f, halfHeight)

        for (x in 0..width) {
            val xFloat = x.toFloat()
            // Normalize x to [0, 1]
            val normX = xFloat / w
            // Apply pow(0.1) to change the frequency.
            val phase = normX.pow(0.1f)
            // Calculate the angle for the sine wave, mapping the phase from [0, 1] to [0, 20 * 2*PI]
            val angle = phase * (20 * 2 * PI)
            // Calculate the y-coordinate
            val y = halfHeight + (sin(angle).toFloat() * amplitude)
            path.lineTo(xFloat, y)
        }
        canvas.drawPath(path, paint)
    }
}
