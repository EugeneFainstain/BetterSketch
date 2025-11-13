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

abstract class MySlider @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var onValueChanged: ((Float) -> Unit)? = null
    var value: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val selectorPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    protected val isVertical: Boolean
        get() = height > width

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val defaultSize = (DEFAULT_SIZE_DP * resources.displayMetrics.density).roundToInt()
        val width = resolveSize(defaultSize, widthMeasureSpec)
        val height = resolveSize(defaultSize, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        drawBackground(canvas)
        drawSelector(canvas)
    }

    abstract fun drawBackground(canvas: Canvas)

    private fun drawSelector(canvas: Canvas) {
        val selectorRadius: Float
        if (isVertical) {
            val y = (1f - value) * height
            selectorRadius = (width / 2f) - selectorPaint.strokeWidth
            canvas.drawCircle(width / 2f, y, selectorRadius, selectorPaint)
        } else {
            val x = value * width
            selectorRadius = (height / 2f) - selectorPaint.strokeWidth
            canvas.drawCircle(x, height / 2f, selectorRadius, selectorPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val newValue = if (isVertical) {
                1f - (event.y / height).coerceIn(0f, 1f)
            } else {
                (event.x / width).coerceIn(0f, 1f)
            }
            if (newValue != value) {
                value = newValue
                onValueChanged?.invoke(value)
            }
        }
        return true
    }

    companion object {
        private const val DEFAULT_SIZE_DP = 48
    }
}
