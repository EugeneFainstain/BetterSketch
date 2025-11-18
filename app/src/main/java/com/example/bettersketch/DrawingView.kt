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
    START, END, MIDDLE, NONE
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
    var currentSmoothness: Int = 0

    // Data
    private val strokes = mutableListOf<Stroke>()
    private var selectedStrokeIdx: Int = -1
    private var middlePointRelativeDistance = 0.5f

    // Transformation state
    private var twoFingerGestureOccured = false
    private var threeFingerGestureOccured = false
    private var strokeImplicitlySelectedForTransform = false

    private val customGestureDetector: CustomGestureDetector

    init {
        customGestureDetector = CustomGestureDetector(context, this)
    }

    // Public properties
    val isStrokeSelected: Boolean get() = selectedStrokeIdx != -1
    private val currentStroke: Stroke? get() = strokes.getOrNull(selectedStrokeIdx)

    fun isEditing(): Boolean {
        return currentState != State.NORMAL_DRAWING
    }

    fun isCurrentStrokeModified(): Boolean {
        return currentStroke?.isModified ?: false
    }

    fun exitEditingMode() {
        selectedStrokeIdx = -1
        setState(State.NORMAL_DRAWING)
    }

    fun undoStrokeModifications() {
        currentStroke?.let {
            // Use updatePointsFromPointFs to ensure totalDistance is also reset
            it.updatePointsFromPointFs(it.originalPoints.map { p -> p.point })
            it.isModified = false
            redrawHistory()
            listener?.onStateChanged()
        }
    }

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

    private fun transformAllStrokes(matrix: Matrix, scale: Float) { // Added scale parameter
        strokes.forEach { stroke ->
            stroke.paint.strokeWidth *= scale // Apply scale to stroke width
            stroke.applyTransformation(matrix, isGlobalTransform = true)
        }
        redrawHistory()
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

    private fun preprocessStroke(points: MutableList<PathPoint>): MutableList<PathPoint> {
        var currentPoints = points
        while (currentPoints.size in 2..19) {
            val newPoints = mutableListOf<PathPoint>()
            newPoints.add(currentPoints.first())

            for (i in 0 until currentPoints.size - 1) {
                val p1 = currentPoints[i]
                val p2 = currentPoints[i + 1]
                val midPoint = PointF((p1.point.x + p2.point.x) / 2f, (p1.point.y + p2.point.y) / 2f)
                newPoints.add(PathPoint(midPoint, 0f)) // placeholder distance
                newPoints.add(p2)
            }
            currentPoints = newPoints
        }
        // The recalculation logic is no longer needed here.
        // The Stroke constructor will take care of calculating distances from the PointF list.
        return currentPoints
    }

    private fun commitStrokeInProgress() {
        if (strokeInProgressPoints.isNotEmpty()) {
            val processedPoints = preprocessStroke(strokeInProgressPoints)
            // Convert PathPoint list to PointF list for the Stroke constructor
            val pointFs = processedPoints.map { it.point }
            val newStroke = Stroke(pointFs, Paint(currentPaint), currentSmoothness) // Updated constructor call
            newStroke.applySmoothing() // Call the new method on the Stroke object
            strokes.add(newStroke)
            strokeInProgressPoints.clear()
            selectedStrokeIdx = -1
            setState(State.NORMAL_DRAWING)
        }
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
        if (stroke.points.isEmpty()) return false

        // Find the point on the stroke physically closest to the tap
        var closestDist = Float.MAX_VALUE
        var closestPoint: PathPoint? = null
        stroke.points.forEach { pathPoint ->
            val d = distance(pathPoint.point, tapPoint)
            if (d < closestDist) {
                closestDist = d
                closestPoint = pathPoint
            }
        }

        if (closestPoint == null) {
            selectedEnd = SelectedEnd.NONE
            return false
        }

        val relativeDistance = if (stroke.totalDistance > 0) closestPoint.distance / stroke.totalDistance else 0f

        // Snap to endpoints if close enough
        if (relativeDistance < 0.05f) {
            selectedEnd = SelectedEnd.START
        } else if (relativeDistance > 0.95f) {
            selectedEnd = SelectedEnd.END
        } else {
            selectedEnd = SelectedEnd.MIDDLE
            middlePointRelativeDistance = relativeDistance
        }
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

    private fun drawStrokeWithEndpoints(canvas: Canvas, points: List<PathPoint>, paint: Paint) {
        if (points.isEmpty()) return

        if ((twoFingerGestureOccured && !threeFingerGestureOccured) || strokeImplicitlySelectedForTransform) {
            drawPoints(canvas, points, paint)
            return
        }

        val radius = paint.strokeWidth * 2f
        val startPoint = points.first().point
        val endPoint = points.last().point

        val endpointPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.GREEN
        }

        if (threeFingerGestureOccured) {
            val middlePoint = currentStroke?.getPointAtRelativeDistance(0.5f)?.point
            canvas.drawCircle(startPoint.x, startPoint.y, radius, endpointPaint)
            canvas.drawCircle(endPoint.x, endPoint.y, radius, endpointPaint)
            middlePoint?.let { canvas.drawCircle(it.x, it.y, radius, endpointPaint) }
        } else {
            when (selectedEnd) {
                SelectedEnd.START -> canvas.drawCircle(startPoint.x, startPoint.y, radius, endpointPaint)
                SelectedEnd.END -> canvas.drawCircle(endPoint.x, endPoint.y, radius, endpointPaint)
                SelectedEnd.MIDDLE -> {
                    val middlePoint = currentStroke?.getPointAtRelativeDistance(middlePointRelativeDistance)?.point
                    middlePoint?.let { canvas.drawCircle(it.x, it.y, radius, endpointPaint) }
                }
                else -> {}
            }
        }

        drawPoints(canvas, points, paint)
    }

    fun setStrokeSmoothness(smoothness: Int) {
        currentSmoothness = smoothness
        currentStroke?.let {
            it.smoothness = smoothness
            it.applySmoothing() // Call the new method on the Stroke object
            it.isModified = true
            redrawHistory()
        }
    }

    fun setColor(color: Int, applyToSelected: Boolean) {
        if (applyToSelected) {
            currentStroke?.isModified = true
        }
        currentPaint.color = color
        if (applyToSelected && selectedStrokeIdx != -1) {
            currentStroke?.paint?.color = color
            redrawHistory()
        }
        listener?.onStateChanged()
    }

    fun setStrokeWidth(px: Float, applyToSelected: Boolean) {
        if (applyToSelected) {
            currentStroke?.isModified = true
        }
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
            if (selectedStrokeIdx != -1 && index != selectedStrokeIdx && !strokeImplicitlySelectedForTransform) {
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
        if (strokeInProgressPoints.isNotEmpty()) {
            if (strokeInProgressPoints.size > 5) {
                commitStrokeInProgress()
            } else {
                strokeInProgressPoints.clear()
            }
        }
        threeFingerGestureOccured = true
        if (selectedStrokeIdx == -1 && strokes.isNotEmpty()) {
            selectedStrokeIdx = strokes.lastIndex
            strokeImplicitlySelectedForTransform = true
            setState(State.STROKE_EDITING)
        } else {
            redrawHistory() // Redraw to show endpoints on already-selected stroke
        }
        return true
    }

    override fun onSomeFingerUp(event: MotionEvent): Boolean {
        return true
    }

    override fun onLastRemainingFingerUp(event: MotionEvent): Boolean {
        twoFingerGestureOccured = false
        threeFingerGestureOccured = false

        if (strokeImplicitlySelectedForTransform) {
            selectedStrokeIdx = -1
            strokeImplicitlySelectedForTransform = false
            setState(State.NORMAL_DRAWING)
        } else {
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
        }
        redrawHistory()
        return true
    }

    override fun onSingleFingerDrag(event: MotionEvent, dx: Float, dy: Float): Boolean {
        if (twoFingerGestureOccured || threeFingerGestureOccured) return true

        when (currentState) {
            State.NORMAL_DRAWING -> touchMove(event.x, event.y)
            State.STROKE_EDITING -> {
                currentStroke?.let {
                    when (selectedEnd) {
                        SelectedEnd.START -> it.moveStartPoint(dx, dy)
                        SelectedEnd.END -> it.moveEndPoint(dx, dy)
                        SelectedEnd.MIDDLE -> it.moveMiddlePoint(dx, dy, middlePointRelativeDistance)
                        else -> {}
                    }
                    it.isModified = true
                    listener?.onStateChanged()
                    redrawHistory()
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
        transformAllStrokes(deltaMatrix, scale) // Pass scale to transformAllStrokes
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
            it.paint.strokeWidth *= scale // Apply scale to stroke width
            it.applyTransformation(deltaMatrix, isGlobalTransform = false)
            listener?.onStateChanged()
            redrawHistory()
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
