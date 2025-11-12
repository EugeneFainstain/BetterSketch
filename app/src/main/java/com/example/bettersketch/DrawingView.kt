package com.example.bettersketch

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

interface LoupeListener {
    fun onStartLoupeUpdate(bitmap: Bitmap?)
    fun onEndLoupeUpdate(bitmap: Bitmap?)
}

class DrawingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var loupeListener: LoupeListener? = null

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
    private var startX = 0f
    private var startY = 0f
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
        startX = x
        startY = y
        updateLoupesForCurrentStroke()
    }

    private fun touchMove(x: Float, y: Float) {
        val dx = kotlin.math.abs(x - lastX)
        val dy = kotlin.math.abs(y - lastY)
        if (dx >= touchTolerance || dy >= touchTolerance) {
            // Quadratic smoothing
            currentPath.quadTo(lastX, lastY, (x + lastX) / 2f, (y + lastY) / 2f)
            lastX = x
            lastY = y
            updateLoupesForCurrentStroke()
        }
    }

    private fun touchUp() {
        // Draw the just-finished stroke to the backing canvas
        backingCanvas?.drawPath(currentPath, currentPaint)

        // Add it to the history
        strokes.add(Stroke(Path(currentPath), Paint(currentPaint), startX, startY, lastX, lastY))

        // Reset for the next one
        currentPath.reset()

        // Update loupes to show the stroke we just finished
        updateLoupesFromLastStroke()
    }

    private fun updateLoupesForCurrentStroke() {
        loupeListener?.onStartLoupeUpdate(createLoupeBitmap(startX, startY))
        loupeListener?.onEndLoupeUpdate(createLoupeBitmap(lastX, lastY))
    }

    private fun updateLoupesFromLastStroke() {
        if (strokes.isNotEmpty()) {
            val lastStroke = strokes.last()
            loupeListener?.onStartLoupeUpdate(createLoupeBitmap(lastStroke.startX, lastStroke.startY))
            loupeListener?.onEndLoupeUpdate(createLoupeBitmap(lastStroke.endX, lastStroke.endY))
        } else {
            loupeListener?.onStartLoupeUpdate(null)
            loupeListener?.onEndLoupeUpdate(null)
        }
    }

    private fun createLoupeBitmap(px: Float, py: Float): Bitmap? {
        val loupeSize = 200 // The dimensions of the loupe bitmap in pixels
        val zoomFactor = 1f

        backingBitmap?.let {
            val loupeBitmap = Bitmap.createBitmap(loupeSize, loupeSize, Bitmap.Config.ARGB_8888)
            val loupeCanvas = Canvas(loupeBitmap)

            loupeCanvas.save()
            loupeCanvas.scale(zoomFactor, zoomFactor)
            loupeCanvas.translate(-px + loupeSize / (2 * zoomFactor), -py + loupeSize / (2 * zoomFactor))

            // Draw the history and current stroke
            loupeCanvas.drawBitmap(it, 0f, 0f, null)
            loupeCanvas.drawPath(currentPath, currentPaint)

            loupeCanvas.restore()

            // Draw a border
            val borderPaint = Paint().apply {
                color = Color.GRAY
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            loupeCanvas.drawRect(0f, 0f, loupeSize.toFloat(), loupeSize.toFloat(), borderPaint)

            return loupeBitmap
        }
        return null
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
            updateLoupesFromLastStroke()
        }
    }

    fun redo() {
        if (undone.isNotEmpty()) {
            strokes.add(undone.removeLast())
            redrawHistory()
            updateLoupesFromLastStroke()
        }
    }

    fun clearAll() {
        strokes.clear()
        undone.clear()
        currentPath.reset()
        redrawHistory()
        updateLoupesFromLastStroke()
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
