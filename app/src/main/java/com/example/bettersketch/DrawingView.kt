package com.example.bettersketch

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

interface DrawingViewListener {
    fun onStateChanged()
}

enum class SelectedEnd {
    START, END, NONE
}

enum class StrokesDrawingMethod {
    DrawAllOpaque,
    DrawAllOpaqueExceptCurrent,
    DrawOpaqueUpToCurrent
}

class DrawingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs), CustomGestureDetector.OnGestureListener {

    var listener: DrawingViewListener? = null
    var selectedEnd: SelectedEnd = SelectedEnd.NONE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }
    var isEditingMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var currentStrokeDrawHalo: Boolean = false
    var currentStrokeDrawEndpoints: Boolean = false
    var strokesDrawingMethod: StrokesDrawingMethod = StrokesDrawingMethod.DrawAllOpaque

    // Drawing state
    private var backingBitmap: Bitmap? = null
    private var backingCanvas: Canvas? = null

    // Stroke in progress
    private val strokeInProgressPoints = mutableListOf<PathPoint>()
    private var strokeInProgressDistance = 0f
    var currentPaint = defaultPaint(Color.BLACK, 12f)

    // History for undo/redo
    private val strokes = mutableListOf<Stroke>()
    private val undone = ArrayDeque<Stroke>()
    private var currentStrokeIdx: Int = -1

    // Transformation state
    private var totalScale = 1.0f
    private var isTransforming = false

    // Touch state for drag calculations
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val customGestureDetector: CustomGestureDetector

    init {
        customGestureDetector = CustomGestureDetector(context, this)
    }


    // Public properties for history state
    val canRewind: Boolean get() = strokes.isNotEmpty()
    val canFF: Boolean get() = undone.isNotEmpty()
    val historySize: Int get() = strokes.size + undone.size
    val currentHistoryPosition: Int get() = strokes.size
    private val currentStroke: Stroke? get() = strokes.getOrNull(currentStrokeIdx)

    // Export bitmap helper
    fun exportBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        draw(c)
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
        canvas.save()

        // 1. Draw the cached history
        if (isTransforming) {
            redrawHistory(canvas, totalScale)
        } else {
            backingBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }

        // 2. Draw the live part
        if (strokeInProgressPoints.isNotEmpty()) {
            if (isEditingMode) {
                drawStrokeWithHalo(canvas, strokeInProgressPoints, currentPaint)
            } else {
                drawPoints(canvas, strokeInProgressPoints, currentPaint)
            }
        } else {
            currentStroke?.let {
                drawStrokeWithHalo(canvas, it.points, it.paint)
            }
        }
        
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        customGestureDetector.onTouchEvent(event)

        // Multi-touch handling (still separate for now, but will be simplified)
        val pointerCount = event.pointerCount
        if (pointerCount >= 2) {
            // This part of multi-touch handling will be moved to CustomGestureDetector
            // For now, it's here to ensure functionality
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL || (event.actionMasked == MotionEvent.ACTION_POINTER_UP && pointerCount == 2)) {
                if(isTransforming) {
                    redrawHistory()
                    isTransforming = false
                }
            }
        }
        
        invalidate()
        return true
    }
    
    // handleMultiTouch method is now removed, its logic is in CustomGestureDetector.onTwoFingerDrag

    private fun transformStroke(stroke: Stroke, matrix: Matrix) {
        val scale = getScaleFromMatrix(matrix)
        stroke.paint.strokeWidth = stroke.paint.strokeWidth * scale
        stroke.points.forEach { pathPoint ->
            val point = floatArrayOf(pathPoint.point.x, pathPoint.point.y)
            matrix.mapPoints(point)
            pathPoint.point.set(point[0], point[1])
        }
        redrawHistory()
    }
    
    private fun transformAllStrokes(matrix: Matrix) {
        (strokes + undone).forEach { stroke ->
            transformStroke(stroke, matrix)
        }
    }

    private fun getScaleFromMatrix(matrix: Matrix): Float {
        val values = FloatArray(9)
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun touchStart(x: Float, y: Float) {
        currentStrokeIdx = -1
        selectedEnd = SelectedEnd.NONE
        strokeInProgressPoints.clear()
        strokeInProgressDistance = 0f
        strokeInProgressPoints.add(PathPoint(PointF(x, y), 0f))
        listener?.onStateChanged()
    }

    private fun touchMove(x: Float, y: Float) {
        if (strokeInProgressPoints.isEmpty()) return
        val lastPoint = strokeInProgressPoints.last().point
        val dx = x - lastPoint.x
        val dy = y - lastPoint.y
        strokeInProgressDistance += sqrt(dx * dx + dy * dy)
        strokeInProgressPoints.add(PathPoint(PointF(x, y), strokeInProgressDistance))
        listener?.onStateChanged()
    }

    private fun touchUp() {
        if (strokeInProgressPoints.isNotEmpty()) {
            commitStrokeInProgress()
        }
    }

    private fun commitStrokeInProgress() {
        val newStroke = Stroke(strokeInProgressPoints.toMutableList(), Paint(currentPaint), strokeInProgressDistance)
        strokes.add(newStroke)
        currentStrokeIdx = -1
        selectedEnd = SelectedEnd.NONE
        strokeInProgressPoints.clear()
        redrawHistory()
        listener?.onStateChanged()
    }
    
    private fun distance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun angle(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()).toDouble()).toFloat()
    }
    
    private fun midpoint(event: MotionEvent): PointF {
        val x = (event.getX(0) + event.getX(1)) / 2f
        val y = (event.getY(0) + event.getY(1)) / 2f
        return PointF(x,y)
    }

    fun findClosestStroke(tapPoint: PointF) {
        var minDistance = Float.MAX_VALUE
        var closestIndex = -1

        strokes.forEachIndexed { index, stroke ->
            for (pathPoint in stroke.points) {
                val dx = pathPoint.point.x - tapPoint.x
                val dy = pathPoint.point.y - tapPoint.y
                val distance = sqrt(dx * dx + dy * dy)
                if (distance < minDistance) {
                    minDistance = distance
                    closestIndex = index
                }
            }
        }

        if (closestIndex != -1) {
            currentStrokeIdx = closestIndex
            val closestStroke = strokes[closestIndex]
            val startPoint = closestStroke.points.first().point
            val endPoint = closestStroke.points.last().point
            val distToStart = sqrt((startPoint.x - tapPoint.x) * (startPoint.x - tapPoint.x) + (startPoint.y - tapPoint.y) * (startPoint.y - tapPoint.y))
            val distToEnd = sqrt((endPoint.x - tapPoint.x) * (endPoint.x - tapPoint.x) + (endPoint.y - tapPoint.y) * (endPoint.y - tapPoint.y))
            
            selectedEnd = if (distToStart < distToEnd) SelectedEnd.START else SelectedEnd.END
            
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    fun deselectAllStrokes() {
        currentStrokeIdx = -1
        selectedEnd = SelectedEnd.NONE
        listener?.onStateChanged()
    }

    fun resetStrokeSelection() {
        selectedEnd = SelectedEnd.END
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
        currentStrokeIdx = -1
        selectedEnd = SelectedEnd.NONE
        redrawHistory()
        listener?.onStateChanged()
    }

    fun deleteCurrentStroke() {
        if (currentStrokeIdx != -1) {
            strokes.removeAt(currentStrokeIdx)
            currentStrokeIdx = -1
            selectedEnd = SelectedEnd.NONE
        } else if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
        }
        redrawHistory()
        listener?.onStateChanged()
    }

    fun getStrokeColors(): IntArray {
        val pastColors = strokes.map { it.paint.color }
        val futureColors = undone.reversed().map { it.paint.color }
        return (pastColors + futureColors).toIntArray()
    }

    fun moveStartPoint(dx: Float, dy: Float) {
        currentStroke?.let {
            val totalDistance = it.totalDistance
            if (totalDistance == 0f) return 
            for (pathPoint in it.points) {
                val weight = 1.0f - (pathPoint.distance / totalDistance)
                pathPoint.point.offset(dx * weight, dy * weight)
            }
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    fun moveEndPoint(dx: Float, dy: Float) {
        currentStroke?.let {
            val totalDistance = it.totalDistance
            if (totalDistance == 0f) return 
            for (pathPoint in it.points) {
                val weight = pathPoint.distance / totalDistance
                pathPoint.point.offset(dx * weight, dy * weight)
            }
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    private fun drawStrokeWithHalo(canvas: Canvas, points: List<PathPoint>, paint: Paint) {
        if (points.isEmpty()) return

        if (currentStrokeDrawHalo) {
            // 1. Draw the halo
            val haloPaint = Paint(paint).apply {
                color = Color.LTGRAY
                strokeWidth = paint.strokeWidth * 2 + 32f
            }
            drawPoints(canvas, points, haloPaint)
        }

        // 2. Draw the endpoint indicator circles
        if (currentStrokeDrawEndpoints) {
            // Check if we are drawing a new stroke in edit mode, or if a completed stroke is selected
            if (strokeInProgressPoints.isNotEmpty() && isEditingMode) {
                // Special case: Drawing a new stroke while in edit mode.
                val startPaint = Paint().apply { style = Paint.Style.FILL; color = Color.GREEN }
                val endPaint = Paint().apply { style = Paint.Style.FILL; color = Color.RED }
                val radius = (paint.strokeWidth * 2 + 32f) / 2f
                canvas.drawCircle(points.first().point.x, points.first().point.y, radius, startPaint)
                canvas.drawCircle(points.last().point.x, points.last().point.y, radius, endPaint)
            } else if (currentStrokeIdx != -1) { // Check if a stroke is selected
                // Normal case: A completed stroke is selected.
                val endpointCirclePaint = Paint().apply {
                    style = Paint.Style.FILL
                    color = if (selectedEnd == SelectedEnd.START) Color.GREEN else Color.RED
                }
                val pointToHighlight = if (selectedEnd == SelectedEnd.START) points.first().point else points.last().point
                val radius = (paint.strokeWidth * 2 + 32f) / 2f
                canvas.drawCircle(pointToHighlight.x, pointToHighlight.y, radius, endpointCirclePaint)
            }
        }
        
        // 3. Draw the actual stroke on top
        drawPoints(canvas, points, paint)
    }

    fun setColor(color: Int, applyToSelected: Boolean = false) {
        currentPaint.color = color
        if (applyToSelected) {
            currentStroke?.paint?.color = color
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    fun setStrokeWidth(px: Float, applyToSelected: Boolean = false) {
        val w = max(1f, min(120f, px))
        currentPaint.strokeWidth = w
        if (applyToSelected) {
            currentStroke?.paint?.strokeWidth = w
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
        strokeInProgressPoints.clear()
        currentStrokeIdx = -1
        selectedEnd = SelectedEnd.NONE
        totalScale = 1.0f
        redrawHistory()
        listener?.onStateChanged()
    }

    private fun redrawHistory(canvas: Canvas? = null, scale: Float = 1.0f) {
        val c = canvas ?: backingCanvas ?: return
        if (canvas == null) {
            c.drawColor(Color.WHITE, PorterDuff.Mode.SRC)
        }
        
        val tempPaint = Paint()

        // Draw future strokes (always faded)
        for (s in undone.reversed()) {
            tempPaint.set(s.paint)
            if(canvas != null) tempPaint.strokeWidth = s.paint.strokeWidth
            tempPaint.alpha = (tempPaint.alpha * 0.25f).toInt()
            drawPoints(c, s.points, tempPaint)
        }

        // Draw past strokes
        for ((index, s) in strokes.withIndex()) {
            tempPaint.set(s.paint)
            if(canvas != null) tempPaint.strokeWidth = s.paint.strokeWidth
            
            when (strokesDrawingMethod) {
                StrokesDrawingMethod.DrawAllOpaque -> { /* Do nothing */ }
                StrokesDrawingMethod.DrawAllOpaqueExceptCurrent -> {
                    if (index != currentStrokeIdx) {
                        tempPaint.alpha = (tempPaint.alpha * 0.25f).toInt()
                    }
                }
                StrokesDrawingMethod.DrawOpaqueUpToCurrent -> {
                    // For now, treat as DrawAllOpaque to maintain functionality
                }
            }

            drawPoints(c, s.points, tempPaint)
        }

        if (canvas == null) invalidate()
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

    override fun onSingleTap(event: MotionEvent): Boolean {
        findClosestStroke(PointF(event.x, event.y))
        return true
    }

    override fun onDoubleTap(event: MotionEvent): Boolean {
        deselectAllStrokes()
        return true
    }

    override fun onFirstFingerDown(event: MotionEvent): Boolean {
        lastTouchX = event.x
        lastTouchY = event.y
        if (!isEditingMode) {
            touchStart(event.x, event.y)
        }
        return true
    }

    override fun onSecondFingerDown(event: MotionEvent): Boolean {
        if (strokeInProgressPoints.isNotEmpty()) {
            if (strokeInProgressPoints.size > 5) {
                commitStrokeInProgress()
            } else {
                strokeInProgressPoints.clear()
            }
        }
        isTransforming = true
        return true
    }

    override fun onSomeFingerUp(event: MotionEvent): Boolean {
        return true
    }

    override fun onLastFingerUp(event: MotionEvent): Boolean {
        if(isTransforming) {
            redrawHistory()
            isTransforming = false
        }
        if (!isEditingMode && strokeInProgressPoints.isNotEmpty()) {
            touchUp()
        }
        return true
    }

    override fun onSingleFingerDrag(event: MotionEvent, dx: Float, dy: Float): Boolean {
        if (isEditingMode) {
            if (selectedEnd == SelectedEnd.START) {
                moveStartPoint(dx, dy)
            } else if (selectedEnd == SelectedEnd.END) {
                moveEndPoint(dx, dy)
            }
        } else {
            touchMove(event.x, event.y)
        }
        return true
    }

    override fun onTwoFingerDrag(event: MotionEvent, dx: Float, dy: Float, scale: Float, rotate: Float): Boolean {
        val deltaMatrix = Matrix()
        if (isEditingMode && currentStrokeIdx != -1) { // Check currentStrokeIdx instead of selectedEnd != SelectedEnd.NONE
            currentStroke?.let {
                val bounds = it.getBounds()
                val centerX = bounds.centerX()
                val centerY = bounds.centerY()
                deltaMatrix.postScale(scale, scale, centerX, centerY) // Corrected pivot
                deltaMatrix.postRotate(rotate, centerX, centerY) // Corrected pivot
                deltaMatrix.postTranslate(dx, dy) // Apply translation last
                transformStroke(it, deltaMatrix)
            }
        } else {
            totalScale *= scale
            deltaMatrix.postTranslate(dx, dy)
            deltaMatrix.postScale(scale, scale, midpoint(event).x, midpoint(event).y)
            deltaMatrix.postRotate(rotate, midpoint(event).x, midpoint(event).y)
            transformAllStrokes(deltaMatrix)
        }
        return true
    }

    override fun onTapAndAHalf(event: MotionEvent): Boolean {
        deselectAllStrokes() // Default action for now
        return true
    }
}
