package com.example.bettersketch

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

interface DrawingViewListener {
    fun onStateChanged()
    fun onLoupeUpdate(bitmap: Bitmap?)
    fun onSelectedEndChanged(selectedEnd: SelectedEnd)
}

enum class SelectedEnd {
    START, END, NONE
}

class DrawingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var listener: DrawingViewListener? = null
    var selectedEnd: SelectedEnd = SelectedEnd.NONE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
                listener?.onSelectedEndChanged(value)
            }
        }
    var isEditingMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // Drawing state
    private var backingBitmap: Bitmap? = null
    private var backingCanvas: Canvas? = null

    // Current tools
    private val currentPoints = mutableListOf<PathPoint>()
    private var currentDistance = 0f
    var currentPaint = defaultPaint(Color.BLACK, 12f)

    // History for undo/redo
    private val strokes = mutableListOf<Stroke>()
    private val undone = ArrayDeque<Stroke>()

    // Public properties for history state
    val canRewind: Boolean get() = strokes.isNotEmpty()
    val canFF: Boolean get() = undone.isNotEmpty()
    val historySize: Int get() = strokes.size + undone.size
    val currentHistoryPosition: Int get() = strokes.size
    val lastStroke: Stroke? get() = strokes.lastOrNull()

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
        // 1. Draw the cached history
        backingBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // 2. Draw the live stroke or the halo for the selected stroke
        if (currentPoints.isNotEmpty()) {
            // A stroke is actively being drawn
            if (isEditingMode) {
                drawStrokeWithHalo(canvas, currentPoints, currentPaint)
            } else {
                drawPoints(canvas, currentPoints, currentPaint)
            }
        } else if (isEditingMode) {
            // Not drawing, but in editing mode, so show halo on the last stroke
            strokes.lastOrNull()?.let {
                drawStrokeWithHalo(canvas, it.points, it.paint)
            }
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchStart(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                touchMove(x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchUp()
            }
        }
        invalidate()
        return true
    }

    private fun touchStart(x: Float, y: Float) {
        redrawHistory() // Redraw to remove halo from previous last stroke

        selectedEnd = SelectedEnd.END
        currentPoints.clear()
        currentDistance = 0f
        currentPoints.add(PathPoint(PointF(x, y), 0f))
        listener?.onStateChanged()
    }

    private fun touchMove(x: Float, y: Float) {
        if (currentPoints.isEmpty()) return

        val lastPoint = currentPoints.last().point
        val dx = x - lastPoint.x
        val dy = y - lastPoint.y
        val segmentLength = sqrt(dx * dx + dy * dy)
        currentDistance += segmentLength
        currentPoints.add(PathPoint(PointF(x, y), currentDistance))
        listener?.onStateChanged()
    }

    private fun touchUp() {
        if (currentPoints.isNotEmpty()) {
            selectedEnd = SelectedEnd.END
            val newStroke = Stroke(currentPoints.toMutableList(), Paint(currentPaint), currentDistance)
            strokes.add(newStroke)
            currentPoints.clear()

            redrawHistory()
            listener?.onStateChanged()
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
        listener?.onStateChanged()
    }

    fun deleteCurrentStroke() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    fun getStrokeColors(): IntArray {
        val pastColors = strokes.map { it.paint.color }
        val futureColors = undone.reversed().map { it.paint.color }
        return (pastColors + futureColors).toIntArray()
    }

    fun transformLastStroke(translateX: Float, translateY: Float, scale: Float, rotate: Float) {
        lastStroke?.let {
            val bounds = it.getBounds()
            val centerX = bounds.centerX()
            val centerY = bounds.centerY()

            val matrix = Matrix()
            matrix.postTranslate(translateX, translateY)
            matrix.postScale(scale, scale, centerX + translateX, centerY + translateY)
            matrix.postRotate(rotate, centerX + translateX, centerY + translateY)

            val pts = it.points.flatMap { listOf(it.point.x, it.point.y) }.toFloatArray()
            matrix.mapPoints(pts)

            for ((index, pathPoint) in it.points.withIndex()) {
                pathPoint.point.x = pts[index * 2]
                pathPoint.point.y = pts[index * 2 + 1]
            }

            redrawHistory()
            listener?.onStateChanged()
        }
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
            listener?.onStateChanged()
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
            listener?.onStateChanged()
        }
    }

    fun updateLoupes(loupeWidth: Int, loupeHeight: Int, selectedEnd: SelectedEnd) {
        var point: PointF? = null
        if (currentPoints.isNotEmpty()) {
            point = if (selectedEnd == SelectedEnd.START) currentPoints.first().point else currentPoints.last().point
        } else if (strokes.isNotEmpty()) {
            val lastStroke = strokes.last()
            point = if (selectedEnd == SelectedEnd.START) lastStroke.points.first().point else lastStroke.points.last().point
        }
        
        if (point != null) {
            listener?.onLoupeUpdate(createLoupeBitmap(point.x, point.y, loupeWidth, loupeHeight, true))
        } else {
            listener?.onLoupeUpdate(null)
        }
    }

    private fun createLoupeBitmap(px: Float, py: Float, loupeWidth: Int, loupeHeight: Int, isSelected: Boolean): Bitmap? {
        if (loupeWidth <= 0 || loupeHeight <= 0) return null

        val zoomFactor = 1f

        val tempBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val tempCanvas = Canvas(tempBitmap)
        draw(tempCanvas)

        backingBitmap?.let {
            val loupeBitmap = Bitmap.createBitmap(loupeWidth, loupeHeight, Bitmap.Config.ARGB_8888)
            val loupeCanvas = Canvas(loupeBitmap)

            loupeCanvas.save()
            loupeCanvas.scale(zoomFactor, zoomFactor)
            loupeCanvas.translate(-px + loupeWidth / (2 * zoomFactor), -py + loupeHeight / (2 * zoomFactor))

            loupeCanvas.drawBitmap(tempBitmap, 0f, 0f, null)

            loupeCanvas.restore()

            val borderPaint = Paint().apply {
                color = if (isSelected) Color.BLUE else Color.GRAY
                style = Paint.Style.STROKE
                strokeWidth = if (isSelected) 8f else 4f
            }
            loupeCanvas.drawRect(0f, 0f, loupeWidth.toFloat(), loupeHeight.toFloat(), borderPaint)

            return loupeBitmap
        }
        return null
    }

    private fun drawStrokeWithHalo(canvas: Canvas, points: List<PathPoint>, paint: Paint) {
        if (points.isEmpty()) return

        // 1. Draw the halo
        val haloPaint = Paint(paint).apply {
            color = Color.LTGRAY
            strokeWidth = paint.strokeWidth + 32f
        }
        drawPoints(canvas, points, haloPaint)

        // 2. Draw the endpoint indicator circles
        if (currentPoints.isNotEmpty()) {
            // Special case: Drawing in progress, highlight both ends
            val startPaint = Paint().apply { style = Paint.Style.FILL; color = Color.GREEN }
            val endPaint = Paint().apply { style = Paint.Style.FILL; color = Color.RED }
            val radius = haloPaint.strokeWidth / 2f
            canvas.drawCircle(points.first().point.x, points.first().point.y, radius, startPaint)
            canvas.drawCircle(points.last().point.x, points.last().point.y, radius, endPaint)
        } else if (selectedEnd != SelectedEnd.NONE) {
            // Normal case: Highlight only the selected end
            val endpointCirclePaint = Paint().apply {
                style = Paint.Style.FILL
                color = if (selectedEnd == SelectedEnd.START) Color.GREEN else Color.RED
            }
            val pointToHighlight = if (selectedEnd == SelectedEnd.START) points.first().point else points.last().point
            val radius = haloPaint.strokeWidth / 2f
            canvas.drawCircle(pointToHighlight.x, pointToHighlight.y, radius, endpointCirclePaint)
        }

        // 3. Draw the actual stroke on top
        drawPoints(canvas, points, paint)
    }

    fun setColor(color: Int, applyToLast: Boolean = false) {
        currentPaint.color = color
        if (applyToLast && strokes.isNotEmpty()) {
            strokes.last().paint.color = color
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    fun setStrokeWidth(px: Float, applyToLast: Boolean = false) {
        val w = max(1f, min(120f, px))
        currentPaint.strokeWidth = w
        if (applyToLast && strokes.isNotEmpty()) {
            strokes.last().paint.strokeWidth = w
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    fun undo() {
        if (canRewind) {
            navigateToHistoryState(currentHistoryPosition - 1)
        }
    }

    fun redo() {
        if (canFF) {
            navigateToHistoryState(currentHistoryPosition + 1)
        }
    }

    fun clearAll() {
        strokes.clear()
        undone.clear()
        currentPoints.clear()
        redrawHistory()
        listener?.onStateChanged()
    }

    private fun redrawHistory() {
        val c = backingCanvas ?: return
        c.drawColor(Color.WHITE, PorterDuff.Mode.SRC)
        val tempPaint = Paint()

        // Draw future strokes with 25% alpha
        for (s in undone.reversed()) {
            tempPaint.set(s.paint)
            val originalAlpha = tempPaint.alpha
            tempPaint.alpha = (originalAlpha * 0.25f).toInt()
            drawPoints(c, s.points, tempPaint)
        }

        // Draw past strokes at full opacity
        for (s in strokes) {
            drawPoints(c, s.points, s.paint)
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
