package com.example.bettersketch

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

interface LoupeListener {
    fun onStartLoupeUpdate(bitmap: Bitmap?)
    fun onEndLoupeUpdate(bitmap: Bitmap?)
    fun onRedoHistoryDecisionRequired()
    fun onCurrentStrokeWidthChanged(width: Float)
    fun onCurrentColorChanged(color: Int)
    fun onHistoryChanged(size: Int)
}

class DrawingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var loupeListener: LoupeListener? = null

    // Drawing state
    private var backingBitmap: Bitmap? = null
    private var backingCanvas: Canvas? = null
    private var insertMode = false

    // Current tools
    private val currentPoints = mutableListOf<PathPoint>()
    private var currentDistance = 0f
    private var currentPaint = defaultPaint(Color.BLACK, 12f)

    // History for undo/redo
    private val strokes = mutableListOf<Stroke>()
    private val undone = ArrayDeque<Stroke>()

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
        backingBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        drawPoints(canvas, currentPoints, currentPaint)
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
        if (undone.isNotEmpty() && !insertMode) {
            loupeListener?.onRedoHistoryDecisionRequired()
            return // Absorb the touch; wait for the user's decision.
        }

        currentPoints.clear()
        currentDistance = 0f
        currentPoints.add(PathPoint(PointF(x, y), 0f))
        updateLoupesForCurrentStroke()
    }

    private fun touchMove(x: Float, y: Float) {
        if (currentPoints.isEmpty()) return // Don't draw if we're waiting for a decision

        val lastPoint = currentPoints.last().point
        val dx = x - lastPoint.x
        val dy = y - lastPoint.y
        val segmentLength = sqrt(dx * dx + dy * dy)
        currentDistance += segmentLength
        currentPoints.add(PathPoint(PointF(x, y), currentDistance))
        updateLoupesForCurrentStroke()
    }

    private fun touchUp() {
        if (currentPoints.isNotEmpty()) {
            val newStroke = Stroke(currentPoints.toMutableList(), Paint(currentPaint), currentDistance)
            strokes.add(newStroke)
            drawPoints(backingCanvas, newStroke.points, newStroke.paint)
            currentPoints.clear()

            if (!insertMode) {
                undone.clear()
            }
            insertMode = false // Always reset after a stroke is complete
            updateUiFromLastStroke()
            loupeListener?.onHistoryChanged(strokes.size)
        }
    }

    fun navigateToHistoryState(index: Int) {
        while (strokes.size > index) {
            undone.addLast(strokes.removeAt(strokes.lastIndex))
        }
        while (strokes.size < index) {
            if (undone.isNotEmpty()) {
                strokes.add(undone.removeLast())
            } else {
                break
            }
        }
        redrawHistory()
        updateUiFromLastStroke()
    }

    fun clearRedoHistory() {
        undone.clear()
    }

    fun prepareToInsertStroke() {
        insertMode = true
    }

    fun moveStartPoint(dx: Float, dy: Float) {
        if (strokes.isNotEmpty()) {
            val lastStroke = strokes.last()
            val totalDistance = lastStroke.totalDistance
            if (totalDistance == 0f) return // Avoid division by zero

            for (pathPoint in lastStroke.points) {
                val weight = 1.0f - (pathPoint.distance / totalDistance)
                pathPoint.point.offset(dx * weight, dy * weight)
            }
            redrawHistory()
            updateUiFromLastStroke()
        }
    }

    fun moveEndPoint(dx: Float, dy: Float) {
        if (strokes.isNotEmpty()) {
            val lastStroke = strokes.last()
            val totalDistance = lastStroke.totalDistance
            if (totalDistance == 0f) return // Avoid division by zero

            for (pathPoint in lastStroke.points) {
                val weight = pathPoint.distance / totalDistance
                pathPoint.point.offset(dx * weight, dy * weight)
            }
            redrawHistory()
            updateUiFromLastStroke()
        }
    }

    private fun updateLoupesForCurrentStroke() {
        if (currentPoints.isNotEmpty()) {
            val start = currentPoints.first().point
            val end = currentPoints.last().point
            loupeListener?.onStartLoupeUpdate(createLoupeBitmap(start.x, start.y))
            loupeListener?.onEndLoupeUpdate(createLoupeBitmap(end.x, end.y))
        }
    }

    private fun updateUiFromLastStroke() {
        if (strokes.isNotEmpty()) {
            val lastStroke = strokes.last()
            val start = lastStroke.points.first().point
            val end = lastStroke.points.last().point
            loupeListener?.onStartLoupeUpdate(createLoupeBitmap(start.x, start.y))
            loupeListener?.onEndLoupeUpdate(createLoupeBitmap(end.x, end.y))
            loupeListener?.onCurrentStrokeWidthChanged(lastStroke.paint.strokeWidth)
            loupeListener?.onCurrentColorChanged(lastStroke.paint.color)
        } else {
            loupeListener?.onStartLoupeUpdate(null)
            loupeListener?.onEndLoupeUpdate(null)
            loupeListener?.onCurrentStrokeWidthChanged(currentPaint.strokeWidth)
            loupeListener?.onCurrentColorChanged(currentPaint.color)
        }
    }

    private fun createLoupeBitmap(px: Float, py: Float): Bitmap? {
        val loupeSize = 200
        val zoomFactor = 1f
        backingBitmap?.let {
            val loupeBitmap = Bitmap.createBitmap(loupeSize, loupeSize, Bitmap.Config.ARGB_8888)
            val loupeCanvas = Canvas(loupeBitmap)
            loupeCanvas.save()
            loupeCanvas.scale(zoomFactor, zoomFactor)
            loupeCanvas.translate(-px + loupeSize / (2 * zoomFactor), -py + loupeSize / (2 * zoomFactor))
            loupeCanvas.drawBitmap(it, 0f, 0f, null)
            drawPoints(loupeCanvas, currentPoints, currentPaint)
            loupeCanvas.restore()
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

    fun setColor(color: Int, applyToLast: Boolean = false) {
        currentPaint.color = color
        if (applyToLast && strokes.isNotEmpty()) {
            strokes.last().paint.color = color
            redrawHistory()
            updateUiFromLastStroke()
        }
    }

    fun setStrokeWidth(px: Float, applyToLast: Boolean = false) {
        val w = max(1f, min(120f, px))
        currentPaint.strokeWidth = w
        if (applyToLast && strokes.isNotEmpty()) {
            strokes.last().paint.strokeWidth = w
            redrawHistory()
            updateUiFromLastStroke()
        }
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            undone.addLast(strokes.removeAt(strokes.lastIndex))
            redrawHistory()
            updateUiFromLastStroke()
            loupeListener?.onHistoryChanged(strokes.size)
        }
    }

    fun redo() {
        if (undone.isNotEmpty()) {
            strokes.add(undone.removeLast())
            redrawHistory()
            updateUiFromLastStroke()
            loupeListener?.onHistoryChanged(strokes.size)
        }
    }

    fun clearAll() {
        strokes.clear()
        undone.clear()
        currentPoints.clear()
        redrawHistory()
        updateUiFromLastStroke()
        loupeListener?.onHistoryChanged(strokes.size)
    }

    private fun redrawHistory() {
        backingCanvas?.let { it.drawColor(Color.WHITE, PorterDuff.Mode.SRC) }
        for (s in strokes) {
            drawPoints(backingCanvas, s.points, s.paint)
        }
        invalidate()
    }

    private fun drawPoints(canvas: Canvas?, points: List<PathPoint>, paint: Paint) {
        if (canvas == null || points.size < 2) return
        val path = Path()
        path.moveTo(points.first().point.x, points.first().point.y)
        for (i in 1 until points.size) {
            path.lineTo(points[i].point.x, points[i].point.y)
        }
        canvas.drawPath(path, paint)
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
