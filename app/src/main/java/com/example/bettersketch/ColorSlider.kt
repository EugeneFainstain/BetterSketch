package com.example.bettersketch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

class ColorSlider @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onColorSelected: ((Int) -> Unit)? = null
    var colors: IntArray = intArrayOf()
        set(value) {
            field = value
            invalidate()
        }
    var selectedColor: Int = Color.BLACK
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint()
    private val borderPaint = Paint().apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val selectorPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultSize = (DEFAULT_SIZE_DP * resources.displayMetrics.density).roundToInt()
        val width = resolveSize(defaultSize, widthMeasureSpec)
        val height = resolveSize(defaultSize, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        if (colors.isEmpty()) return

        val isVertical = height > width
        if (isVertical) {
            drawVertical(canvas)
        } else {
            drawHorizontal(canvas)
        }
    }

    private fun drawHorizontal(canvas: Canvas) {
        val segmentWidth = width.toFloat() / colors.size
        var selectedX = -1f
        for ((index, color) in colors.withIndex()) {
            paint.color = color
            val left = index * segmentWidth
            val right = left + segmentWidth
            canvas.drawRect(left, 0f, right, height.toFloat(), paint)
            if (color == Color.WHITE) {
                canvas.drawRect(left, 0f, right, height.toFloat(), borderPaint)
            }
            if (color == selectedColor) {
                selectedX = left + segmentWidth / 2
            }
        }
        if (selectedX != -1f) {
            val radius = (height.toFloat() / 2f) - (selectorPaint.strokeWidth)
            canvas.drawCircle(selectedX, height / 2f, radius, selectorPaint)
        }
    }

    private fun drawVertical(canvas: Canvas) {
        val segmentHeight = height.toFloat() / colors.size
        var selectedY = -1f
        for ((index, color) in colors.withIndex()) {
            paint.color = color
            val top = index * segmentHeight
            val bottom = top + segmentHeight
            canvas.drawRect(0f, top, width.toFloat(), bottom, paint)
            if (color == Color.WHITE) {
                canvas.drawRect(0f, top, width.toFloat(), bottom, borderPaint)
            }
            if (color == selectedColor) {
                selectedY = top + segmentHeight / 2
            }
        }
        if (selectedY != -1f) {
            val radius = (width.toFloat() / 2f) - (selectorPaint.strokeWidth)
            canvas.drawCircle(width / 2f, selectedY, radius, selectorPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (colors.isEmpty()) return super.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val index = if (height > width) {
                (event.y / height * colors.size).toInt()
            } else {
                (event.x / width * colors.size).toInt()
            }
            val safeIndex = index.coerceIn(0, colors.size - 1)
            val newColor = colors[safeIndex]
            if (newColor != selectedColor) {
                selectedColor = newColor
                onColorSelected?.invoke(selectedColor)
            }
        }
        return true
    }

    companion object {
        private const val DEFAULT_SIZE_DP = 48
    }
}
