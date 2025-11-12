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

    // Drawing state
    private var backingBitmap: Bitmap? = null
    private var backingCanvas: Canvas? = null

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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Initialize the backing bitmaps
        if (w > 0 && h > 0) {
            backingBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            backingCanvas = Canvas(backingBitmap!!)
            redrawHistory()
        } else {
            backingBitmap = null
            backingCanvas = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw the history from the bitmap
        backingBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
        }

        // Draw the current stroke-in-progress on top
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
        undone.clear()
        currentPath.reset()
        currentPath.moveTo(x, y)
        lastX = x
        lastY = y
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
        // Draw the just-finished stroke to the backing canvas
        backingCanvas?.drawPath(currentPath, currentPaint)

        // Add it to the history
        strokes.add(Stroke(Path(currentPath), Paint(currentPaint)))

        // Reset for the next one
        currentPath.reset()
    }

    fun setColor(color: Int) {
        currentPaint = defaultPaint(color, currentPaint.strokeWidth)
    }

    fun setStrokeWidth(px: Float) {
        val w = max(1f, min(120f, px))
        currentPaint.strokeWidth = w
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            undone.addLast(strokes.removeAt(strokes.lastIndex))
            redrawHistory()
        }
    }

    fun redo() {
        if (undone.isNotEmpty()) {
            strokes.add(undone.removeLast())
            redrawHistory()
        }
    }

    fun clearAll() {
        strokes.clear()
        undone.clear()
        currentPath.reset()
        redrawHistory()
    }

    private fun redrawHistory() {
        // Clear the backing canvas and redraw all the strokes
        backingCanvas?.let {
            it.drawColor(Color.WHITE, PorterDuff.Mode.SRC)
            for (s in strokes) {
                it.drawPath(s.path, s.paint)
            }
        }
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
