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

    // Data
    private val strokes = mutableListOf<Stroke>()
    private var selectedStrokeIdx: Int = -1

    // Transformation state
    private var twoFingerGestureOccured = false
    private var threeFingerGestureOccured = false

    private val customGestureDetector: CustomGestureDetector

    init {
        customGestureDetector = CustomGestureDetector(context, this)
    }

    // Public properties
    val isStrokeSelected: Boolean get() = selectedStrokeIdx != -1
    private val currentStroke: Stroke? get() = strokes.getOrNull(selectedStrokeIdx)

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
        strokes.forEach { stroke ->
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
        selectedStrokeIdx = -1
        setState(State.NORMAL_DRAWING)
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
            selectedStrokeIdx = closestIndex
            currentPaint = Paint(strokes[closestIndex].paint)
            return true
        }
        return false
    }

    private fun selectEndpointOfCurrentStroke(tapPoint: PointF): Boolean {
        if (selectedStrokeIdx == -1) return false
        val stroke = currentStroke ?: return false

        val startPoint = stroke.points.first().point
        val endPoint = stroke.points.last().point
        val distToStart = distance(startPoint, tapPoint)
        val distToEnd = distance(endPoint, tapPoint)

        selectedEnd = if (distToStart < distToEnd) SelectedEnd.START else SelectedEnd.END
        return true
    }

    fun deleteCurrentStroke() {
        if (selectedStrokeIdx != -1) {
            strokes.removeAt(selectedStrokeIdx)
        } else if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
        }
        selectedStrokeIdx = -1
        setState(State.NORMAL_DRAWING)
        redrawHistory()
    }

    fun getStrokeColors(): IntArray {
        return strokes.map { it.paint.color }.toIntArray()
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

    private fun drawStrokeWithEndpoints(canvas: Canvas, points: List<PathPoint>, paint: Paint) {
        if (points.isEmpty()) return

        // During a two-finger drag, we don't want to see the endpoints at all.
        if (twoFingerGestureOccured && !threeFingerGestureOccured) {
            drawPoints(canvas, points, paint)
            return
        }

        // 1. Draw the endpoint indicator circles FIRST
        val radius = paint.strokeWidth * 2f
        val startPoint = points.first().point
        val endPoint = points.last().point

        val endpointPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.GREEN
        }

        // If we are three-finger dragging, draw both endpoints.
        if (threeFingerGestureOccured) {
            canvas.drawCircle(startPoint.x, startPoint.y, radius, endpointPaint)
            canvas.drawCircle(endPoint.x, endPoint.y, radius, endpointPaint)
        } else { // Otherwise, it's a single-finger drag on an endpoint
            if (selectedEnd == SelectedEnd.START) {
                canvas.drawCircle(startPoint.x, startPoint.y, radius, endpointPaint)
            } else {
                canvas.drawCircle(endPoint.x, endPoint.y, radius, endpointPaint)
            }
        }

        // 2. Draw the actual stroke on TOP of the circles
        drawPoints(canvas, points, paint)
    }

    fun setColor(color: Int, applyToSelected: Boolean) {
        currentPaint.color = color
        if (applyToSelected && selectedStrokeIdx != -1) {
            currentStroke?.paint?.color = color
            redrawHistory()
        }
        listener?.onStateChanged()
    }

    fun setStrokeWidth(px: Float, applyToSelected: Boolean) {
        val w = max(1f, min(120f, px))
        currentPaint.strokeWidth = w
        if (applyToSelected && selectedStrokeIdx != -1) {
            currentStroke?.paint?.strokeWidth = w
            redrawHistory()
        }
        listener?.onStateChanged()
    }

    fun clearAll() {
        strokes.clear()
        selectedStrokeIdx = -1
        setState(State.NORMAL_DRAWING)
    }

    private fun redrawHistory(canvas: Canvas? = null) {
        val c = canvas ?: backingCanvas ?: return
        if (canvas == null) {
            c.drawColor(Color.WHITE, PorterDuff.Mode.SRC)
        }

        val tempPaint = Paint()
        for ((index, s) in strokes.withIndex()) {
            tempPaint.set(s.paint)
            if (selectedStrokeIdx != -1 && index != selectedStrokeIdx) {
                tempPaint.alpha = (tempPaint.alpha * 0.25f).toInt()
            }
            drawPoints(c, s.points, tempPaint)
        }

        if (currentState == State.STROKE_EDITING) {
            currentStroke?.let {
                drawStrokeWithEndpoints(c, it.points, it.paint)
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

    override fun onSingleTapEnd(event: MotionEvent): Boolean {
        when (currentState) {
            State.NORMAL_DRAWING, State.CHOSEN_STROKE, State.STROKE_EDITING -> {
                if (selectStrokeAt(PointF(event.x, event.y))) {
                    setState(State.CHOSEN_STROKE)
                }
            }
        }
        return true
    }

    override fun onDoubleTapEnd(event: MotionEvent): Boolean {
        when (currentState) {
            State.CHOSEN_STROKE, State.STROKE_EDITING -> {
                selectedStrokeIdx = -1
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
        twoFingerGestureOccured = true
        redrawHistory()
        return true
    }

    override fun onThirdFingerDown(event: MotionEvent): Boolean {
        threeFingerGestureOccured = true
        return true
    }

    override fun onSomeFingerUp(event: MotionEvent): Boolean {
        return true
    }

    override fun onLastRemainingFingerUp(event: MotionEvent): Boolean {
        twoFingerGestureOccured = false
        threeFingerGestureOccured = false
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
        redrawHistory()
        return true
    }

    override fun onSingleFingerDrag(event: MotionEvent, dx: Float, dy: Float): Boolean {
        if (twoFingerGestureOccured || threeFingerGestureOccured) return true

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

    private fun midpoint(event: MotionEvent): PointF {
        if (event.pointerCount < 2) return PointF(event.x, event.y)
        val x = (event.getX(0) + event.getX(1)) / 2f
        val y = (event.getY(0) + event.getY(1)) / 2f
        return PointF(x, y)
    }

    override fun onTwoFingerDrag(event: MotionEvent, dx: Float, dy: Float, scale: Float, rotate: Float): Boolean {
        val deltaMatrix = Matrix()
        val mid = midpoint(event)
        deltaMatrix.postTranslate(dx, dy)
        deltaMatrix.postScale(scale, scale, mid.x, mid.y)
        deltaMatrix.postRotate(rotate, mid.x, mid.y)
        transformAllStrokes(deltaMatrix)
        return true
    }

    override fun onThreeFingerDrag(event: MotionEvent, dx: Float, dy: Float, scale: Float, rotate: Float): Boolean {
        currentStroke?.let {
            val deltaMatrix = Matrix()
            val bounds = it.getBounds()
            val centerX = bounds.centerX()
            val centerY = bounds.centerY()
            deltaMatrix.postScale(scale, scale, centerX, centerY)
            deltaMatrix.postRotate(rotate, centerX, centerY)
            deltaMatrix.postTranslate(dx, dy)
            transformStroke(it, deltaMatrix)
        }
        return true
    }

    override fun onTapAndAHalf(event: MotionEvent): Boolean {
        selectedStrokeIdx = -1
        selectedEnd = SelectedEnd.NONE
        setState(State.NORMAL_DRAWING)
        return true
    }
}
