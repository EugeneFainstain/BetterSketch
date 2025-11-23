package com.example.bettersketch

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withSave
import kotlin.math.*

interface DrawingViewListener {
    fun onStateChanged()
}

sealed class ShapeFitResult {
    abstract val strokeToReplace: Stroke
    data class Square(override val strokeToReplace: Stroke, val fitResult: SquareFitter.FitResult) : ShapeFitResult()
    data class Circle(override val strokeToReplace: Stroke, val fitResult: CircleFitter.FitResult) : ShapeFitResult()
    data class Polynomial(override val strokeToReplace: Stroke, val fitResult: PolynomFitter.FitResult) : ShapeFitResult()
}

interface ShapeDetectionListener {
    fun onShapeDetected(shapeFitResult: ShapeFitResult)
    fun onNoShapeDetected()
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
    var shapeDetectionListener: ShapeDetectionListener? = null
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
    var currentPaint = defaultPaint()
    var currentSmoothness: Int = 0

    // Data
    val strokes = mutableListOf<Stroke>()
    private var selectedStrokeIdx: Int = -1
    private var editingPointIndex: Int = -1
    private var editingPointInitialWeights: List<Float>? = null

    // Transformation state
    private val globalTransform = Matrix() // Matrix for transforming from WORLD-SPACE to SCREEN-SPACE (a.k.a the VIEW MATRIX)
    private var twoFingerGestureOccured = false
    private var threeFingerGestureOccured = false
    private var backedUpGroupStroke: Stroke? = null
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f

    private val customGestureDetector = CustomGestureDetector(context, this)
    private val haloPaint: Paint
    private val haloOffset: Float
    private val selectionPaint: Paint
    private var selectionCircle: Triple<PointF, Float, Path>? = null


    init {
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
            it.applySmoothing()
            it.isModified = false
        }
        redrawHistory()
        listener?.onStateChanged()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            if (backingBitmap == null || w != backingBitmap!!.width || h != backingBitmap!!.height) {
                backingBitmap = createBitmap(w, h, Bitmap.Config.ARGB_8888)
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
        // 1. Draw the pre-rendered, transformed history from the bitmap
        backingBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        // 2. Draw the "live" part (the new stroke being created) on top, with transformation.
        if (currentState == State.NORMAL_DRAWING && strokeInProgress != null) {
            drawStroke(canvas, strokeInProgress!!, 1.0f, 1.0f, false)
        }

        selectionCircle?.let {
            val transformedPath = Path(it.third)
            transformedPath.transform(globalTransform)
            canvas.drawPath(transformedPath, selectionPaint)
        }
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        customGestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun toWorldCoordinates(x: Float, y: Float): PointF {
        val point = floatArrayOf(x, y)
        val invertedMatrix = Matrix()
        globalTransform.invert(invertedMatrix)
        invertedMatrix.mapPoints(point)
        return PointF(point[0], point[1])
    }

    private fun toScreenCoordinates(x: Float, y: Float): PointF {
        val point = floatArrayOf(x, y)
        globalTransform.mapPoints(point)
        return PointF(point[0], point[1])
    }

    private fun transformStroke(stroke: Stroke, matrix: Matrix) {
        val scale = getScaleFromMatrix(matrix)
        stroke.forEachStroke { s ->
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
        listener?.onStateChanged()
        redrawHistory()
    }

    private fun getScaleFromMatrix(matrix: Matrix): Float {
        val values = FloatArray(9)
        matrix.getValues(values)
        // Use the pythagorean theorem to calculate the scale, which is robust against rotation
        val scaleX = values[Matrix.MSCALE_X]
        val skewY = values[Matrix.MSKEW_Y]
        return sqrt(scaleX * scaleX + skewY * skewY)
    }

    private fun preTransform(matrix: Matrix, pre: Matrix) {
        // I added this wrapper to reduce confusion as to what postConcat and preConcat actually do.
        // The naming is very confusing, because it implies opposite things - when considering the order
        // of matrix multiplications vs the order of applying the transformations. Using the new names
        // this confusion is removed.
        matrix.preConcat(pre)
    }

    private fun postTransform(matrix: Matrix, post: Matrix) {
        // I added this wrapper to reduce confusion as to what postConcat and preConcat actually do.
        // The naming is very confusing, because it implies opposite things - when considering the order
        // of matrix multiplications vs the order of applying the transformations. Using the new names
        // this confusion is removed.
        matrix.postConcat(post)
    }

    private fun touchStart(x: Float, y: Float) {
        val worldPoint = toWorldCoordinates(x, y)
        strokeInProgress = Stroke(Paint(currentPaint), currentSmoothness)
        strokeInProgress?.addPoint(worldPoint)
    }

    private fun touchMove(x: Float, y: Float) {
        val worldPoint = toWorldCoordinates(x, y)
        strokeInProgress?.addPoint(worldPoint)
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

            detectShape(newStroke)
        }
    }

    private fun detectShape(stroke: Stroke) {
        if (stroke.isGroup) {
            shapeDetectionListener?.onNoShapeDetected()
            return
        }

        val strokeForFitting = stroke.generateUniformSampled(256)
        val fitResult = ShapeFitter.fit(stroke, strokeForFitting)

        if (fitResult != null) {
            shapeDetectionListener?.onShapeDetected(fitResult)
        } else {
            shapeDetectionListener?.onNoShapeDetected()
        }
    }

    fun replaceWithShape(originalStroke: Stroke, fittedStroke: Stroke) {
        val index = strokes.indexOf(originalStroke)
        if (index != -1) {
            strokes[index] = fittedStroke
        }
        redrawHistory()
    }

    private fun distance(p1: PointF, p2: PointF): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun setStrokeHighlighted(stroke: Stroke?) {
        stroke?.setHighlightedRecursively(true)
    }

    private fun selectStrokeAt(tapPoint: PointF): Boolean {
        var minDistance = Float.MAX_VALUE
        var closestStrokeIndex = -1

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
            val selected = currentStroke
            if (selected != null) {
                setStrokeHighlighted(selected)
                currentPaint = Paint(selected.paint)
                if (!selected.isGroup) {
                    detectShape(selected)
                }
            }
            return true
        }
        return false
    }

