package com.example.bettersketch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet

class ColorSlider @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : MySlider(context, attrs) {

    var colors: IntArray = intArrayOf()
        set(value) {
            field = value
            steps = value.size
            invalidate()
        }

    private val paint = Paint()
    private val borderPaint = Paint().apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    override fun drawBackground(canvas: Canvas) {
        if (colors.isEmpty()) return

        if (isVertical) {
            drawVertical(canvas)
        } else {
            drawHorizontal(canvas)
        }
    }

    private fun drawHorizontal(canvas: Canvas) {
        val segmentWidth = width.toFloat() / colors.size
        for ((index, color) in colors.withIndex()) {
            paint.color = color
            val left = index * segmentWidth
            val right = left + segmentWidth
            canvas.drawRect(left, 0f, right, height.toFloat(), paint)
            if (color == Color.WHITE) {
                canvas.drawRect(left, 0f, right, height.toFloat(), borderPaint)
            }
        }
    }

    private fun drawVertical(canvas: Canvas) {
        val segmentHeight = height.toFloat() / colors.size
        for ((index, color) in colors.withIndex()) {
            paint.color = color
            val top = index * segmentHeight
            val bottom = top + segmentHeight
            canvas.drawRect(0f, top, width.toFloat(), bottom, paint)
            if (color == Color.WHITE) {
                canvas.drawRect(0f, top, width.toFloat(), bottom, borderPaint)
            }
        }
    }
}
