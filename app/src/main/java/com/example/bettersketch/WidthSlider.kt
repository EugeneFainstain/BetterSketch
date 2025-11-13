package com.example.bettersketch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet

class WidthSlider @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : MySlider(context, attrs) {

    private val paint = Paint()

    override fun drawBackground(canvas: Canvas) {
        val shader = if (isVertical) {
            LinearGradient(0f, 0f, 0f, height.toFloat(), Color.LTGRAY, Color.BLACK, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, 0f, width.toFloat(), 0f, Color.LTGRAY, Color.BLACK, Shader.TileMode.CLAMP)
        }
        paint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }
}
