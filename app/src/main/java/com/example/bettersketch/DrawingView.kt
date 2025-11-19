package com.example.bettersketch

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
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
    private var editingPointIndex: Int = -1
    private var editingPointInitialWeights: List<Float>? = null

    // Transformation state
    private var twoFingerGestureOccured = false
    private var threeFingerGestureOccured = false

    private val customGestureDetector: CustomGestureDetector
    private val haloPaint: Paint
    private val haloOffset: Float
    private val selectionPaint: Paint
    private var selectionCircle: Triple<PointF, Float, Path>? = null


    init {
        customGestureDetector = CustomGestureDetector(context, this)
        haloOffset = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, context.resources.displayMetrics)
        haloPaint = Paint().apply {
            isAntiAlias = true
            isDither = true
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            color = Color.LTGRAY
        }
        selectionPaint = Paint().apply {
            isAntiAlias = true
            color = Color.BLUE
            style = Paint.Style.STROKE
            strokeWidth = 5f
            pathEffect = DashPathEffect(floatArrayOf(15f, 15f), 0f)
        }
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
        currentStroke?.setHighlightedRecursively(false)
        selectedStrokeIdx = -1
        editingPointIndex = -1
        editingPointInitialWeights = null
        setState(State.NORMAL_DRAWING)
    }

    fun undoStrokeModifications() {
        currentStroke?.forEachStroke {
            it.unsmoothedPoints.clear()
            it.unsmoothedPoints.addAll(it.originalPoints.map { p -> PathPoint(PointF(p.point.x, p.point.y), p.distance) })
            val (recalculatedUnsmoothedPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(it.unsmoothedPoints.map { p -> p.point })
            it.unsmoothedPoints.clear()
            it.unsmoothedPoints.addAll(recalculatedUnsmoothedPoints)
            it.totalDistance = newTotalDistance
            it.paint.strokeWidth = it.originalStrokeWidth
            it.applySmoothing()
            it.isModified = false
        }
        redrawHistory()
        listener?.onStateChanged()
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
        if (currentState == State.NORMAL_DRAWING && strokeInProgress != null) {
            drawStroke(canvas, strokeInProgress!!)
        }

        selectionCircle?.let {
            canvas.drawPath(it.third, selectionPaint)
        }

        canvas.restore()
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        customGestureDetector.onTouchEvent(event)
        return true
    }

    private fun transformStroke(stroke: Stroke, matrix: Matrix, isGlobalTransform: Boolean) {
        val scale = getScaleFromMatrix(matrix)
        stroke.forEachStroke { s ->
            if (isGlobalTransform) {
                s.paint.strokeWidth *= scale
                s.originalStrokeWidth *= scale
                s.totalDistance *= scale

                val transformPoint = { pathPoint: PathPoint ->
                    val point = floatArrayOf(pathPoint.point.x, pathPoint.point.y)
                    matrix.mapPoints(point)
                    pathPoint.point.set(point[0], point[1])
                    pathPoint.distance *= scale
                }

                s.points.forEach(transformPoint)
                s.unsmoothedPoints.forEach(transformPoint)
                s.originalPoints.forEach(transformPoint)

            } else {
                s.isModified = true
                s.paint.strokeWidth *= scale

                s.unsmoothedPoints.forEach { pathPoint ->
                    val point = floatArrayOf(pathPoint.point.x, pathPoint.point.y)
                    matrix.mapPoints(point)
                    pathPoint.point.set(point[0], point[1])
                }
                val (recalculatedUnsmoothedPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(s.unsmoothedPoints.map { it.point })
                s.unsmoothedPoints.clear()
                s.unsmoothedPoints.addAll(recalculatedUnsmoothedPoints)
                s.totalDistance = newTotalDistance

                s.applySmoothing()
            }
        }
        if (!isGlobalTransform) {
            listener?.onStateChanged()
            redrawHistory()
        }
    }

    private fun transformAllStrokes(matrix: Matrix) {
        strokes.forEach { stroke ->
            transformStroke(stroke, matrix, isGlobalTransform = true)
        }
        redrawHistory() // Redraw only once after all strokes are transformed
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
            val preprocessedUnsmoothedPoints = Stroke.preprocessStroke(currentStrokeInProgress.unsmoothedPoints)
            val (finalUnsmoothedPoints, totalDistanceForNewStroke) = Stroke.calculatePathPointsWithDistances(preprocessedUnsmoothedPoints.map { it.point })
            val newStroke = Stroke(finalUnsmoothedPoints, Paint(currentStrokeInProgress.paint), totalDistanceForNewStroke, currentStrokeInProgress.smoothness)
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

    private fun setStrokeHighlighted(stroke: Stroke?, highlighted: Boolean) {
        stroke?.setHighlightedRecursively(highlighted)
    }

    private fun selectStrokeAt(tapPoint: PointF): Boolean {
        var minDistance = Float.MAX_VALUE
        var closestStrokeIndex = -1

        // Clear all current highlights before selecting a new stroke
        strokes.forEach { it.setHighlightedRecursively(false) }

        strokes.forEachIndexed { index, stroke ->
            stroke.forEachStroke { s ->
                for (pathPoint in s.points) {
                    val d = distance(pathPoint.point, tapPoint)
                    if (d < minDistance) {
                        minDistance = d
                        closestStrokeIndex = index
                    }
                }
            }
        }

        if (closestStrokeIndex != -1) {
            selectedStrokeIdx = closestStrokeIndex
            setStrokeHighlighted(currentStroke, true)
            currentPaint = Paint(strokes[closestStrokeIndex].paint)
            return true
        }
        return false
    }

    private fun selectEndpointOfCurrentStroke(tapPoint: PointF): Boolean {
        val stroke = currentStroke ?: return false
        if (stroke.isGroup) return false // Don't allow endpoint selection for groups

        var closestDist = Float.MAX_VALUE
        var closestPointIndex = -1
        stroke.points.forEachIndexed { index, pathPoint ->
            val d = distance(pathPoint.point, tapPoint)
            if (d < closestDist) {
                closestDist = d
                closestPointIndex = index
            }
        }

        if (closestPointIndex == -1) {
            selectedEnd = SelectedEnd.NONE
            return false
        }

        editingPointIndex = closestPointIndex
        val totalPoints = stroke.unsmoothedPoints.size

        if (editingPointIndex < totalPoints * 0.05f) {
            selectedEnd = SelectedEnd.START
            editingPointIndex = 0
        } else if (editingPointIndex > totalPoints * 0.95f) {
            selectedEnd = SelectedEnd.END
            editingPointIndex = totalPoints - 1
        } else {
            selectedEnd = SelectedEnd.MIDDLE
            val totalDistanceOfUnsmoothed = stroke.unsmoothedPoints.last().distance
            val middlePointRelativeDistance = stroke.unsmoothedPoints[editingPointIndex].distance / totalDistanceOfUnsmoothed
            editingPointInitialWeights = stroke.unsmoothedPoints.map {
                val relativeDistance = it.distance / totalDistanceOfUnsmoothed
                val mappedDistance = if (relativeDistance <= middlePointRelativeDistance) {
                    relativeDistance / middlePointRelativeDistance
                } else {
                    1 - ((relativeDistance - middlePointRelativeDistance) / (1 - middlePointRelativeDistance))
                }
                sin(mappedDistance * PI / 2).toFloat()
            }
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

    fun duplicateCurrentStroke() {
        currentStroke?.let { originalStroke ->
            val duplicatedStroke = originalStroke.deepCopy()
            val bounds = originalStroke.getBounds()
            val offsetY = -bounds.height() / 2f
            val matrix = Matrix().apply { postTranslate(0f, offsetY) }
            transformStroke(duplicatedStroke, matrix, isGlobalTransform = true)

            strokes.add(duplicatedStroke)
            selectedStrokeIdx = strokes.lastIndex
            setState(State.CHOSEN_STROKE)
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    fun getStrokeColors(): IntArray {
        return strokes.map { it.paint.color }.toIntArray()
    }

    private fun moveEditingPoint(dx: Float, dy: Float) {
        currentStroke?.isModified = true
        currentStroke?.let { stroke ->
            val weights = editingPointInitialWeights
            if (weights != null && weights.size == stroke.unsmoothedPoints.size) {
                stroke.unsmoothedPoints.forEachIndexed { index, pathPoint ->
                    pathPoint.point.offset(dx * weights[index], dy * weights[index])
                }
            } else {
                val totalDistanceOfUnsmoothed = stroke.unsmoothedPoints.lastOrNull()?.distance ?: 0f
                if (totalDistanceOfUnsmoothed == 0f) return

                stroke.unsmoothedPoints.forEach { pathPoint ->
                    val weight = if (selectedEnd == SelectedEnd.START) {
                        1.0f - (pathPoint.distance / totalDistanceOfUnsmoothed)
                    } else {
                        pathPoint.distance / totalDistanceOfUnsmoothed
                    }
                    pathPoint.point.offset(dx * weight, dy * weight)
                }
            }

            val (recalculatedUnsmoothedPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(stroke.unsmoothedPoints.map { p -> p.point })
            stroke.unsmoothedPoints.clear()
            stroke.unsmoothedPoints.addAll(recalculatedUnsmoothedPoints)
            stroke.totalDistance = newTotalDistance

            stroke.applySmoothing()
            redrawHistory()
        }
    }

    private fun drawStrokeWithEndpoints(canvas: Canvas, stroke: Stroke) {
        if (stroke.isGroup) return
        if (stroke.points.isEmpty()) return

        if (twoFingerGestureOccured || threeFingerGestureOccured) {
            return
        }

        val radius = stroke.paint.strokeWidth * 2f
        val endpointPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.GREEN
        }

        if (editingPointIndex != -1) {
            val pointToHighlight = stroke.points[editingPointIndex].point
            canvas.drawCircle(pointToHighlight.x, pointToHighlight.y, radius, endpointPaint)
        }
    }

    fun setStrokeSmoothness(smoothness: Int) {
        currentSmoothness = smoothness
        currentStroke?.forEachStroke {
            it.smoothness = smoothness
            it.applySmoothing()
            it.isModified = true
        }
        redrawHistory()
    }

    fun setColor(color: Int, applyToSelected: Boolean) {
        if (applyToSelected) {
            currentStroke?.forEachStroke {
                it.isModified = true
                it.paint.color = color
            }
            redrawHistory()
        }
        currentPaint.color = color
        listener?.onStateChanged()
    }

    fun setStrokeWidth(px: Float, applyToSelected: Boolean) {
        if (applyToSelected) {
            val w = max(1f, min(120f, px))
            currentStroke?.forEachStroke {
                it.isModified = true
                it.paint.strokeWidth = w
            }
            redrawHistory()
        }
        currentPaint.strokeWidth = max(1f, min(120f, px))
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
            if (s.isHighlighted) {
                s.forEachStroke {
                    haloPaint.strokeWidth = it.paint.strokeWidth + haloOffset
                    drawStroke(c, it, haloPaint)
                }
            }
            val paintToDraw = Paint(s.paint)
            if (selectedStrokeIdx != -1 && index != selectedStrokeIdx) {
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
        stroke.forEachStroke { s ->
            if (s.points.size >= 2) {
                val path = Path()
                path.moveTo(s.points.first().point.x, s.points.first().point.y)
                for (i in 1 until s.points.size) {
                    path.lineTo(s.points[i].point.x, s.points[i].point.y)
                }
                canvas?.drawPath(path, paint ?: s.paint)
            }
        }
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
            State.NORMAL_DRAWING -> {
                if (selectStrokeAt(PointF(event.x, event.y))) {
                    setState(State.CHOSEN_STROKE)
                }
            }
            State.CHOSEN_STROKE, State.STROKE_EDITING -> {
                strokes.forEach { it.setHighlightedRecursively(false) }
                exitEditingMode()
            }
        }
        return true
    }

    override fun onDoubleTapEnd(event: MotionEvent): Boolean {
        onSingleTapEnd(event)
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
        threeFingerGestureOccured = true
        return true
    }

    override fun onSomeFingerUp(event: MotionEvent): Boolean {
        return true
    }

    override fun onLastRemainingFingerUp(event: MotionEvent): Boolean {
        twoFingerGestureOccured = false
        threeFingerGestureOccured = false
        selectionCircle = null

        when (currentState) {
            State.NORMAL_DRAWING -> {
                strokeInProgress?.let {
                    touchUp()
                }
            }
            State.STROKE_EDITING -> {
                setState(State.CHOSEN_STROKE)
                editingPointIndex = -1
                editingPointInitialWeights = null
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
                moveEditingPoint(dx, dy)
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

    private fun calculateCircle(p1: PointF, p2: PointF, p3: PointF): Triple<PointF, Float, Path> {
        val points = listOf(p1, p2, p3)
        var maxDist = 0f
        var pt1 = p1
        var pt2 = p2
        var pt3 = p3

        for (i in 0..2) {
            for (j in i + 1..2) {
                val d = distance(points[i], points[j])
                if (d > maxDist) {
                    maxDist = d
                    pt1 = points[i]
                    pt2 = points[j]
                }
            }
        }
        pt3 = points.first { it != pt1 && it != pt2 }

        val midPoint = PointF((pt1.x + pt2.x) / 2, (pt1.y + pt2.y) / 2)
        val center = PointF((midPoint.x * 2/3) + (pt3.x * 1/3), (midPoint.y * 2/3) + (pt3.y * 1/3))
        val radius = maxDist / 2f
        val path = Path().apply { addCircle(center.x, center.y, radius, Path.Direction.CW) }
        return Triple(center, radius, path)
    }

    private fun isPointInCircle(point: PointF, circleCenter: PointF, circleRadius: Float): Boolean {
        return distance(point, circleCenter) < circleRadius
    }

    override fun onThreeFingerDrag(event: MotionEvent, dx: Float, dy: Float, scale: Float, rotate: Float): Boolean {
        if (event.pointerCount >= 3) {
            val p1 = PointF(event.getX(0), event.getY(0))
            val p2 = PointF(event.getX(1), event.getY(1))
            val p3 = PointF(event.getX(2), event.getY(2))

            selectionCircle = calculateCircle(p1, p2, p3)
            selectionCircle?.let { (center, radius, _) ->
                strokes.forEach { stroke ->
                    var strokeInCircle = false
                    stroke.forEachStroke { s ->
                        if (s.points.any { isPointInCircle(it.point, center, radius) }) {
                            strokeInCircle = true
                        }
                    }

                    stroke.setHighlightedRecursively(strokeInCircle || (stroke == currentStroke))
                }
            }
            redrawHistory()
        }
        return true
    }
}
