package com.example.bettersketch

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.createBitmap
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
    fun onShapeDetected(shapeFitResult: ShapeFitResult, polylineFit: PolyLineFitter.FitResult?)
    fun onNoShapeDetected()
}

private enum class State {
    NORMAL_DRAWING,
    CHOSEN_STROKE_IN_NORMAL_MODE,
    STROKE_EDITING
}

class DrawingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs), CustomGestureDetector.OnGestureListener {

    var listener: DrawingViewListener? = null
    var shapeDetectionListener: ShapeDetectionListener? = null
    var mainGestureHelper: ButtonAugmentedGestureHelper? = null
        set(value) {
            field = value
            customGestureDetector.mainGestureHelper = value  // Pass it to the detector
        }

    private var currentState = State.NORMAL_DRAWING
    private var globalSetStateIsNeeded = false
    private fun setState(newState: State) {
        if (currentState != newState) {
            currentState = newState
        }
        redrawHistory() // Redraw in any case
        listener?.onStateChanged() // This is the only place this is called
        globalSetStateIsNeeded = false
    }

    // Draw mask constants
    companion object {
        private const val MASK_DRAW_HALOS_AND_MARKERS = 0x02
        private const val MASK_DRAW_STROKE_ITSELF = 0x01
        private const val MASK_DRAW_ALL = MASK_DRAW_HALOS_AND_MARKERS or MASK_DRAW_STROKE_ITSELF
    }

    // Drawing state
    private var backingBitmap: Bitmap? = null
    private var backingCanvas: Canvas? = null

    // Stroke in progress
    private var strokeInProgress: Stroke? = null

    private var strokesToTransform = mutableSetOf<Stroke>()

    var currentPaint = defaultPaint()
    var currentSmoothness: Int = 0

    // Data
    val strokes = mutableListOf<Stroke>()
    private var editingPointIndex: Int = -1
    private var editingPointInitialWeights: List<Float>? = null
    private var addingAnchorPointIndex: Int = -1

    private data class AnchorPointToEdit(
        val stroke: Stroke,
        val pointIndex: Int,
        val snapshotUnsmoothedPoints: MutableList<PathPoint>,
        val weights: List<Float>
    )

    private var anchorPointsToEdit = mutableListOf<AnchorPointToEdit>()
    // Transformation state
    private val globalTransform = Matrix() // Matrix for transforming from WORLD-SPACE to SCREEN-SPACE (a.k.a the VIEW MATRIX)
    private var dragGestureHasEnded = false
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
    private var advancedGestureInProgress = false
    private var selectionGestureInProgress = false
    private var addAnchorPointGestureInProgress = false
    private var isFingerOverRemoveButton = false


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
    public val getHighlightedStrokes: List<Stroke> get() = strokes.filter { it.isHighlighted }
    public val singleHighlightedStroke: Stroke? get() {
        // Return the single highlighted stroke if exactly one is highlighted
        val highlighted = getHighlightedStrokes
        return when (highlighted.size) {
            1 -> highlighted.first()
            else -> null
        }
    }

    fun isEditing(): Boolean {
        return currentState == State.CHOSEN_STROKE_IN_NORMAL_MODE || currentState == State.STROKE_EDITING
    }

    fun isAnchorPointDragging(): Boolean {
        return (editingPointIndex != -1)
    }

    fun areAnyHighlightedStrokesModified(): Boolean {
        val highlightedAndModifiedStrokes = strokes.filter { it.isHighlighted && it.isModified }
        return highlightedAndModifiedStrokes.isNotEmpty()
    }

    fun currentStrokeHasPolylineData(): Boolean {
        return singleHighlightedStroke?.polylineIndices?.isNotEmpty() ?: false
    }

    private fun findClosestPointOnCurve(tapPoint: PointF): Int {
        val stroke = singleHighlightedStroke ?: return -1
        if (stroke.isGroup) return -1

        var closestDist = Float.MAX_VALUE
        var closestPointIndex = -1

        stroke.pointsForDrawing.forEachIndexed { index, pathPoint ->
            val d = distance(pathPoint.point, tapPoint)
            if (d < closestDist) {
                closestDist = d
                closestPointIndex = index
            }
        }

        return closestPointIndex
    }

    fun addAnchorPointAtIndex(index: Int) {
        val stroke = singleHighlightedStroke ?: return
        if (stroke.isGroup) return
        if (index == -1) return

        // Initialize polylineIndices if empty (first anchor being added)
        if (stroke.polylineIndices.isEmpty()) {
            // Add first and last points as anchors
            stroke.polylineIndices.add(0)
            stroke.polylineIndices.add(stroke.unsmoothedPoints.size - 1)
        }

        // Find where to insert the new anchor in the sorted polylineIndices list
        var insertPosition = stroke.polylineIndices.size
        for (i in stroke.polylineIndices.indices) {
            if (index < stroke.polylineIndices[i]) {
                insertPosition = i
                break
            } else if (index == stroke.polylineIndices[i]) {
                // Already an anchor at this position, don't add
                return
            }
        }

        // Insert the new anchor
        stroke.polylineIndices.add(insertPosition, index)
        stroke.isModified = true

        // Regenerate the stroke
        stroke.regenerateInterpolatedPolylinePoints()
        stroke.applySmoothing()
    }

