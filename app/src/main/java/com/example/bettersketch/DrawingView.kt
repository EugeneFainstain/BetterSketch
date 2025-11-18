package com.example.bettersketch

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

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
    private var strokeInProgress: Stroke? = null
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
            it.unsmoothedPoints.clear()
            it.unsmoothedPoints.addAll(it.originalPoints.map { p -> PathPoint(PointF(p.point.x, p.point.y), p.distance) })
            // Recalculate distances for unsmoothedPoints after modification
            val (recalculatedUnsmoothedPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(it.unsmoothedPoints.map { p -> p.point })
            it.unsmoothedPoints.clear()
            it.unsmoothedPoints.addAll(recalculatedUnsmoothedPoints)
            it.totalDistance = newTotalDistance // Update totalDistance based on unsmoothed points
            it.applySmoothing() // Re-smooth from the restored unsmoothed points
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
        if (currentState == State.NORMAL_DRAWING && strokeInProgress != null && strokeInProgress!!.points.isNotEmpty()) {
            drawStroke(canvas, strokeInProgress!!)
        }

        canvas.restore()
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        customGestureDetector.onTouchEvent(event)
        return true
    }

    private fun transformStroke(stroke: Stroke, matrix: Matrix, isGlobalTransform: Boolean) {
        if (!isGlobalTransform) {
            stroke.isModified = true
            listener?.onStateChanged()
        }
        val scale = getScaleFromMatrix(matrix)
        stroke.paint.strokeWidth *= scale

        // Transform unsmoothedPoints
        stroke.unsmoothedPoints.forEach { pathPoint ->
            val point = floatArrayOf(pathPoint.point.x, pathPoint.point.y)
            matrix.mapPoints(point)
            pathPoint.point.set(point[0], point[1])
        }
        // Recalculate distances for unsmoothedPoints after transformation
        val (recalculatedUnsmoothedPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(stroke.unsmoothedPoints.map { it.point })
        stroke.unsmoothedPoints.clear()
        stroke.unsmoothedPoints.addAll(recalculatedUnsmoothedPoints)
        stroke.totalDistance = newTotalDistance // Update totalDistance based on unsmoothed points

        // Only transform the original points if it's a global canvas operation
        if (isGlobalTransform) {
            stroke.originalPoints.forEach { pathPoint ->
                val point = floatArrayOf(pathPoint.point.x, pathPoint.point.y)
                matrix.mapPoints(point)
                pathPoint.point.set(point[0], point[1])
            }
            // Recalculate distances for originalPoints after transformation
            val (recalculatedOriginalPoints, _) = Stroke.calculatePathPointsWithDistances(stroke.originalPoints.map { it.point })
            stroke.originalPoints.clear()
            stroke.originalPoints.addAll(recalculatedOriginalPoints)
        }

        stroke.applySmoothing() // Re-smooth points and update totalDistance based on the new unsmoothedPoints
        redrawHistory()
    }

    private fun transformAllStrokes(matrix: Matrix) {
        strokes.forEach { stroke ->
            transformStroke(stroke, matrix, isGlobalTransform = true)
        }
    }

    private fun getScaleFromMatrix(matrix: Matrix): Float {
        val values = FloatArray(9)
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun touchStart(x: Float, y: Float) {
        strokeInProgress = Stroke(Paint(currentPaint), currentSmoothness)
        strokeInProgress?.addPoint(PointF(x, y))
    }

    private fun touchMove(x: Float, y: Float) {
        strokeInProgress?.addPoint(PointF(x, y))
        invalidate() // Redraw the live stroke
    }

    private fun touchUp() {
        strokeInProgress?.let {
            commitStrokeInProgress()
        }
    }

    private fun commitStrokeInProgress() {
        strokeInProgress?.let { currentStrokeInProgress ->
            // Preprocess the unsmoothed points from the strokeInProgress
            val preprocessedUnsmoothedPoints = Stroke.preprocessStroke(currentStrokeInProgress.unsmoothedPoints)

            // Calculate total distance for the preprocessed unsmoothed points
            val (finalUnsmoothedPoints, totalDistanceForNewStroke) = Stroke.calculatePathPointsWithDistances(preprocessedUnsmoothedPoints.map { it.point })

            // Create the new Stroke using the preprocessed unsmoothed points
            val newStroke = Stroke(finalUnsmoothedPoints, Paint(currentStrokeInProgress.paint), totalDistanceForNewStroke, currentStrokeInProgress.smoothness)
            // The constructor now calls applySmoothing internally, so no need for explicit call here.

            strokes.add(newStroke)
            strokeInProgress = null
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

    private fun getPointAtRelativeDistance(stroke: Stroke, relativeDist: Float): PathPoint? {
        if (stroke.points.isEmpty()) return null
        val targetDist = stroke.totalDistance * relativeDist
        var closestPoint = stroke.points.first()
        var smallestDist = Float.MAX_VALUE
        for (p in stroke.points) {
            val dist = abs(p.distance - targetDist)
            if (dist < smallestDist) {
                smallestDist = dist
                closestPoint = p
            }
        }
        return closestPoint
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

    fun moveStartPoint(dx: Float, dy: Float) {
        currentStroke?.isModified = true
        currentStroke?.let {
            // Operate on unsmoothedPoints
            val totalDistanceOfUnsmoothed = it.unsmoothedPoints.lastOrNull()?.distance ?: 0f
            if (totalDistanceOfUnsmoothed == 0f) return

            for (pathPoint in it.unsmoothedPoints) {
                val weight = 1.0f - (pathPoint.distance / totalDistanceOfUnsmoothed)
                pathPoint.point.offset(dx * weight, dy * weight)
            }
            // Recalculate distances for unsmoothedPoints after modification
            val (recalculatedUnsmoothedPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(it.unsmoothedPoints.map { p -> p.point })
            it.unsmoothedPoints.clear()
            it.unsmoothedPoints.addAll(recalculatedUnsmoothedPoints)
            it.totalDistance = newTotalDistance // Update totalDistance based on unsmoothed points

            it.applySmoothing() // Re-smooth points after modification
            redrawHistory()
        }
    }

    fun moveEndPoint(dx: Float, dy: Float) {
        currentStroke?.isModified = true
        currentStroke?.let {
            // Operate on unsmoothedPoints
            val totalDistanceOfUnsmoothed = it.unsmoothedPoints.lastOrNull()?.distance ?: 0f
            if (totalDistanceOfUnsmoothed == 0f) return

            for (pathPoint in it.unsmoothedPoints) {
                val weight = pathPoint.distance / totalDistanceOfUnsmoothed
                pathPoint.point.offset(dx * weight, dy * weight)
            }
            // Recalculate distances for unsmoothedPoints after modification
            val (recalculatedUnsmoothedPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(it.unsmoothedPoints.map { p -> p.point })
            it.unsmoothedPoints.clear()
            it.unsmoothedPoints.addAll(recalculatedUnsmoothedPoints)
            it.totalDistance = newTotalDistance // Update totalDistance based on unsmoothed points

            it.applySmoothing() // Re-smooth points after modification
            redrawHistory()
        }
    }

    fun moveMiddlePoint(dx: Float, dy: Float) {
        currentStroke?.isModified = true
        currentStroke?.let {
            // Operate on unsmoothedPoints
            val totalDistanceOfUnsmoothed = it.unsmoothedPoints.lastOrNull()?.distance ?: 0f
            if (totalDistanceOfUnsmoothed == 0f) return

            for (pathPoint in it.unsmoothedPoints) {
                val relativeDistance = pathPoint.distance / totalDistanceOfUnsmoothed

                val mappedDistance = if (relativeDistance <= middlePointRelativeDistance) {
                    relativeDistance / middlePointRelativeDistance
                } else {
                    1 - ((relativeDistance - middlePointRelativeDistance) / (1 - middlePointRelativeDistance))
                }
                val weight = sin(mappedDistance * PI / 2).toFloat()
                pathPoint.point.offset(dx * weight, dy * weight)
            }
            // Recalculate distances for unsmoothedPoints after modification
            val (recalculatedUnsmoothedPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(it.unsmoothedPoints.map { p -> p.point })
            it.unsmoothedPoints.clear()
            it.unsmoothedPoints.addAll(recalculatedUnsmoothedPoints)
            it.totalDistance = newTotalDistance // Update totalDistance based on unsmoothed points

            it.applySmoothing() // Re-smooth points after modification
            redrawHistory()
        }
    }

    private fun drawStrokeWithEndpoints(canvas: Canvas, stroke: Stroke) {
        if (stroke.points.isEmpty()) return

        if ((twoFingerGestureOccured && !threeFingerGestureOccured) || strokeImplicitlySelectedForTransform) {
            drawStroke(canvas, stroke)
            return
        }

        val radius = stroke.paint.strokeWidth * 2f
        val startPoint = stroke.points.first().point
        val endPoint = stroke.points.last().point

        val endpointPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.GREEN
        }

        if (threeFingerGestureOccured) {
            val middlePoint = getPointAtRelativeDistance(stroke, 0.5f)?.point
            canvas.drawCircle(startPoint.x, startPoint.y, radius, endpointPaint)
            canvas.drawCircle(endPoint.x, endPoint.y, radius, endpointPaint)
            middlePoint?.let { canvas.drawCircle(it.x, it.y, radius, endpointPaint) }
        } else {
            when (selectedEnd) {
                SelectedEnd.START -> canvas.drawCircle(startPoint.x, startPoint.y, radius, endpointPaint)
                SelectedEnd.END -> canvas.drawCircle(endPoint.x, endPoint.y, radius, endpointPaint)
                SelectedEnd.MIDDLE -> {
                    val middlePoint = getPointAtRelativeDistance(stroke, middlePointRelativeDistance)?.point
                    middlePoint?.let { canvas.drawCircle(it.x, it.y, radius, endpointPaint) }
                }
                else -> {}
            }
        }

        drawStroke(canvas, stroke)
    }

    fun setStrokeSmoothness(smoothness: Int) {
        currentSmoothness = smoothness
        currentStroke?.let {
            it.smoothness = smoothness
            it.applySmoothing()
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

        for ((index, s) in strokes.withIndex()) {
            val paintToDraw = Paint(s.paint) // Create a copy to modify alpha
            if (selectedStrokeIdx != -1 && index != selectedStrokeIdx && !strokeImplicitlySelectedForTransform) {
                paintToDraw.alpha = (paintToDraw.alpha * 0.25f).toInt()
            }
            drawStroke(c, s, paintToDraw)
        }

        if (currentState == State.STROKE_EDITING) {
            currentStroke?.let {
                drawStrokeWithEndpoints(c, it)
            }
        }

        if (canvas == null) invalidate()
    }

    private fun drawStroke(canvas: Canvas?, stroke: Stroke, paint: Paint? = null) {
        if (canvas == null || stroke.points.size < 2) return
        val path = Path()
        path.moveTo(stroke.points.first().point.x, stroke.points.first().point.y)
        for (i in 1 until stroke.points.size) {
            path.lineTo(stroke.points[i].point.x, stroke.points[i].point.y)
        }
        canvas.drawPath(path, paint ?: stroke.paint)
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
        if (strokeInProgress != null && strokeInProgress!!.points.isNotEmpty()) {
            if (strokeInProgress!!.points.size > 5) {
                commitStrokeInProgress()
            } else {
                strokeInProgress = null
            }
        }
        twoFingerGestureOccured = true
        redrawHistory()
        return true
    }

    override fun onThirdFingerDown(event: MotionEvent): Boolean {
        if (strokeInProgress != null && strokeInProgress!!.points.isNotEmpty()) {
            if (strokeInProgress!!.points.size > 5) {
                commitStrokeInProgress()
            } else {
                strokeInProgress = null
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
                    strokeInProgress?.let {
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
                when (selectedEnd) {
                    SelectedEnd.START -> moveStartPoint(dx, dy)
                    SelectedEnd.END -> moveEndPoint(dx, dy)
                    SelectedEnd.MIDDLE -> moveMiddlePoint(dx, dy)
                    else -> {}
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
        when (currentState) {
            State.NORMAL_DRAWING -> {
                val deltaMatrix = Matrix()
                val mid = midpoint(event)
                deltaMatrix.postTranslate(dx, dy)
                deltaMatrix.postScale(scale, scale, mid.x, mid.y)
                deltaMatrix.postRotate(rotate, mid.x, mid.y)
                transformAllStrokes(deltaMatrix) // Canvas transformation
            }
            State.CHOSEN_STROKE, State.STROKE_EDITING -> {
                currentStroke?.let {
                    val deltaMatrix = Matrix()
                    val bounds = it.getBounds()
                    val centerX = bounds.centerX()
                    val centerY = bounds.centerY()
                    deltaMatrix.postScale(scale, scale, centerX, centerY)
                    deltaMatrix.postRotate(rotate, centerX, centerY)
                    deltaMatrix.postTranslate(dx, dy)
                    transformStroke(it, deltaMatrix, isGlobalTransform = false) // Stroke transformation
                }
            }
        }
        return true
    }

    override fun onThreeFingerDrag(event: MotionEvent, dx: Float, dy: Float, scale: Float, rotate: Float): Boolean {
        val deltaMatrix = Matrix()
        val mid = midpoint(event)
        deltaMatrix.postTranslate(dx, dy)
        deltaMatrix.postScale(scale, scale, mid.x, mid.y)
        deltaMatrix.postRotate(rotate, mid.x, mid.y)
        transformAllStrokes(deltaMatrix) // Canvas transformation
        return true
    }

    override fun onTapAndAHalf(event: MotionEvent): Boolean {
        selectedStrokeIdx = -1
        selectedEnd = SelectedEnd.NONE
        setState(State.NORMAL_DRAWING)
        return true
    }
}
