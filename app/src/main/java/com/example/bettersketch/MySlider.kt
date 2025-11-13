package com.example.bettersketch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.view.GestureDetectorCompat
import kotlin.math.roundToInt

abstract class MySlider @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface OnSliderValueChangedListener {
        fun onValueChanged(value: Float)
        fun onValueEdit(value: Float)
        fun onValueEditEnd()
    }

    var listener: OnSliderValueChangedListener? = null
    var value: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    var steps: Int = 0

    private var isEditing = false
    private val gestureDetector = GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            isEditing = true
            // We don't need to handle the value here,
            // onTouchEvent will do it.
            return true
        }
    })

    private val selectorPaint = Paint().apply {
        color = Color.GRAY
        style = Paint.Style.FILL
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
            val segmentHeight = if (steps > 1) height.toFloat() / steps else 0f
            val y = value * (height - segmentHeight) + (segmentHeight / 2f)
            selectorRadius = (width.toFloat() / 2f) * (2f / 3f)
            canvas.drawCircle(width / 2f, y, selectorRadius, selectorPaint)
        } else {
            val segmentWidth = if (steps > 1) width.toFloat() / steps else 0f
            val x = value * (width - segmentWidth) + (segmentWidth / 2f)
            selectorRadius = (height.toFloat() / 2f) * (2f / 3f)
            canvas.drawCircle(x, height / 2f, selectorRadius, selectorPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> handleValueChange(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isEditing) {
                    listener?.onValueEditEnd()
                }
                isEditing = false
            }
        }
        return true
    }

    private fun handleValueChange(event: MotionEvent) {
        var newValue = if (isVertical) {
            (event.y / height).coerceIn(0f, 1f)
        } else {
            (event.x / width).coerceIn(0f, 1f)
        }

        if (steps > 1) {
            val stepIndex = (newValue * (steps - 1)).roundToInt()
            newValue = stepIndex.toFloat() / (steps - 1)
        }

        if (isEditing) {
            value = newValue
            listener?.onValueEdit(value)
        } else if (newValue != value) {
            value = newValue
            listener?.onValueChanged(value)
        }
    }

    companion object {
        private const val DEFAULT_SIZE_DP = 32
    }
}