    fun removeAnchorPointAtEditingIndex() {
        if (anchorPointsToEdit.isEmpty()) return

        // Remove anchor points from all affected strokes
        anchorPointsToEdit.forEach { anchor ->
            val stroke = anchor.stroke
            if (stroke.polylineIndices.isEmpty()) return@forEach

            // Find which polyline index corresponds to the editing point
            val polylineIndexToRemove = stroke.polylineIndices.indexOfFirst { it == anchor.pointIndex }
            if (polylineIndexToRemove == -1) return@forEach

            // Don't allow removing if it would leave fewer than 2 vertices
            if (stroke.polylineIndices.size <= 2) return@forEach

            // Restore unsmoothedPoints to the snapshot
            stroke.unsmoothedPoints.clear()
            stroke.unsmoothedPoints.addAll(
                anchor.snapshotUnsmoothedPoints.map {
                    PathPoint(PointF(it.point.x, it.point.y), it.distance)
                }
            )

            // Recalculate distances
            val (recalculatedPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(
                stroke.unsmoothedPoints.map { it.point }
            )
            stroke.unsmoothedPoints.clear()
            stroke.unsmoothedPoints.addAll(recalculatedPoints)
            stroke.totalDistance = newTotalDistance

            // Remove the polyline index
            stroke.polylineIndices.removeAt(polylineIndexToRemove)
            stroke.isModified = true

            // Regenerate the stroke
            stroke.regenerateInterpolatedPolylinePoints()
            stroke.applySmoothing()
        }

        // Clear editing state
        anchorPointsToEdit.clear()
        editingPointIndex = -1
        editingPointInitialWeights = null

        setState(currentState) // Refresh UI and Canvas
    }

    fun exitEditingMode() {
        deselectAndDeHighlight()
        editingPointInitialWeights = null
        setState(State.NORMAL_DRAWING)
    }

    fun undoModificationsForHighlightedStrokes() {
        val highlightedStrokes = getHighlightedStrokes
        highlightedStrokes.forEach { stroke ->
            stroke.forEachStroke {
                it.unsmoothedPoints.clear()
                it.unsmoothedPoints.addAll(it.originalPoints.map { p -> PathPoint(PointF(p.point.x, p.point.y), p.distance) })
                val (recalculatedUnsmoothedPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(it.unsmoothedPoints.map { p -> p.point })
                it.unsmoothedPoints.clear()
                it.unsmoothedPoints.addAll(recalculatedUnsmoothedPoints)
                it.totalDistance = newTotalDistance
                it.applySmoothing()
                it.isModified = false
            }
        }
        setState(currentState)
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

        // Ensure all strokes are up-to-date before drawing
        strokes.forEach { stroke ->
            if (stroke.needsToRegenerate)
                stroke.regenerateUnsmoothedPointsFromAnalytical()
        }

        if (isEditing()) {
            canvas.drawColor(Color.argb(25, 255, 165, 0)) // 10% opacity orange
        }

        // 1. Draw halos and markers first (if in editing mode)
        if (isEditing()) {
            // Draw halos and markers for highlighted strokes only
            strokes.forEach { stroke ->
                if (stroke.isHighlighted) {
                    drawStroke(canvas, stroke, 1.0f, 1.0f, true, MASK_DRAW_HALOS_AND_MARKERS)
                }
            }
        }

        // 2. Draw the backing bitmap at 25% opacity (or full opacity if not editing)
        backingBitmap?.let {
            if (isEditing()) {
                val bitmapPaint = Paint().apply { alpha = (255 * 0.25f).toInt() }
                canvas.drawBitmap(it, 0f, 0f, bitmapPaint)
            } else {
                canvas.drawBitmap(it, 0f, 0f, null)
            }
        }

        // 3. Draw the highlighted strokes themselves (if in editing mode)
        if (isEditing()) {
            strokes.forEach { stroke ->
                if (stroke.isHighlighted) {
                    drawStroke(canvas, stroke, 1.0f, 1.0f, true, MASK_DRAW_STROKE_ITSELF)
                }
            }
        }

        // 4. Draw the "live" part (the new stroke being created) on top, with transformation.
        if (currentState == State.NORMAL_DRAWING && strokeInProgress != null) {
            drawStroke(canvas, strokeInProgress!!, 1.0f, 1.0f, false, MASK_DRAW_ALL)
        }

        selectionCircle?.let {
            val transformedPath = Path(it.third)
            transformedPath.transform(globalTransform)
            canvas.drawPath(transformedPath, selectionPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Check if this gesture started from a button
        val gestureTag = mainGestureHelper?.activeGestureTag

        selectionGestureInProgress      = (gestureTag == MainActivity.tagSelectionGesture)
        addAnchorPointGestureInProgress = (gestureTag == MainActivity.tagAddAnchorPointGesture)

        advancedGestureInProgress = selectionGestureInProgress || addAnchorPointGestureInProgress

        // Check if finger is over the remove anchor point button (when in editing mode and dragging an anchor)
        if (currentState == State.STROKE_EDITING && editingPointIndex != -1) {
            isFingerOverRemoveButton = isEventOverRemoveAnchorButton(event) and (addAnchorPointGestureInProgress == false)
        } else {
            isFingerOverRemoveButton = false
        }

        customGestureDetector.onTouchEvent(event)
        return true
    }

    private fun isEventOverRemoveAnchorButton(event: MotionEvent): Boolean {
        // Get the button from MainActivity
        val activity = context as? MainActivity ?: return false
        val button = activity.findViewById<View>(R.id.btnRemoveAnchorPoint) ?: return false

        if (button.visibility != View.VISIBLE) return false

        // Get button bounds in screen coordinates
        val buttonLocation = IntArray(2)
        button.getLocationOnScreen(buttonLocation)

        // Get event coordinates in screen coordinates
        val screenX = event.rawX
        val screenY = event.rawY

        // Check if event is within button bounds
        val isOver = screenX >= buttonLocation[0] &&
                screenX <= buttonLocation[0] + button.width &&
                screenY >= buttonLocation[1] &&
                screenY <= buttonLocation[1] + button.height

        // Provide visual feedback
        button.isPressed = isOver

        return isOver
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

            s.unsmoothedPoints.forEach { pathPoint ->
                val point = floatArrayOf(pathPoint.point.x, pathPoint.point.y)
                matrix.mapPoints(point)
                pathPoint.point.set(point[0], point[1])
            }
            val (recalculatedUnsmoothedPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(s.unsmoothedPoints.map { it.point })
            s.unsmoothedPoints.clear()
            s.unsmoothedPoints.addAll(recalculatedUnsmoothedPoints)
            s.totalDistance = newTotalDistance

            s.interpolatedPolylinePoints.forEach { pathPoint ->
                val point = floatArrayOf(pathPoint.point.x, pathPoint.point.y)
                matrix.mapPoints(point)
                pathPoint.point.set(point[0], point[1])
            }
            val (recalculatedInterpolatedPolylinePoints, _) = Stroke.calculatePathPointsWithDistances(s.interpolatedPolylinePoints.map { it.point })
            s.interpolatedPolylinePoints.clear()
            s.interpolatedPolylinePoints.addAll(recalculatedInterpolatedPolylinePoints)

            s.applySmoothing()
        }
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
            if( it.totalDistance > 20f )
                commitStrokeInProgress()
            else {
                strokeInProgress = null
                setState(currentState) // causes redraw
            }
        }
    }

    private fun commitStrokeInProgress() {
        strokeInProgress?.let { currentStrokeInProgress ->
            val preprocessedUnsmoothedPoints = Stroke.preprocessStroke(currentStrokeInProgress.unsmoothedPoints)
            val (finalUnsmoothedPoints, totalDistanceForNewStroke) = Stroke.calculatePathPointsWithDistances(preprocessedUnsmoothedPoints.map { it.point })
            val newStroke = Stroke(finalUnsmoothedPoints, Paint(currentStrokeInProgress.paint), totalDistanceForNewStroke, currentStrokeInProgress.smoothness)
            strokes.add(newStroke)
            strokeInProgress = null
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

        // Only compute polyline fit if the stroke doesn't already have polyline indices
        val polylineFitResult = if (stroke.polylineIndices.isEmpty()) {
            // Get the polyline fit - use the ORIGINAL stroke, not the uniformly sampled one
            val result = ShapeFitter.polylineFit(stroke, stroke)

            // Update the original stroke's polylineIndices if renderAsPolyline is false
            if (result != null && !stroke.renderAsPolyline) {
                stroke.polylineIndices.clear()
                stroke.polylineIndices.addAll(result.fittedStroke.polylineIndices)
            }

            result
        } else {
            // Stroke already has polyline data, don't recompute
            null
        }

        // Get the best shape fit - use the uniformly sampled stroke for better fitting
        val shapeFitResult = ShapeFitter.shapeFit(stroke, strokeForFitting)

        if (shapeFitResult != null) {
            shapeDetectionListener?.onShapeDetected(shapeFitResult, polylineFitResult)
        } else {
            shapeDetectionListener?.onNoShapeDetected()
        }
    }

    fun replaceWithShape(originalStroke: Stroke, fittedStroke: Stroke) {
        val index = strokes.indexOf(originalStroke)
        if (index != -1) {
            strokes[index] = fittedStroke
            fittedStroke.setHighlightedRecursively(true)
        }
        setState(State.CHOSEN_STROKE_IN_NORMAL_MODE)
    }

    fun toggleCurrentStrokePolyline() {
        singleHighlightedStroke?.let {
            it.togglePolylineRepresentation()
            setState(currentState) // Refresh UI and Canvas
        }
    }

    private fun distance(p1: PointF, p2: PointF): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun setStrokeHighlighted(stroke: Stroke?) {
        stroke?.setHighlightedRecursively(true)
    }

    private fun deselectAndDeHighlight() {
        // Note: doesn't cause a redraw on its own
        editingPointIndex = -1 // Don't forget this one...
        strokes.forEach { it.setHighlightedRecursively(false) } // Second - de-highlight
    }

    private fun selectStrokeAt(tapPointScreen: PointF): Boolean {
        val tapPointWorld = toWorldCoordinates(tapPointScreen.x, tapPointScreen.y)
        var minDistance = Float.MAX_VALUE
        var closestStrokeIndex = -1
        var closestPointWorld: PointF? = null

        deselectAndDeHighlight() // do this first thing

        strokes.forEachIndexed { index, stroke ->
            // Ensure stroke is up-to-date before accessing its points
            if (stroke.needsToRegenerate) {
                stroke.regenerateUnsmoothedPointsFromAnalytical()
                stroke.needsToRegenerate = false
            }

            stroke.forEachStroke { s ->
                for (pathPoint in s.pointsForDrawing) {
                    val d = distance(pathPoint.point, tapPointWorld)
                    if (d < minDistance) {
                        minDistance = d
                        closestStrokeIndex = index
                        closestPointWorld = pathPoint.point
                    }
                }
            }
        }

        if (closestStrokeIndex != -1 && closestPointWorld != null) {
            val closestPointScreen = toScreenCoordinates(closestPointWorld!!.x, closestPointWorld!!.y)
            val screenDistance = distance(closestPointScreen, tapPointScreen)
            val screenLongDimension = max(width, height)

            if (screenDistance > screenLongDimension / 16f) {
                return false
            }

            val selected = strokes.getOrNull(closestStrokeIndex)
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
        if (addAnchorPointGestureInProgress) return false

        // Clear previous editing state
        anchorPointsToEdit.clear()
        editingPointIndex = -1
        editingPointInitialWeights = null

        // Find the absolute nearest ANCHOR point (not just any point) across all highlighted strokes
        val nearestResult = findClosestAnchorPointAcrossAllStrokes(tapPoint) ?: return false
        val (primaryStroke, primaryIndex) = nearestResult

        // Store the primary stroke for drawing the green circle
        editingPointIndex = primaryIndex

        // Get the primary point from UNSMOOTHED points (this is an anchor point)
        val primaryPoint = primaryStroke.unsmoothedPoints[primaryIndex].point

        // Now find all co-located anchor points on other strokes
        val highlightedStrokes = getHighlightedStrokes

        highlightedStrokes.forEach { stroke ->
            stroke.forEachStroke { s ->
                if (!s.isGroup && s.polylineIndices.isNotEmpty()) {
                    // Find the closest ANCHOR point on this stroke to the primary point
                    var closestAnchorIdx = -1
                    var closestDist = Float.MAX_VALUE

                    s.polylineIndices.forEach { anchorIndex ->
                        if (anchorIndex >= 0 && anchorIndex < s.unsmoothedPoints.size) {
                            val anchorPoint = s.unsmoothedPoints[anchorIndex].point
                            val d = distance(anchorPoint, primaryPoint)
                            if (d < closestDist) {
                                closestDist = d
                                closestAnchorIdx = anchorIndex
                            }
                        }
                    }

                    // Check if this anchor point is within the stroke width threshold
                    if (closestAnchorIdx != -1 && closestDist < s.paint.strokeWidth) {
                        // Save snapshot of unsmoothed points before editing
                        val snapshot = s.unsmoothedPoints.map {
                            PathPoint(PointF(it.point.x, it.point.y), it.distance)
                        }.toMutableList()

                        // Calculate weights for this anchor point
                        val weights = calculateWeightsForAnchorPoint(s, closestAnchorIdx)

                        anchorPointsToEdit.add(
                            AnchorPointToEdit(
                                stroke = s,
                                pointIndex = closestAnchorIdx,
                                snapshotUnsmoothedPoints = snapshot,
                                weights = weights
                            )
                        )
                    }
                }
            }
        }

        return anchorPointsToEdit.isNotEmpty()
    }

    fun deleteStrokes() {
        val highlightedStrokes = getHighlightedStrokes
        
        if (highlightedStrokes.isNotEmpty()) {
            // Check if we should revert instead of delete
            if (highlightedStrokes.size == 1) {
                val stroke = highlightedStrokes.first()
                if (stroke.analyticalShapeType != AnalyticalShapeType.NONE || stroke.renderAsPolyline) {
                    revertStrokeToOriginal(stroke)
                    setState(currentState) // Refresh UI and Canvas
                    return
                }
            }
            // Delete all highlighted strokes
            strokes.removeAll(highlightedStrokes)
        } else if (strokes.isNotEmpty()) {
            // Delete the last stroke as fallback
            val lastStroke = strokes.last()
            if (lastStroke.analyticalShapeType != AnalyticalShapeType.NONE || lastStroke.renderAsPolyline) {
                revertStrokeToOriginal(lastStroke)
                setState(currentState)
                return
            }
            strokes.removeAt(strokes.lastIndex)
        }

        if( currentState == State.NORMAL_DRAWING )
        {
            deselectAndDeHighlight() // Deselect everything
            setState(currentState) // Update screen and UI
        } else {
            // Highlight the last stroke
            if (strokes.isNotEmpty()) {
                strokes.last().setHighlightedRecursively(true)
                setState(State.CHOSEN_STROKE_IN_NORMAL_MODE)
            } else
                setState(State.NORMAL_DRAWING)
        }
    }

    private fun revertStrokeToOriginal(stroke: Stroke) {
        // Reset analytical shape properties
        stroke.analyticalShapeType = AnalyticalShapeType.NONE
        stroke.renderAsPolyline = false
        stroke.needsToRegenerate = false
        stroke.polylineIndices.clear()
        stroke.shapeParameterPoints.clear()

        // Restore from originalPoints
        stroke.unsmoothedPoints.clear()
        stroke.unsmoothedPoints.addAll(
            stroke.originalPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) }
        )

        // Recalculate distances
        val (recalculatedPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(
            stroke.unsmoothedPoints.map { it.point }
        )
        stroke.unsmoothedPoints.clear()
        stroke.unsmoothedPoints.addAll(recalculatedPoints)
        stroke.totalDistance = newTotalDistance

        // Reapply smoothing
        stroke.applySmoothing()

        // Re-detect shape for the reverted stroke
        detectShape(stroke)
    }

    fun duplicateCurrentStroke() {
        singleHighlightedStroke?.let { originalStroke ->
            strokes.forEach { it.setHighlightedRecursively(false) }

            val duplicatedStroke = originalStroke.newFrom() // Create a copy of the original stroke
            transformStroke(duplicatedStroke, globalTransform) // Bring it into screen-space

            val bounds = duplicatedStroke.getBounds() // Calculate the bounds, in screen-space
            val offsetY = -max(bounds.width(), bounds.height()) / 2f
            val matrix = Matrix().apply { postTranslate(0f, offsetY) }
            transformStroke(duplicatedStroke, matrix) // Offset in screen-space

            val inverseGlobalTransform = Matrix()
            globalTransform.invert(inverseGlobalTransform)
            transformStroke(duplicatedStroke, inverseGlobalTransform) // Bring it back into world-space

            // Explicitly deleting "undo" history:
            duplicatedStroke.originalPoints.clear()
            duplicatedStroke.originalPoints.addAll(duplicatedStroke.unsmoothedPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })
            duplicatedStroke.isModified = false

            // Highlighting
            duplicatedStroke.setHighlightedRecursively(true)

            strokes.add(duplicatedStroke)

            setState(State.CHOSEN_STROKE_IN_NORMAL_MODE)
        }
    }

    fun groupSelectedStrokes() {
        val highlightedStrokes = getHighlightedStrokes
        if (highlightedStrokes.size > 1) {
            val newGroup = Stroke(highlightedStrokes.toMutableList(), defaultPaint())
            strokes.removeAll(highlightedStrokes)
            strokes.add(newGroup)
            newGroup.setHighlightedRecursively(true)
            setState(State.CHOSEN_STROKE_IN_NORMAL_MODE)
        }
    }

    fun ungroupSelectedStrokes() {
        singleHighlightedStroke?.let { groupStroke ->
            if (groupStroke.isGroup) {
                val index = strokes.indexOf(groupStroke)
                if (index != -1) {
                    strokes.removeAt(index)
                    strokes.addAll(index, groupStroke.childStrokes)
                    groupStroke.childStrokes.forEach { it.setHighlightedRecursively(true) }
                    setState(State.CHOSEN_STROKE_IN_NORMAL_MODE)
                }
            }
        }
    }

    fun getHighlightedStrokeCount(): Int {
        return strokes.count { it.isHighlighted }
    }

    fun isCurrentStrokeGroup(): Boolean {
        return singleHighlightedStroke?.isGroup ?: false
    }

    private fun moveEditingPoint(dx: Float, dy: Float) {
        // Move all co-located anchor points
        anchorPointsToEdit.forEach { anchor ->
            anchor.stroke.isModified = true

            // Apply weighted transformation to unsmoothedPoints
            if (anchor.weights.size == anchor.stroke.unsmoothedPoints.size) {
                anchor.stroke.unsmoothedPoints.forEachIndexed { index, pathPoint ->
                    pathPoint.point.offset(dx * anchor.weights[index], dy * anchor.weights[index])
                }
            }

            // Recalculate distances for unsmoothed points
            val (recalculatedUnsmoothedPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(
                anchor.stroke.unsmoothedPoints.map { it.point }
            )
            anchor.stroke.unsmoothedPoints.clear()
            anchor.stroke.unsmoothedPoints.addAll(recalculatedUnsmoothedPoints)
            anchor.stroke.totalDistance = newTotalDistance

            // Regenerate interpolated polyline points and apply smoothing
            anchor.stroke.regenerateInterpolatedPolylinePoints()
            anchor.stroke.applySmoothing()
        }

        invalidate()
    }
    
    fun setStrokeSmoothness(smoothness: Int) {
        currentSmoothness = smoothness
        val selectedStroke = singleHighlightedStroke
        if (selectedStroke != null && !selectedStroke.isGroup) {
            selectedStroke.forEachStroke {
                it.smoothness = smoothness
                it.applySmoothing()
                it.isModified = true
            }
            invalidate()
        }
    }

    fun setColor(color: Int, applyToSelected: Boolean) {
        if (applyToSelected) {
            val highlightedStrokes = getHighlightedStrokes
            
            if (highlightedStrokes.isNotEmpty()) {
                // Get the common color from the first stroke
                val firstStrokeColor = highlightedStrokes.first().getSingleColor()
                
                // Only apply if the first stroke has a uniform color
                if (firstStrokeColor != null) {
                    // Check if ALL highlighted strokes have the SAME single color
                    val allHaveSameColor = highlightedStrokes.all { stroke ->
                        stroke.getSingleColor() == firstStrokeColor
                    }
                    
                    if (allHaveSameColor) {
                        highlightedStrokes.forEach { stroke ->
                            stroke.setColor(color)
                        }
                        redrawHistory()
                    }
                }
            }
        }
        currentPaint.color = color
        invalidate()
    }

    fun setStrokeWidth(px: Float, applyToSelected: Boolean) {
        if (applyToSelected && singleHighlightedStroke != null) {
            val w = max(1f, min(120f, px))
            val selectedStroke = singleHighlightedStroke
            if (selectedStroke != null) {
                selectedStroke.isModified = true
                selectedStroke.paint.strokeWidth = w
                redrawHistory()
            }
        }
        currentPaint.strokeWidth = max(1f, min(120f, px))
        invalidate()
    }

    // Draw stokes to the backing bitmap (at full opacity) + invalidate
    private fun redrawHistory() {
        val c = backingCanvas ?: return
        c.drawColor(Color.WHITE, PorterDuff.Mode.SRC)

        // Draw all non-highlighted strokes to the backing bitmap
        strokes.forEach { stroke ->
            val shouldDrawToBitmap = (currentState == State.NORMAL_DRAWING) || !stroke.isHighlighted
            if (shouldDrawToBitmap) {
                drawStroke(c, stroke, 1.0f, 1.0f, false, MASK_DRAW_ALL)
            }
        }

        invalidate()
    }

    private fun drawStroke(
        canvas: Canvas,
        stroke: Stroke,
        cumulativeOpacityMultiplier: Float,
        cumulativeWidthMultiplier: Float,
        drawEndpoints: Boolean,
        drawMask: Int = MASK_DRAW_ALL
    ) {
        if (stroke.isGroup) {
            val groupThicknessMultiplier = stroke.paint.strokeWidth / 10f
            val newTotalWidthMultiplier = cumulativeWidthMultiplier * groupThicknessMultiplier
            val newCumulativeOpacityMultiplier = cumulativeOpacityMultiplier * (stroke.paint.alpha / 255f)

            // Ensure stroke is regenerated if needed
            if (stroke.needsToRegenerate) {
                stroke.regenerateUnsmoothedPointsFromAnalytical()
                stroke.needsToRegenerate = false
            }

            stroke.childStrokes.forEach { childStroke ->
                drawStroke(canvas, childStroke, newCumulativeOpacityMultiplier, newTotalWidthMultiplier, drawEndpoints, drawMask)
            }
        } else {
            if (stroke.pointsForDrawing.size >= 2) {
                val path = Path()
                val firstPoint = stroke.pointsForDrawing.first().point
                path.moveTo(firstPoint.x, firstPoint.y)
                for (i in 1 until stroke.pointsForDrawing.size) {
                    val point = stroke.pointsForDrawing[i].point
                    path.lineTo(point.x, point.y)
                }

                val finalPaint = Paint(stroke.paint)
                path.transform(globalTransform)
                val currentScale = getScaleFromMatrix(globalTransform)

                finalPaint.strokeWidth *= cumulativeWidthMultiplier * currentScale
                finalPaint.alpha = (finalPaint.alpha * cumulativeOpacityMultiplier).toInt()

                val haloPaintToUse = Paint(haloPaint)
                haloPaintToUse.strokeWidth = finalPaint.strokeWidth * 3f

                // Draw halos and markers if mask permits
                if ((drawMask and MASK_DRAW_HALOS_AND_MARKERS) != 0) {
                    if (stroke.isHighlighted) {
                        canvas.drawPath(path, haloPaintToUse)

                        // Draw circles for associated polyline points
                        if (stroke.polylineIndices.isNotEmpty()) {
                            val associatedPoints = stroke.getAssociatedPolylinePointsOnSmoothedCurve()
                            val vertexPaint = Paint().apply {
                                style = Paint.Style.FILL
                                color = Color.BLUE
                            }
                            val radius = haloPaintToUse.strokeWidth / 2f

                            associatedPoints.forEach { point ->
                                val transformedPoint = floatArrayOf(point.x, point.y)
                                globalTransform.mapPoints(transformedPoint)
                                canvas.drawCircle(transformedPoint[0], transformedPoint[1], radius, vertexPaint)
                            }
                        }

                        // Draw red circle for adding anchor point preview
                        if (addAnchorPointGestureInProgress && addingAnchorPointIndex != -1 &&
                            addingAnchorPointIndex < stroke.pointsForDrawing.size) {
                            val previewPoint = stroke.pointsForDrawing[addingAnchorPointIndex].point
                            val transformedPoint = floatArrayOf(previewPoint.x, previewPoint.y)
                            globalTransform.mapPoints(transformedPoint)

                            val previewPaint = Paint().apply {
                                style = Paint.Style.FILL
                                color = Color.RED
                            }
                            val radius = haloPaintToUse.strokeWidth / 1.5f
                            canvas.drawCircle(transformedPoint[0], transformedPoint[1], radius, previewPaint)
                        }
                    }

                    // Draw green circles for all anchor points being edited
                    // Check independently of drawEndpoints and gesture flags
                    if (!twoFingerGestureOccured && !threeFingerGestureOccured) { // Do not draw highlighted endpoint during a 2- or 3- finger gesture
                        val radius = haloPaintToUse.strokeWidth/2f
                        val endpointPaint = Paint().apply {
                            style = Paint.Style.FILL
                            color = Color.GREEN
                        }

                        // Check if this stroke has any anchor points being edited
                        anchorPointsToEdit.forEach { anchor ->
                            if (anchor.stroke == stroke && anchor.pointIndex < stroke.pointsForDrawing.size) {
                                // Draw green circle on the SMOOTHED position of this anchor point
                                val pointToHighlight = stroke.pointsForDrawing[anchor.pointIndex].point
                                val transformedPoint = floatArrayOf(pointToHighlight.x, pointToHighlight.y)
                                globalTransform.mapPoints(transformedPoint)
                                canvas.drawCircle(transformedPoint[0], transformedPoint[1], radius, endpointPaint)
                            }
                        }
                    }
                }

                // Draw the stroke itself if mask permits
                if ((drawMask and MASK_DRAW_STROKE_ITSELF) != 0) {
                    canvas.drawPath(path, finalPaint)
                }
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

        if( advancedGestureInProgress )
            return true

        performClick()
        val screenPoint = PointF(event.x, event.y)
        when (currentState) {
            State.NORMAL_DRAWING,
            State.CHOSEN_STROKE_IN_NORMAL_MODE,
            State.STROKE_EDITING-> {
                if (selectStrokeAt(screenPoint)) {
                    setState(State.CHOSEN_STROKE_IN_NORMAL_MODE)
                } else {
                    deselectAndDeHighlight() // Note: doesn't cause a redraw on its own
                    setState(State.NORMAL_DRAWING)
                }
            }
        }
        return true
    }

    override fun onDoubleTapEnd(event: MotionEvent): Boolean {

        if( advancedGestureInProgress )
            return true

        deselectAndDeHighlight() // Note: doesn't cause a redraw on its own
        setState(State.NORMAL_DRAWING)
        return true
    }

    override fun onFirstFingerDown(event: MotionEvent): Boolean {

        twoFingerGestureOccured   = false // this is the only place it becomes "false"
        threeFingerGestureOccured = false // this is the only place it becomes "false"
        dragGestureHasEnded       = false // this is the only place it becomes "false"

        if( advancedGestureInProgress )
            return true

        val downPoint = PointF(event.x, event.y)
        val worldPoint = toWorldCoordinates(downPoint.x, downPoint.y)

        globalSetStateIsNeeded = true
        when (currentState) {
            State.NORMAL_DRAWING -> {
                touchStart(downPoint.x, downPoint.y)
            }
            State.CHOSEN_STROKE_IN_NORMAL_MODE -> {
                if (selectEndpointOfCurrentStroke(worldPoint)) {
                    setState(State.STROKE_EDITING)
                } else {
                    singleHighlightedStroke?.let {
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
                selectEndpointOfCurrentStroke(worldPoint)
            }
        }
        if( globalSetStateIsNeeded ) setState(currentState) // Refresh UI and Canvas
        return true
    }

    override fun onSecondFingerDown(event: MotionEvent): Boolean {
        if (strokeInProgress != null && strokeInProgress!!.pointsForDrawing.isNotEmpty()) {
            if (strokeInProgress!!.pointsForDrawing.size > 5) {
                commitStrokeInProgress()
            } else {
                strokeInProgress = null
            }
        }
        redrawHistory()
        twoFingerGestureOccured = true
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
        selectionCircle = null
        backedUpGroupStroke = null
        globalSetStateIsNeeded = true // Make sure setState gets called in the end...

        run {
            // Check if we're adding an anchor point
            if( addAnchorPointGestureInProgress && addingAnchorPointIndex != -1 ) {
                addAnchorPointAtIndex(addingAnchorPointIndex)
                addingAnchorPointIndex = -1
                return@run
            }

            // Check if finger was released over the remove anchor point button while editing
            if (currentState == State.STROKE_EDITING && isFingerOverRemoveButton) {
                removeAnchorPointAtEditingIndex()
                setState(State.CHOSEN_STROKE_IN_NORMAL_MODE)
                isFingerOverRemoveButton = false
                return@run
            }

            if( threeFingerGestureOccured )
                return@run

            if( selectionGestureInProgress ) {
                val highlightedStrokes = getHighlightedStrokes
                if (highlightedStrokes.size == 1) {
                    val singleHighlightedStroke = highlightedStrokes.first()
                    currentPaint = Paint(singleHighlightedStroke.paint)
                    setState(State.CHOSEN_STROKE_IN_NORMAL_MODE)
                } else
                    if (highlightedStrokes.isNotEmpty())
                        setState(State.CHOSEN_STROKE_IN_NORMAL_MODE)
                    else
                        setState(State.NORMAL_DRAWING)
                return@run
            }

            if (twoFingerGestureOccured)
                return@run

            when (currentState) {
                State.NORMAL_DRAWING -> {
                    strokeInProgress?.let {
                        touchUp()
                    }
                }
                State.CHOSEN_STROKE_IN_NORMAL_MODE-> {
                    val screenPoint = PointF(event.x, event.y)
                    if (selectStrokeAt(screenPoint))
                        setState(State.CHOSEN_STROKE_IN_NORMAL_MODE)
                    else
                        setState(State.NORMAL_DRAWING)
                }
                State.STROKE_EDITING -> {
                    anchorPointsToEdit.clear()
                    editingPointIndex = -1
                    editingPointInitialWeights = null
                    setState(State.CHOSEN_STROKE_IN_NORMAL_MODE)
                }
                else -> {}
            }
        }

        // De-initialization code that ALWAYS runs
        editingPointIndex = -1
        strokesToTransform.clear()
        dragGestureHasEnded = true // this is the ONLY place it becomes "true"
        if( globalSetStateIsNeeded ) setState(currentState) // Update drawing and UI
        return true
    }

    override fun onSingleFingerDrag(event: MotionEvent, dx: Float, dy: Float): Boolean {

        if( dragGestureHasEnded )
            return true

        if( selectionGestureInProgress )
            return true

        val inverseGlobalTransform = Matrix()
        globalTransform.invert(inverseGlobalTransform)

        // Handle add anchor point gesture
        if( addAnchorPointGestureInProgress ) {
            val worldPoint = toWorldCoordinates(event.x, event.y)
            addingAnchorPointIndex = findClosestPointOnCurve(worldPoint)
            invalidate() // Redraw to show the red circle
            return true
        }

        // Continue transforming strokes (translation only) after transitioning from 2 to 1 finger
        if ( threeFingerGestureOccured && strokesToTransform.isNotEmpty()) {
            val worldDelta = floatArrayOf(dx, dy)
            inverseGlobalTransform.mapVectors(worldDelta)

            // Create transformation matrix with translation only (no scale or rotation)
            val deltaMatrix = Matrix()
            deltaMatrix.postTranslate(worldDelta[0], worldDelta[1])

            // Apply transformation to all strokes
            strokesToTransform.forEach { stroke ->
                transformStroke(stroke, deltaMatrix)
            }
            redrawHistory()
            return true
        }

        if( twoFingerGestureOccured ) {
            val worldDelta = floatArrayOf(dx, dy)
            inverseGlobalTransform.mapVectors(worldDelta)

            // Create transformation matrix with translation only (no scale or rotation)
            val deltaMatrix = Matrix()
            deltaMatrix.postTranslate(worldDelta[0], worldDelta[1])

            val screenMidPoint = midpoint(event)
            val worldMidPoint = toWorldCoordinates(screenMidPoint.x, screenMidPoint.y)

            globalTransform.preTranslate(worldDelta[0], worldDelta[1])

            setState(currentState)
            return true
        }

        when (currentState) {
            State.NORMAL_DRAWING -> {
                touchMove(event.x, event.y)
            }
            State.STROKE_EDITING -> {
                val delta = floatArrayOf(dx, dy)
                inverseGlobalTransform.mapVectors(delta) // Transform delta into world-space
                moveEditingPoint(delta[0], delta[1])
            }
            State.CHOSEN_STROKE_IN_NORMAL_MODE -> {
                singleHighlightedStroke?.let { current ->
                    if (current.isGroup) {
                        backedUpGroupStroke?.let { backup ->
                            current.copyFrom(backup, false)

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
            else -> {}
        }
        redrawHistory()
        return true
    }

    private fun midpoint(event: MotionEvent): PointF {
        if (event.pointerCount < 2) return PointF(event.x, event.y)
        val x = (event.getX(0) + event.getX(1)) / 2f
        val y = (event.getY(0) + event.getY(1)) / 2f
        return PointF(x, y)
    }

    override fun onTwoFingerDrag(event: MotionEvent, dx: Float, dy: Float, scale: Float, rotate: Float): Boolean {

        if( dragGestureHasEnded )
            return true

        val invertedGlobal = Matrix()
        globalTransform.invert(invertedGlobal)
        val worldDelta = floatArrayOf(dx, dy)
        invertedGlobal.mapVectors(worldDelta)

        if( selectionGestureInProgress )
        {
            if (event.pointerCount >= 2) {
                val p1 = toWorldCoordinates(event.getX(0), event.getY(0))
                val p2 = toWorldCoordinates(event.getX(1), event.getY(1))

                selectionCircle = calculateCircleFrom2Points(p1, p2)
                selectionCircle?.let { (center, radius, _) ->
                    strokes.forEach { stroke ->
                        var strokeInCircle = false
                        stroke.forEachStroke { s ->
                            if (s.pointsForDrawing.any { isPointInCircle(it.point, center, radius) }) {
                                strokeInCircle = true
                            }
                        }

                        stroke.setHighlightedRecursively(strokeInCircle || (stroke == singleHighlightedStroke))
                    }
                }
            }
        }
        else if (threeFingerGestureOccured) {
            // Continue transforming strokes even after transitioning from 3 to 2 fingers
            if (strokesToTransform.isNotEmpty()) {
                // Calculate common bounding box for all strokes to transform
                val commonBounds = RectF()
                strokesToTransform.forEach { stroke ->
                    commonBounds.union(stroke.getBounds())
                }

                // Get center of common bounding box
                val centerX = commonBounds.centerX()
                val centerY = commonBounds.centerY()

                // Create transformation matrix in world-space
                val deltaMatrix = Matrix()
                deltaMatrix.postScale(scale, scale, centerX, centerY)
                deltaMatrix.postRotate(rotate, centerX, centerY)
                deltaMatrix.postTranslate(worldDelta[0], worldDelta[1])

                // Apply transformation to all strokes
                strokesToTransform.forEach { stroke -> transformStroke(stroke, deltaMatrix) }
            }
        }
        else
        {
            val screenMidPoint = midpoint(event)
            val worldMidPoint = toWorldCoordinates(screenMidPoint.x, screenMidPoint.y)
            globalTransform.preTranslate(worldDelta[0], worldDelta[1])
            globalTransform.preScale(scale, scale, worldMidPoint.x, worldMidPoint.y)
            globalTransform.preRotate(rotate, worldMidPoint.x, worldMidPoint.y)
        }

        redrawHistory()
        return true
    }

    private fun calculateCircleFrom2Points(p1: PointF, p2: PointF): Triple<PointF, Float, Path> {
        val center = PointF((p1.x + p2.x) / 2, (p1.y + p2.y) / 2)
        val radius = distance(p1, p2) / 2f
        val path = Path().apply { addCircle(center.x, center.y, radius, Path.Direction.CW) }
        return Triple(center, radius, path)
    }

    private fun isPointInCircle(point: PointF, circleCenter: PointF, circleRadius: Float): Boolean {
        return distance(point, circleCenter) < circleRadius
    }

    override fun onThreeFingerDrag(event: MotionEvent, dx: Float, dy: Float, scale: Float, rotate: Float): Boolean {

        if( dragGestureHasEnded )
            return true

        val invertedGlobal = Matrix()
        globalTransform.invert(invertedGlobal)
        val worldDelta = floatArrayOf(dx, dy)
        invertedGlobal.mapVectors(worldDelta)

        // Get all highlighted strokes and include currentStroke
        if( strokesToTransform.isEmpty()) {
            strokesToTransform = getHighlightedStrokes.toMutableSet()
            singleHighlightedStroke?.let { strokesToTransform.add(it) }
        }

        if (strokesToTransform.isNotEmpty()) {
            // Calculate common bounding box for all strokes to transform
            val commonBounds = RectF()
            strokesToTransform.forEach { stroke ->
                commonBounds.union(stroke.getBounds())
            }

            // Get center of common bounding box
            val centerX = commonBounds.centerX()
            val centerY = commonBounds.centerY()

            // Create transformation matrix in world-space
            val deltaMatrix = Matrix()
            deltaMatrix.postScale(scale, scale, centerX, centerY)
            deltaMatrix.postRotate(rotate, centerX, centerY)
            deltaMatrix.postTranslate(worldDelta[0], worldDelta[1])

            // Apply transformation to all strokes
            strokesToTransform.forEach { stroke ->
                transformStroke(stroke, deltaMatrix)
            }

            redrawHistory()
        }

        return true
    }

    private fun findClosestAnchorPointAcrossAllStrokes(tapPoint: PointF): Pair<Stroke, Int>? {
        // Returns (stroke, pointIndex) of the closest ANCHOR point across all highlighted strokes
        // Only searches through polylineIndices, not all unsmoothed points
        var closestStroke: Stroke? = null
        var closestPointIndex = -1
        var closestDist = Float.MAX_VALUE

        // Get all highlighted strokes
        val highlightedStrokes = getHighlightedStrokes
        if (highlightedStrokes.isEmpty()) return null

        highlightedStrokes.forEach { stroke ->
            stroke.forEachStroke { s ->
                if (!s.isGroup && s.polylineIndices.isNotEmpty()) {
                    // Only search through anchor points (polylineIndices)
                    s.polylineIndices.forEach { anchorIndex ->
                        if (anchorIndex >= 0 && anchorIndex < s.unsmoothedPoints.size) {
                            val anchorPoint = s.unsmoothedPoints[anchorIndex].point
                            val d = distance(anchorPoint, tapPoint)
                            if (d < closestDist) {
                                closestDist = d
                                closestPointIndex = anchorIndex
                                closestStroke = s
                            }
                        }
                    }
                }
            }
        }

        return if (closestStroke != null && closestPointIndex != -1) {
            Pair(closestStroke!!, closestPointIndex)
        } else null
    }

    private fun calculateWeightsForAnchorPoint(stroke: Stroke, pointIndex: Int): List<Float> {
        // Calculate weight function for this specific anchor point
        if (stroke.polylineIndices.isNotEmpty() && stroke.polylineIndices.size >= 2 &&
            stroke.distancesForWeights.isNotEmpty()) {

            // Find which polyline anchor this corresponds to
            val closestDrawingDistance = if (pointIndex < stroke.distancesForWeights.size) {
                stroke.distancesForWeights[pointIndex]
            } else {
                return List(stroke.unsmoothedPoints.size) { 0f }
            }

            var closestPolylineIdxInArray = 0
            var minDistToAnchor = Float.MAX_VALUE

            for (i in stroke.polylineIndices.indices) {
                val anchorIndexInOriginal = stroke.polylineIndices[i]
                if (anchorIndexInOriginal >= 0 && anchorIndexInOriginal < stroke.distancesForWeights.size) {
                    val anchorDistance = stroke.distancesForWeights[anchorIndexInOriginal]
                    val distDiff = abs(anchorDistance - closestDrawingDistance)
                    if (distDiff < minDistToAnchor) {
                        minDistToAnchor = distDiff
                        closestPolylineIdxInArray = i
                    }
                }
            }

            if (closestPolylineIdxInArray >= 0 && closestPolylineIdxInArray < stroke.polylineIndices.size) {
                val leftPolylineArrayIdx = if (closestPolylineIdxInArray > 0) closestPolylineIdxInArray - 1 else 0
                val rightPolylineArrayIdx = if (closestPolylineIdxInArray < stroke.polylineIndices.size - 1) {
                    closestPolylineIdxInArray + 1
                } else {
                    stroke.polylineIndices.size - 1
                }

                val leftOriginalIdx = stroke.polylineIndices[leftPolylineArrayIdx].coerceIn(0, stroke.distancesForWeights.size - 1)
                val middleOriginalIdx = stroke.polylineIndices[closestPolylineIdxInArray].coerceIn(0, stroke.distancesForWeights.size - 1)
                val rightOriginalIdx = stroke.polylineIndices[rightPolylineArrayIdx].coerceIn(0, stroke.distancesForWeights.size - 1)

                val leftDist = stroke.distancesForWeights[leftOriginalIdx]
                val middleDist = stroke.distancesForWeights[middleOriginalIdx]
                val rightDist = stroke.distancesForWeights[rightOriginalIdx]

                return stroke.distancesForWeights.mapIndexed { index, dist ->
                    when {
                        dist < leftDist || dist > rightDist -> 0f
                        dist <= middleDist -> {
                            val segmentLength = middleDist - leftDist
                            if (segmentLength == 0f) 1f
                            else {
                                val t = (dist - leftDist) / segmentLength
                                val angle = t * PI.toFloat() / 2f
                                sin(angle) * sin(angle)
                            }
                        }
                        else -> {
                            val segmentLength = rightDist - middleDist
                            if (segmentLength == 0f) 1f
                            else {
                                val t = (dist - middleDist) / segmentLength
                                val angle = (1f - t) * PI.toFloat() / 2f
                                sin(angle) * sin(angle)
                            }
                        }
                    }
                }
            }
        }

        // Fallback to original behavior
        val totalDistanceOfUnsmoothed = stroke.unsmoothedPoints.lastOrNull()?.distance ?: return List(stroke.unsmoothedPoints.size) { 0f }
        val middlePointRelativeDistance = if (pointIndex < stroke.unsmoothedPoints.size) {
            stroke.unsmoothedPoints[pointIndex].distance / totalDistanceOfUnsmoothed
        } else {
            0.5f
        }

        return stroke.unsmoothedPoints.map {
            val relativeDistance = it.distance / totalDistanceOfUnsmoothed
            val mappedDistance = if (relativeDistance <= middlePointRelativeDistance) {
                relativeDistance / middlePointRelativeDistance
            } else {
                1 - ((relativeDistance - middlePointRelativeDistance) / (1 - middlePointRelativeDistance))
            }
            sin(mappedDistance * PI / 2).toFloat()
        }
    }
}
