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
    private val currentPoints = mutableListOf<PointF>()
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
        undone.clear()
        currentPoints.clear()
        currentPoints.add(PointF(x, y))
        updateLoupesForCurrentStroke()
    }

    private fun touchMove(x: Float, y: Float) {
        currentPoints.add(PointF(x, y))
        updateLoupesForCurrentStroke()
    }

    private fun touchUp() {
        if (currentPoints.isNotEmpty()) {
            val newStroke = Stroke(currentPoints.toMutableList(), Paint(currentPaint))
            strokes.add(newStroke)
            drawPoints(backingCanvas, newStroke.points, newStroke.paint)
            currentPoints.clear()
            updateLoupesFromLastStroke()
        }
    }

    fun moveStartPoint(dx: Float, dy: Float) {
        if (strokes.isNotEmpty()) {
            val lastStroke = strokes.last()
            val points = lastStroke.points
            val numPoints = points.size
            if (numPoints < 2) return

            for (i in 0 until numPoints) {
                val weight = 1.0f - (i.toFloat() / (numPoints - 1).toFloat())
                points[i].offset(dx * weight, dy * weight)
            }
            redrawHistory()
            updateLoupesFromLastStroke()
        }
    }

    fun moveEndPoint(dx: Float, dy: Float) {
        if (strokes.isNotEmpty()) {
            val lastStroke = strokes.last()
            val points = lastStroke.points
            val numPoints = points.size
            if (numPoints < 2) return

            for (i in 0 until numPoints) {
                val weight = i.toFloat() / (numPoints - 1).toFloat()
                points[i].offset(dx * weight, dy * weight)
            }
            redrawHistory()
            updateLoupesFromLastStroke()
        }
    }

    private fun updateLoupesForCurrentStroke() {
        if (currentPoints.isNotEmpty()) {
            val start = currentPoints.first()
            val end = currentPoints.last()
            loupeListener?.onStartLoupeUpdate(createLoupeBitmap(start.x, start.y))
            loupeListener?.onEndLoupeUpdate(createLoupeBitmap(end.x, end.y))
        }
    }

    private fun updateLoupesFromLastStroke() {
        if (strokes.isNotEmpty()) {
            val lastStroke = strokes.last()
            val start = lastStroke.points.first()
            val end = lastStroke.points.last()
            loupeListener?.onStartLoupeUpdate(createLoupeBitmap(start.x, start.y))
            loupeListener?.onEndLoupeUpdate(createLoupeBitmap(end.x, end.y))
        } else {
            loupeListener?.onStartLoupeUpdate(null)
            loupeListener?.onEndLoupeUpdate(null)
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
        currentPoints.clear()
        redrawHistory()
        updateLoupesFromLastStroke()
    }

    private fun redrawHistory() {
        backingCanvas?.let { it.drawColor(Color.WHITE, PorterDuff.Mode.SRC) }
        for (s in strokes) {
            drawPoints(backingCanvas, s.points, s.paint)
        }
        invalidate()
    }

    private fun drawPoints(canvas: Canvas?, points: List<PointF>, paint: Paint) {
        if (canvas == null || points.size < 2) return
        val path = Path()
        path.moveTo(points.first().x, points.first().y)
        for (i in 1 until points.size) {
            path.lineTo(points[i].x, points[i].y)
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