    private fun selectEndpointOfCurrentStroke(tapPoint: PointF): Boolean {
        val stroke = currentStroke ?: return false
        if (stroke.isGroup) return false

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
            strokes.forEach { it.setHighlightedRecursively(false) }

            val duplicatedStroke = originalStroke.newFrom()
            val bounds = originalStroke.getBounds()
            val offsetY = -bounds.height() / 2f
            val matrix = Matrix().apply { postTranslate(0f, offsetY) }
            transformStroke(duplicatedStroke, matrix)

            duplicatedStroke.setHighlightedRecursively(true)

            strokes.add(duplicatedStroke)
            selectedStrokeIdx = strokes.lastIndex

            setState(State.CHOSEN_STROKE)
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    fun groupSelectedStrokes() {
        val highlightedStrokes = strokes.filter { it.isHighlighted }
        if (highlightedStrokes.size > 1) {
            val newGroup = Stroke(highlightedStrokes.toMutableList(), defaultPaint())
            strokes.removeAll(highlightedStrokes)
            strokes.add(newGroup)
            selectedStrokeIdx = strokes.lastIndex
            newGroup.setHighlightedRecursively(true)
            setState(State.CHOSEN_STROKE)
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    fun ungroupSelectedStrokes() {
        currentStroke?.let { groupStroke ->
            if (groupStroke.isGroup) {
                val index = strokes.indexOf(groupStroke)
                if (index != -1) {
                    strokes.removeAt(index)
                    strokes.addAll(index, groupStroke.childStrokes)
                    groupStroke.childStrokes.forEach { it.setHighlightedRecursively(true) }
                    selectedStrokeIdx = -1
                    setState(State.CHOSEN_STROKE)
                    redrawHistory()
                    listener?.onStateChanged()
                }
            }
        }
    }

    fun getHighlightedStrokeCount(): Int {
        return strokes.count { it.isHighlighted }
    }

    fun isCurrentStrokeGroup(): Boolean {
        return currentStroke?.isGroup ?: false
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

    fun setStrokeSmoothness(smoothness: Int) {
        currentSmoothness = smoothness
        val selectedStroke = currentStroke
        if (selectedStroke != null && !selectedStroke.isGroup) {
            selectedStroke.forEachStroke {
                it.smoothness = smoothness
                it.applySmoothing()
                it.isModified = true
            }
            redrawHistory()
        }
        listener?.onStateChanged()
    }

    fun setColor(color: Int, applyToSelected: Boolean) {
        if (applyToSelected) {
            val selectedStroke = currentStroke
            if (selectedStroke != null && !selectedStroke.isGroup) {
                selectedStroke.forEachStroke {
                    it.isModified = true
                    it.paint.color = color
                }
                redrawHistory()
            }
        }
        currentPaint.color = color
        listener?.onStateChanged()
    }

    fun setStrokeWidth(px: Float, applyToSelected: Boolean) {
        if (applyToSelected) {
            val w = max(1f, min(120f, px))
            val selectedStroke = currentStroke
            if (selectedStroke != null) {
                selectedStroke.isModified = true
                selectedStroke.paint.strokeWidth = w
                redrawHistory()
            }
        }
        currentPaint.strokeWidth = max(1f, min(120f, px))
        listener?.onStateChanged()
    }

    private fun redrawHistory(canvas: Canvas? = null) {
        val c = canvas ?: backingCanvas ?: return
        if (canvas == null) {
            c.drawColor(Color.WHITE, PorterDuff.Mode.SRC)
        }

        for ((index, s) in strokes.withIndex()) {
            val opacityMultiplier = when (currentState) {
                State.NORMAL_DRAWING -> 1.0f
                State.CHOSEN_STROKE, State.STROKE_EDITING -> if (index != selectedStrokeIdx) 0.25f else 1.0f
            }

            val drawEndpoints = currentState == State.STROKE_EDITING && index == selectedStrokeIdx
            drawStroke(c, s, opacityMultiplier, 1.0f, drawEndpoints)
        }

        if (canvas == null) invalidate()
    }

    private fun drawStroke(
        canvas: Canvas,
        stroke: Stroke,
        cumulativeOpacityMultiplier: Float,
        cumulativeWidthMultiplier: Float,
        drawEndpoints: Boolean
    ) {
        if (stroke.isGroup) {
            val groupThicknessMultiplier = stroke.paint.strokeWidth / 10f
            val newTotalWidthMultiplier = cumulativeWidthMultiplier * groupThicknessMultiplier
            val newCumulativeOpacityMultiplier = cumulativeOpacityMultiplier * (stroke.paint.alpha / 255f)

            stroke.childStrokes.forEach { childStroke ->
                drawStroke(canvas, childStroke, newCumulativeOpacityMultiplier, newTotalWidthMultiplier, drawEndpoints)
            }
        } else {
            if (stroke.points.size >= 2) {
                val path = Path()
                val firstPoint = stroke.points.first().point
                path.moveTo(firstPoint.x, firstPoint.y)
                for (i in 1 until stroke.points.size) {
                    val point = stroke.points[i].point
                    path.lineTo(point.x, point.y)
                }

                val finalPaint = Paint(stroke.paint)
                path.transform(globalTransform)
                val currentScale = getScaleFromMatrix(globalTransform)

                finalPaint.strokeWidth *= cumulativeWidthMultiplier * currentScale
                finalPaint.alpha = (finalPaint.alpha * cumulativeOpacityMultiplier).toInt()

                if (stroke.isHighlighted) {
                    val haloPaintToUse = Paint(haloPaint)
                    haloPaintToUse.strokeWidth = finalPaint.strokeWidth + haloOffset * currentScale
                    canvas.drawPath(path, haloPaintToUse)
                }

                if (drawEndpoints && !twoFingerGestureOccured && !threeFingerGestureOccured) {
                    val radius = finalPaint.strokeWidth * 2f
                    val endpointPaint = Paint().apply {
                        style = Paint.Style.FILL
                        color = Color.GREEN
                    }

                    if (editingPointIndex != -1 && editingPointIndex < stroke.points.size) {
                        val pointToHighlight = stroke.points[editingPointIndex].point
                        val transformedPoint = floatArrayOf(pointToHighlight.x, pointToHighlight.y)
                        globalTransform.mapPoints(transformedPoint)
                        canvas.drawCircle(transformedPoint[0], transformedPoint[1], radius, endpointPaint)
                    }
                }

                canvas.drawPath(path, finalPaint)
            }
        }
    }

    private fun defaultPaint() = Paint().apply {
        isAntiAlias = true
        isDither = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK
        strokeWidth = 12f
    }

    override fun onSingleTapEnd(event: MotionEvent): Boolean {
        performClick()
        val worldPoint = toWorldCoordinates(event.x, event.y)
        when (currentState) {
            State.NORMAL_DRAWING -> {
                if (selectStrokeAt(worldPoint)) {
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
        val worldPoint = toWorldCoordinates(downPoint.x, downPoint.y)
        when (currentState) {
            State.NORMAL_DRAWING -> {
                touchStart(downPoint.x, downPoint.y)
            }
            State.CHOSEN_STROKE -> {
                if (selectEndpointOfCurrentStroke(worldPoint)) {
                    setState(State.STROKE_EDITING)
                } else {
                    currentStroke?.let {
                        if (it.isGroup) {
                            backedUpGroupStroke = it.newFrom()
                            backedUpGroupStroke?.setHighlightedRecursively(true)
                            initialTouchX = event.x
                            initialTouchY = event.y
                        }
                    }
                }
            }
            State.STROKE_EDITING -> {
                if (selectEndpointOfCurrentStroke(worldPoint)) {
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
        selectionCircle = null
        backedUpGroupStroke = null

        if (threeFingerGestureOccured) {
            val highlightedStrokes = strokes.filter { it.isHighlighted }
            if (highlightedStrokes.size == 1) {
                val singleHighlightedStroke = highlightedStrokes.first()
                val index = strokes.indexOf(singleHighlightedStroke)
                if (index != -1) {
                    selectedStrokeIdx = index
                    currentPaint = Paint(singleHighlightedStroke.paint)
                    setState(State.CHOSEN_STROKE)
                }
            } else if (highlightedStrokes.isNotEmpty()) {
                setState(State.CHOSEN_STROKE)
            }
            threeFingerGestureOccured = false
            listener?.onStateChanged()
        }


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

        val inverseGlobalTransform = Matrix()
        globalTransform.invert(inverseGlobalTransform)

        when (currentState) {
            State.NORMAL_DRAWING -> touchMove(event.x, event.y)
            State.STROKE_EDITING -> {
                val delta = floatArrayOf(dx, dy)
                inverseGlobalTransform.mapVectors(delta) // Transform delta into world-space
                moveEditingPoint(delta[0], delta[1])
            }
            State.CHOSEN_STROKE -> {
                currentStroke?.let { current ->
                    if (current.isGroup) {
                        backedUpGroupStroke?.let { backup ->
                            current.copyFrom(backup)

                            val bounds = backup.getBounds()
                            val centerX = bounds.centerX()
                            val centerY = bounds.centerY()
                            val centerScreen = toScreenCoordinates(centerX, centerY)
                            val initialHeight = centerScreen.y - initialTouchY  // yes, it can be negative

                            val totalDx = event.x - initialTouchX
                            var totalDy = event.y - initialTouchY

                            // Transform Matrix - in screen-space
                            val screenspaceTransform = Matrix()

                            // Applying translation, in screen-space
                            screenspaceTransform.setTranslate(totalDx, 0f)

                            // Applying scaling, in screen-space
                            if (initialHeight != 0f) {
                                val newHeight = initialHeight - totalDy
                                val scaleY = newHeight / initialHeight
                                screenspaceTransform.preScale(1.0f, scaleY, centerScreen.x, centerScreen.y)
                            }

                            // Calculate the world-space transform matrix to be applied to stroke points
                            val worldspaceTransform = Matrix()
                            worldspaceTransform.set(globalTransform)                                // 1. First thing, transform everything to screen-space
                            postTransform(worldspaceTransform,screenspaceTransform)  // 2. Next, apply our transformation, in screen-space
                            postTransform(worldspaceTransform,inverseGlobalTransform)// 3. Finally, transform back to world-space

                            // Applying the transformations, in world-space
                            transformStroke(current, worldspaceTransform)
                        }
                    }
                }
            }
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
        val invertedGlobal = Matrix()
        globalTransform.invert(invertedGlobal)
        val worldDelta = floatArrayOf(dx, dy)
        invertedGlobal.mapVectors(worldDelta)

        when (currentState) {
            State.NORMAL_DRAWING -> {
                val mid = midpoint(event)
                globalTransform.preTranslate(worldDelta[0], worldDelta[1])
                globalTransform.preScale(scale, scale, mid.x, mid.y)
                globalTransform.preRotate(rotate, mid.x, mid.y)
                redrawHistory()
            }
            State.CHOSEN_STROKE, State.STROKE_EDITING -> {
                currentStroke?.let {

                    val deltaMatrix = Matrix()
                    val bounds = it.getBounds()
                    val centerX = bounds.centerX()
                    val centerY = bounds.centerY()
                    deltaMatrix.postScale(scale, scale, centerX, centerY)
                    deltaMatrix.postRotate(rotate, centerX, centerY)
                    deltaMatrix.postTranslate(worldDelta[0], worldDelta[1])
                    transformStroke(it, deltaMatrix)
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
        val pt3 = points.first { it != pt1 && it != pt2 }

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
            val p1 = toWorldCoordinates(event.getX(0), event.getY(0))
            val p2 = toWorldCoordinates(event.getX(1), event.getY(1))
            val p3 = toWorldCoordinates(event.getX(2), event.getY(2))

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