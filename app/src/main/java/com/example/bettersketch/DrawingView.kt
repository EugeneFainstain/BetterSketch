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
}

private enum class State {
    NORMAL_DRAWING,
    CHOSEN_STROKE,
    STROKE_EDITING
}

enum class SelectedEnd {
    START, END, NONE
}

class DrawingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs), CustomGestureDetector.OnGestureListener {

    var listener: DrawingViewListener? = null
    private var selectedEnd: SelectedEnd = SelectedEnd.NONE

    private var currentState = State.NORMAL_DRAWING
    private fun setState(newState: State) {
        if (currentState != newState) {
            currentState = newState
            redrawHistory()
            listener?.onStateChanged()
        }
    }

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

    private val customGestureDetector: CustomGestureDetector

    init {
        customGestureDetector = CustomGestureDetector(context, this)
    }

    // Public properties
    val canRewind: Boolean get() = strokes.isNotEmpty()
    val canFF: Boolean get() = undone.isNotEmpty()
    val historySize: Int get() = strokes.size + undone.size
    val currentHistoryPosition: Int get() = strokes.size
    val isStrokeSelected: Boolean get() = currentStrokeIdx != -1
    private val currentStroke: Stroke? get() = strokes.getOrNull(currentStrokeIdx)

    fun exportBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Temporarily set to normal drawing to export all strokes as opaque
        val oldState = currentState
        currentState = State.NORMAL_DRAWING
        redrawHistory(c)
        currentState = oldState
        return bmp
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            if (backingBitmap == null || w != backingBitmap!!.width || h != backingBitmap!!.height) {
                backingBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                backingCanvas = Canvas(backingBitmap!!)
            }
            redrawHistory()
        } else {
            backingBitmap = null
            backingCanvas = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()

        // 1. Draw the pre-rendered history from the bitmap
        backingBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // 2. Draw the "live" part (the new stroke being created) on top.
        if (currentState == State.NORMAL_DRAWING && strokeInProgressPoints.isNotEmpty()) {
            drawPoints(canvas, strokeInProgressPoints, currentPaint)
        }

        canvas.restore()
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        customGestureDetector.onTouchEvent(event)
        return true
    }

    private fun transformStroke(stroke: Stroke, matrix: Matrix) {
        val scale = getScaleFromMatrix(matrix)
        stroke.paint.strokeWidth *= scale
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
        strokeInProgressPoints.clear()
        strokeInProgressDistance = 0f
        strokeInProgressPoints.add(PathPoint(PointF(x, y), 0f))
    }

    private fun touchMove(x: Float, y: Float) {
        if (strokeInProgressPoints.isEmpty()) return
        val lastPoint = strokeInProgressPoints.last().point
        val dx = x - lastPoint.x
        val dy = y - lastPoint.y
        strokeInProgressDistance += sqrt(dx * dx + dy * dy)
        strokeInProgressPoints.add(PathPoint(PointF(x, y), strokeInProgressDistance))
        invalidate() // Redraw the live stroke
    }

    private fun touchUp() {
        if (strokeInProgressPoints.isNotEmpty()) {
            commitStrokeInProgress()
        }
    }

    private fun commitStrokeInProgress() {
        val newStroke = Stroke(strokeInProgressPoints.toMutableList(), Paint(currentPaint), strokeInProgressDistance)
        strokes.add(newStroke)
        strokeInProgressPoints.clear()
        redrawHistory()
        listener?.onStateChanged()
    }

    private fun distance(p1: PointF, p2: PointF): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun selectStrokeAt(tapPoint: PointF): Boolean {
        var minDistance = Float.MAX_VALUE
        var closestIndex = -1

        strokes.forEachIndexed { index, stroke ->
            for (pathPoint in stroke.points) {
                val d = distance(pathPoint.point, tapPoint)
                if (d < minDistance) {
                    minDistance = d
                    closestIndex = index
                }
            }
        }

        if (closestIndex != -1) {
            currentStrokeIdx = closestIndex
            currentPaint = Paint(strokes[closestIndex].paint)
            return true
        }
        return false
    }

    private fun selectEndpointOfCurrentStroke(tapPoint: PointF): Boolean {
        if (currentStrokeIdx == -1) return false
        val stroke = currentStroke ?: return false

        val startPoint = stroke.points.first().point
        val endPoint = stroke.points.last().point
        val distToStart = distance(startPoint, tapPoint)
        val distToEnd = distance(endPoint, tapPoint)

        selectedEnd = if (distToStart < distToEnd) SelectedEnd.START else SelectedEnd.END
        return true
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
        setState(State.NORMAL_DRAWING)
    }

    fun deleteCurrentStroke() {
        if (currentStrokeIdx != -1) {
            strokes.removeAt(currentStrokeIdx)
        } else if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
        }
        currentStrokeIdx = -1
        selectedEnd = SelectedEnd.NONE
        setState(State.NORMAL_DRAWING)
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
        }
    }

    private fun drawStrokeWithHalo(canvas: Canvas, points: List<PathPoint>, paint: Paint) {
        if (points.isEmpty()) return

        // 1. Draw the halo
        val haloPaint = Paint(paint).apply {
            color = Color.LTGRAY
            strokeWidth = paint.strokeWidth * 2 + 32f
        }
        drawPoints(canvas, points, haloPaint)
        
        // 2. Draw the actual stroke on top of the halo
        drawPoints(canvas, points, paint)

        // 3. Draw the endpoint indicator circles and highlight on top of everything
        val radius = (paint.strokeWidth * 2 + 32f) / 2f
        val startPoint = points.first().point
        val endPoint = points.last().point

        val startPaint = Paint().apply { style = Paint.Style.FILL; color = Color.GREEN }
        val endPaint = Paint().apply { style = Paint.Style.FILL; color = Color.RED }

        canvas.drawCircle(startPoint.x, startPoint.y, radius, startPaint)
        canvas.drawCircle(endPoint.x, endPoint.y, radius, endPaint)

        if (selectedEnd != SelectedEnd.NONE) {
            val highlightPaint = Paint().apply {
                style = Paint.Style.STROKE
                color = Color.CYAN
                strokeWidth = 8f
            }
            val pointToHighlight = if (selectedEnd == SelectedEnd.START) startPoint else endPoint
            canvas.drawCircle(pointToHighlight.x, pointToHighlight.y, radius + 6f, highlightPaint)
        }
    }

    fun setColor(color: Int, applyToSelected: Boolean) {
        currentPaint.color = color
        if (applyToSelected && currentStrokeIdx != -1) {
            currentStroke?.paint?.color = color
            redrawHistory()
        }
        listener?.onStateChanged()
    }

    fun setStrokeWidth(px: Float, applyToSelected: Boolean) {
        val w = max(1f, min(120f, px))
        currentPaint.strokeWidth = w
        if (applyToSelected && currentStrokeIdx != -1) {
            currentStroke?.paint?.strokeWidth = w
            redrawHistory()
        }
        listener?.onStateChanged()
    }

    fun undo() {
        if (canRewind) navigateToHistoryState(currentHistoryPosition - 1)
    }

    fun redo() {
        if (canFF) navigateToHistoryState(currentHistoryPosition + 1)
    }

    fun clearAll() {
        strokes.clear()
        undone.clear()
        strokeInProgressPoints.clear()
        currentStrokeIdx = -1
        selectedEnd = SelectedEnd.NONE
        setState(State.NORMAL_DRAWING)
        totalScale = 1.0f
    }

    private fun redrawHistory(canvas: Canvas? = null) {
        val c = canvas ?: backingCanvas ?: return
        if (canvas == null) {
            c.drawColor(Color.WHITE, PorterDuff.Mode.SRC)
        }

        val tempPaint = Paint()

        // Draw future strokes (always faded)
        for (s in undone.reversed()) {
            tempPaint.set(s.paint)
            tempPaint.alpha = (tempPaint.alpha * 0.25f).toInt()
            drawPoints(c, s.points, tempPaint)
        }

        // Draw past strokes
        for ((index, s) in strokes.withIndex()) {
            tempPaint.set(s.paint)
            
            if (currentState != State.NORMAL_DRAWING && index != currentStrokeIdx) {
                tempPaint.alpha = (tempPaint.alpha * 0.25f).toInt()
            }
            drawPoints(c, s.points, tempPaint)
        }
        
        // Draw the selected stroke's decorations on top if needed
        if (currentState == State.STROKE_EDITING) {
            currentStroke?.let {
                drawStrokeWithHalo(c, it.points, it.paint)
            }
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
        val tapPoint = PointF(event.x, event.y)
        when (currentState) {
            State.NORMAL_DRAWING -> {
                if (selectStrokeAt(tapPoint)) {
                    setState(State.CHOSEN_STROKE)
                }
            }
            State.CHOSEN_STROKE -> {
                if (selectEndpointOfCurrentStroke(tapPoint)) {
                    setState(State.STROKE_EDITING)
                }
                // If tap is not on an endpoint, do nothing.
            }
            State.STROKE_EDITING -> {
                if (!selectEndpointOfCurrentStroke(tapPoint)) {
                    setState(State.CHOSEN_STROKE)
                } else {
                    redrawHistory() // Redraw to update highlight if endpoint changes
                }
            }
        }
        return true
    }

    override fun onDoubleTap(event: MotionEvent): Boolean {
        when (currentState) {
            State.CHOSEN_STROKE, State.STROKE_EDITING -> {
                currentStrokeIdx = -1
                selectedEnd = SelectedEnd.NONE
                setState(State.NORMAL_DRAWING)
            }
            else -> {}
        }
        return true
    }

    override fun onFirstFingerDown(event: MotionEvent): Boolean {
        val downPoint = PointF(event.x, event.y)
        when (currentState) {
            State.NORMAL_DRAWING -> {
                touchStart(downPoint.x, downPoint.y)
            }
            State.CHOSEN_STROKE -> {
                if (selectEndpointOfCurrentStroke(downPoint)) {
                    setState(State.STROKE_EDITING)
                }
            }
            State.STROKE_EDITING -> {
                if (selectEndpointOfCurrentStroke(downPoint)) {
                    redrawHistory()
                }
            }
        }
        listener?.onStateChanged()
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
        when (currentState) {
            State.NORMAL_DRAWING -> {
                if (strokeInProgressPoints.isNotEmpty()) {
                    touchUp()
                }
            }
            State.STROKE_EDITING -> {
                setState(State.CHOSEN_STROKE)
            }
            else -> {}
        }
        if (isTransforming) {
            redrawHistory()
            isTransforming = false
        }
        return true
    }

    override fun onSingleFingerDrag(event: MotionEvent, dx: Float, dy: Float): Boolean {
        when (currentState) {
            State.NORMAL_DRAWING -> touchMove(event.x, event.y)
            State.STROKE_EDITING -> {
                if (selectedEnd == SelectedEnd.START) {
                    moveStartPoint(dx, dy)
                } else if (selectedEnd == SelectedEnd.END) {
                    moveEndPoint(dx, dy)
                }
            }
            else -> {}
        }
        return true
    }

    override fun onTwoFingerDrag(event: MotionEvent, dx: Float, dy: Float, scale: Float, rotate: Float): Boolean {
        val deltaMatrix = Matrix()
        when (currentState) {
            State.NORMAL_DRAWING -> {
                totalScale *= scale
                val mid = PointF((event.getX(0) + event.getX(1)) / 2f, (event.getY(0) + event.getY(1)) / 2f)
                deltaMatrix.postTranslate(dx, dy)
                deltaMatrix.postScale(scale, scale, mid.x, mid.y)
                deltaMatrix.postRotate(rotate, mid.x, mid.y)
                transformAllStrokes(deltaMatrix)
            }
            State.CHOSEN_STROKE, State.STROKE_EDITING -> {
                currentStroke?.let {
                    val bounds = it.getBounds()
                    val centerX = bounds.centerX()
                    val centerY = bounds.centerY()
                    deltaMatrix.postScale(scale, scale, centerX, centerY)
                    deltaMatrix.postRotate(rotate, centerX, centerY)
                    deltaMatrix.postTranslate(dx, dy)
                    transformStroke(it, deltaMatrix)
                }
            }
        }
        return true
    }

    override fun onTapAndAHalf(event: MotionEvent): Boolean {
        currentStrokeIdx = -1
        selectedEnd = SelectedEnd.NONE
        setState(State.NORMAL_DRAWING)
        return true
    }
}
