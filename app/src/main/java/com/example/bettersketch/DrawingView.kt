package com.example.bettersketch

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

class DrawingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // Current tools
    private var currentPath = Path()
    private var currentPaint = defaultPaint(Color.BLACK, 12f)

    // History for undo/redo
    private val strokes = mutableListOf<Stroke>()
    private val undone = ArrayDeque<Stroke>()

    // Touch smoothing
    private var lastX = 0f
    private var lastY = 0f
    private val touchTolerance = 3f

    // Export bitmap helper
    fun exportBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        draw(c) // draw the view as-is
        return bmp
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // draw history
        for (s in strokes) {
            canvas.drawPath(s.path, s.paint)
        }
        // draw current stroke-in-progress
        canvas.drawPath(currentPath, currentPaint)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStart(x, y)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                touchMove(x, y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchUp()
                invalidate()
            }
        }
        return true
    }

    private fun touchStart(x: Float, y: Float) {
        // starting a brand-new path
        currentPath.reset()
        currentPath.moveTo(x, y)
        lastX = x
        lastY = y
        // new stroke will be added on ACTION_UP
    }

    private fun touchMove(x: Float, y: Float) {
        val dx = kotlin.math.abs(x - lastX)
        val dy = kotlin.math.abs(y - lastY)
        if (dx >= touchTolerance || dy >= touchTolerance) {
            // Quadratic smoothing
            currentPath.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f)
            lastX = x
            lastY = y
        }
    }

    private fun touchUp() {
        // complete the current stroke
        val finalPath = Path(currentPath) // copy
        val finalPaint = Paint(currentPaint) // copy
        strokes.add(Stroke(finalPath, finalPaint))
        currentPath.reset()
        undone.clear()
    }

    fun setColor(color: Int) {
        currentPaint = defaultPaint(color, currentPaint.strokeWidth)
        invalidate()
    }

    fun setStrokeWidth(px: Float) {
        val w = max(1f, min(120f, px))
        currentPaint.strokeWidth = w
        invalidate()
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            undone.addLast(strokes.removeAt(strokes.lastIndex))
            invalidate()
        }
    }

    fun redo() {
        if (undone.isNotEmpty()) {
            strokes.add(undone.removeLast())
            invalidate()
        }
    }

    fun clearAll() {
        strokes.clear()
        undone.clear()
        currentPath.reset()
        invalidate()
    }

    private fun defaultPaint(_color: Int, _widthPx: Float) = Paint().apply {
        isAntiAlias = true
        isDither = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = _color
        strokeWidth = _widthPx
    }
}
