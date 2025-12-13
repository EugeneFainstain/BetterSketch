package com.example.bettersketch

/*
   STYLE NOTES FOR LLMs - PAY ATTENTION!
    - If there are function calls with multiple parameters - prefer to put these parameters in the same line of code.
    - On function declarations - prefer to use less lines for declaring parameters
    - When specifying function parameters - specify just the parameter, don't do things like myFunction(s = s, b = b)
    - When moving a function to a specialized class - try not to create passthrough wrappers for it here - call it directly, like GeometryUtils.evaluateCubicBezier() etc.
 */

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.blue
import androidx.core.graphics.createBitmap
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.example.bettersketch.GeometryUtils.distance
import kotlin.math.*

// Voronoi diagram colors for editing mode background (10% opacity each)
private val VORONOI_COLORS = arrayOf(
    Color.argb(255, 5, 0, 0),     // Red
    Color.argb(255, 5, 2, 1),     // Orange
    Color.argb(255, 5, 5, 0),     // Yellow
    Color.argb(255, 0, 5, 0),     // Green
    Color.argb(255, 0, 5, 5),     // Cyan
    Color.argb(255, 0, 0, 5),     // Blue
    Color.argb(255, 5, 0, 5),     // Magenta
)

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
    private var anchorPointsToEdit = mutableListOf<AnchorPointToEdit>()
    private var addAnchorPointHere: AnchorPointLocation? = null  // Combined stroke + index

    // Track second finger for bezier control point editing
    private var secondFingerControlEdit: ControlPointToEdit? = null
    private var isSecondFingerEditing = false
    private var firstFingerDownTime: Long = 0  // Track when first finger landed
    private var firstFingerDownPosition: PointF? = null  // Track where first finger landed

    public data class AnchorPointToEdit(
        val stroke: Stroke,
        val pointIndex: Int,
        val snapshotUnsmoothedPoints: MutableList<PathPoint>,
        val weightsForPolylineEditing: List<Float>,
        val isBezierAnchor: Boolean
    )

    data class ControlPointToEdit(
        val stroke: Stroke,
        val controlIndex: Int,      // Index into bezierAnchorPoints (and for both control arrays)
        val arrayIdx: Int           // 1 for controlPoints1, 2 for controlPoints2
    )

    private data class AnchorPointLocation(
        val stroke: Stroke,
        val pointIndex: Int
    )

    // Transformation state
    private val globalTransform =
        Matrix() // Matrix for transforming from WORLD-SPACE to SCREEN-SPACE (a.k.a the VIEW MATRIX)
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
        haloOffset = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            16f,
            context.resources.displayMetrics
        )
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
    public val singleHighlightedStroke: Stroke?
        get() {
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
        return anchorPointsToEdit.isNotEmpty()
    }

    fun areAnyHighlightedStrokesModified(): Boolean {
        val highlightedAndModifiedStrokes = strokes.filter { it.isHighlighted && it.isModified }
        return highlightedAndModifiedStrokes.isNotEmpty()
    }

    fun currentStrokeHasBezierData(): Boolean {
        return singleHighlightedStroke?.hasBezierData() ?: false
    }

    fun currentStrokeHasPolylineData(): Boolean {
        return singleHighlightedStroke?.polylineIndices?.isNotEmpty() ?: false
    }

    // Data class to hold the result of finding closest point across multiple strokes
    private data class ClosestPointResult(
        val stroke: Stroke,
        val pointIndex: Int,
        val distance: Float
    )

    fun addAnchorPointAtIndex(index: Int) {
        // Use the stored location from the preview
        val location = addAnchorPointHere ?: return
        val stroke = location.stroke
        if (stroke.isGroup) return
        if (index == -1) return

        // Check if we're in bezier mode
        if (stroke.renderAsBezier && stroke.bezierAnchorPoints.isNotEmpty()) {
            // Bezier mode: add a new bezier anchor
            BezierUtils.addBezierAnchorPoint(stroke, index)
        } else {
            // Polyline mode: add a polyline anchor
            PolylineUtils.addPolylineAnchorPoint(stroke, index)
        }
    }

    fun undoModificationsForHighlightedStrokes() {
        StrokeUtils.undoModificationsForHighlightedStrokes(getHighlightedStrokes)
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
            //canvas.drawColor(Color.argb(25, 255, 165, 0)) // 10% opacity orange
            // Draw Voronoi diagram as background for highlighted strokes
            drawVoronoiBackground(canvas)
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

        selectionGestureInProgress = (gestureTag == MainActivity.tagSelectionGesture)
        addAnchorPointGestureInProgress = (gestureTag == MainActivity.tagAddAnchorPointGesture)

        advancedGestureInProgress = selectionGestureInProgress || addAnchorPointGestureInProgress

        // Check if finger is over the remove anchor point button (when in editing mode and dragging an anchor)
        if (currentState == State.STROKE_EDITING && anchorPointsToEdit.isNotEmpty()) {
            isFingerOverRemoveButton =
                isEventOverRemoveAnchorButton(event) && !addAnchorPointGestureInProgress
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
            if (it.totalDistance > 20f)
                commitStrokeInProgress()
            else {
                strokeInProgress = null
                setState(currentState) // causes redraw
            }
        }
    }

    private fun commitStrokeInProgress() {
        strokeInProgress?.let { currentStrokeInProgress ->
            val preprocessedUnsmoothedPoints =
                Stroke.preprocessStroke(currentStrokeInProgress.unsmoothedPoints)
            val (finalUnsmoothedPoints, totalDistanceForNewStroke) = Stroke.calculatePathPointsWithDistances(
                preprocessedUnsmoothedPoints.map { it.point })
            val newStroke = Stroke(finalUnsmoothedPoints, Paint(currentStrokeInProgress.paint), totalDistanceForNewStroke, currentStrokeInProgress.smoothness)

            // Postprocess the stroke immediately after creation
            val postprocessSucceeded = newStroke.postProcessAfterDrawing()

            // Only add the stroke if postprocessing succeeded
            // If it failed, we gracefully abandon this stroke
            if (postprocessSucceeded) {
                // Since renderAsBezier is true by default, regenerate the curve using bezier data
                if (newStroke.renderAsBezier && newStroke.hasBezierData()) {
                    BezierUtils.regenerateBezierCurve(newStroke)
                    newStroke.applySmoothing()
                }

                strokes.add(newStroke)
                detectShape(newStroke)
            }

            strokeInProgress = null
            setState(State.NORMAL_DRAWING)
        }
    }

    private fun detectShape(stroke: Stroke) {
        if (stroke.isGroup) {
            shapeDetectionListener?.onNoShapeDetected()
            return
        }

        val strokeForFitting = stroke.generateUniformSampled(256, stroke.pointsForDrawing)

        // Get the best shape fit - use the uniformly sampled stroke for better fitting
        val shapeFitResult = ShapeFitter.shapeFit(stroke, strokeForFitting)

        if (shapeFitResult != null) {
            // Pass null for polylineFitResult since polyline data already exists in the stroke
            // (it was computed in postProcessAfterDrawing)
            shapeDetectionListener?.onShapeDetected(shapeFitResult, null)
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

    fun toggleCurrentStrokeBezier() {
        singleHighlightedStroke?.let {
            it.toggleBezierRepresentation()
            setState(currentState) // Refresh UI and Canvas
        }
    }

    fun toggleNoBezierHandles() {
        // Recompute bezier curves for all highlighted strokes that render as bezier
        getHighlightedStrokes.forEach { stroke ->
            stroke.forEachStroke { s ->
                if (s.renderAsBezier && s.hasBezierData()) {
                    s.noBezierHandles = !s.noBezierHandles
                    BezierUtils.regenerateBezierCurve(s )
                    s.applySmoothing()
                }
            }
        }
        setState(currentState) // Refresh UI and Canvas
    }

    private fun setStrokeHighlighted(stroke: Stroke?) {
        stroke?.setHighlightedRecursively(true)
    }

    private fun deselectAndDeHighlight() {
        // Note: doesn't cause a redraw on its own
        anchorPointsToEdit.clear()  // Just clear the list
        strokes.forEach { it.setHighlightedRecursively(false) }
    }

    private fun selectStrokeAt(tapPointScreen: PointF): Boolean {
        val tapPointWorld = toWorldCoordinates(tapPointScreen.x, tapPointScreen.y)

        deselectAndDeHighlight() // do this first thing

        val screenLongDimension = max(width, height)
        val selectedIndex = StrokeUtils.selectStrokeAt(tapPointScreen, tapPointWorld, strokes, screenLongDimension, ::toScreenCoordinates)

        if (selectedIndex != -1) {
            val selected = strokes.getOrNull(selectedIndex)
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

        // Find the absolute nearest ANCHOR point across all highlighted strokes
        val nearestResult =
            StrokeUtils.findClosestAnchorPointAcrossAllStrokes(tapPoint, getHighlightedStrokes)
                ?: return false
        // Primary stroke is the stroke who's endpoint has been selected for editing
        val (primaryStroke, primaryIndex) = nearestResult

        // Determine if we're in bezier mode or polyline mode
        val inBezierMode =
            primaryStroke.renderAsBezier && primaryStroke.bezierAnchorPoints.isNotEmpty()

        if (inBezierMode) {
            // Bezier mode: primaryIndex is an index into bezierAnchorPoints
            val primaryAnchor = primaryStroke.bezierAnchorPoints[primaryIndex]

            // Save snapshot for undo
            val snapshot = primaryStroke.unsmoothedPoints.map {
                PathPoint(PointF(it.point.x, it.point.y), it.distance)
            }.toMutableList()

            // For bezier anchors, we don't use weightsForPolylineEditing - we move the anchor directly
            // Create a weight list that's all zeros except at the anchor location
            // (We'll handle bezier anchor movement differently in moveEditingPoint)
            anchorPointsToEdit.add(AnchorPointToEdit(primaryStroke, primaryIndex, snapshot, emptyList(), true))
        } else {
            // Polyline/normal mode: primaryIndex is an index into unsmoothedPoints
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

                            // Calculate weightsForPolylineEditing for this anchor point
                            val weightsForPolylineEditing = PolylineUtils.calculateWeightsForAnchorPoint(s, closestAnchorIdx)

                            anchorPointsToEdit.add(AnchorPointToEdit(s, closestAnchorIdx, snapshot, weightsForPolylineEditing, false))
                        }
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
                    StrokeUtils.revertStrokeToOriginal(stroke)
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
                StrokeUtils.revertStrokeToOriginal(lastStroke)
                setState(currentState)
                return
            }
            strokes.removeAt(strokes.lastIndex)
        }

        if (currentState == State.NORMAL_DRAWING) {
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

    fun duplicateCurrentStroke() {
        singleHighlightedStroke?.let { originalStroke ->
            strokes.forEach { it.setHighlightedRecursively(false) }

            val duplicatedStroke = StrokeUtils.duplicateStroke(originalStroke, globalTransform)

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
        // Move all anchor points (either bezier anchors or polyline anchors)
        anchorPointsToEdit.forEach { anchor ->
            // Check if this is a bezier anchor (explicit flag)
            if (anchor.isBezierAnchor) {
                // Bezier mode: move the bezier anchor AND its associated control points
                BezierUtils.moveBezierAnchor(anchor.stroke, anchor.pointIndex, dx, dy)
                // Regenerate the curve from the modified bezier data
                BezierUtils.regenerateBezierCurve(anchor.stroke)
            } else {
                // Polyline mode: apply weighted transformation to unsmoothedPoints
                PolylineUtils.movePolylineAnchorWithWeights(anchor.stroke, anchor.weightsForPolylineEditing, dx, dy)
                // Regenerate interpolated polyline points and apply smoothing
                PolylineUtils.regenerateInterpolatedPolylinePoints(anchor.stroke)
            }
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
            val newCumulativeOpacityMultiplier =
                cumulativeOpacityMultiplier * (stroke.paint.alpha / 255f)

            // Ensure stroke is regenerated if needed
            if (stroke.needsToRegenerate) {
                stroke.regenerateUnsmoothedPointsFromAnalytical()
                stroke.needsToRegenerate = false
            }

            stroke.childStrokes.forEach { childStroke ->
                drawStroke(
                    canvas,
                    childStroke,
                    newCumulativeOpacityMultiplier,
                    newTotalWidthMultiplier,
                    drawEndpoints,
                    drawMask
                )
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
                val currentScale = GeometryUtils.getScaleFromMatrix(globalTransform)

                finalPaint.strokeWidth *= cumulativeWidthMultiplier * currentScale
                finalPaint.alpha = (finalPaint.alpha * cumulativeOpacityMultiplier).toInt()

                val haloPaintToUse = Paint(haloPaint)
                haloPaintToUse.strokeWidth = finalPaint.strokeWidth * 3f

                // Draw halos and markers if mask permits
                if ((drawMask and MASK_DRAW_HALOS_AND_MARKERS) != 0) {
                    if (stroke.isHighlighted) {
                        canvas.drawPath(path, haloPaintToUse)

                        // Draw circles for associated polyline points
                        if (!stroke.renderAsBezier)
                            if (stroke.polylineIndices.isNotEmpty()) {
                                val associatedPoints =
                                    stroke.getAssociatedPolylinePointsOnSmoothedCurve()
                                val vertexPaint = Paint().apply {
                                    style = Paint.Style.FILL
                                    color = Color.BLUE
                                }
                                val radius = haloPaintToUse.strokeWidth // / 2f

                                associatedPoints.forEach { point ->
                                    val transformedPoint = floatArrayOf(point.x, point.y)
                                    globalTransform.mapPoints(transformedPoint)
                                    canvas.drawCircle(transformedPoint[0], transformedPoint[1], radius, vertexPaint)
                                }
                            }

                        // Draw red squares for bezier anchor points
                        if (stroke.bezierAnchorIndices.isNotEmpty()) {
                            val bezierAnchorPoints =
                                stroke.getAssociatedBezierAnchorPointsOnSmoothedCurve()
                            val bezierPaint = Paint().apply {
                                style = Paint.Style.FILL
                                color = Color.RED
                            }
                            val size = haloPaintToUse.strokeWidth / 2f

                            bezierAnchorPoints.forEach { point ->
                                val transformedPoint = floatArrayOf(point.x, point.y)
                                globalTransform.mapPoints(transformedPoint)

                                // Draw a square centered at the anchor point
                                val left = transformedPoint[0] - size
                                val top = transformedPoint[1] - size
                                val right = transformedPoint[0] + size
                                val bottom = transformedPoint[1] + size
                                canvas.drawRect(left, top, right, bottom, bezierPaint)
                            }
                        }

                        // Draw bezier handles (control points and connecting lines)
                        if (stroke.bezierAnchorPoints.isNotEmpty() &&
                            stroke.bezierControlPoints1.isNotEmpty() &&
                            stroke.bezierControlPoints2.isNotEmpty()
                        ) {

                            // Paint for handle lines
                            val handleLinePaint = Paint().apply {
                                style = Paint.Style.STROKE
                                color = Color.GRAY
                                strokeWidth = 2f
                                alpha = 128 // 50% opacity
                            }

                            // Paint for control point squares
                            val controlPointPaint = Paint().apply {
                                style = Paint.Style.FILL
                                color = Color.GREEN
                            }

                            val controlSize =
                                haloPaintToUse.strokeWidth / 3f // Smaller than anchors

                            for (anchorIndex in 0 until stroke.bezierAnchorPoints.size) {
                                val anchor = stroke.bezierAnchorPoints[anchorIndex]

                                // Transform anchor to screen space
                                val anchorScreen = floatArrayOf(anchor.x, anchor.y)
                                globalTransform.mapPoints(anchorScreen)

                                // Draw outgoing control point (controlPoints1)
                                if (anchorIndex < stroke.bezierControlPoints1.size) {
                                    val control1 = stroke.bezierControlPoints1[anchorIndex]
                                    val control1Screen = floatArrayOf(control1.x, control1.y)
                                    globalTransform.mapPoints(control1Screen)

                                    // Draw handle line
                                    canvas.drawLine(anchorScreen[0], anchorScreen[1], control1Screen[0], control1Screen[1], handleLinePaint)

                                    // Draw control point square
                                    canvas.drawRect(control1Screen[0] - controlSize, control1Screen[1] - controlSize, control1Screen[0] + controlSize, control1Screen[1] + controlSize, controlPointPaint)
                                }

                                // Draw incoming control point (controlPoints2)
                                if (anchorIndex < stroke.bezierControlPoints2.size) {
                                    val control2 = stroke.bezierControlPoints2[anchorIndex]
                                    val control2Screen = floatArrayOf(control2.x, control2.y)
                                    globalTransform.mapPoints(control2Screen)

                                    // Draw handle line
                                    canvas.drawLine(anchorScreen[0], anchorScreen[1], control2Screen[0], control2Screen[1], handleLinePaint)

                                    // Draw control point square
                                    canvas.drawRect(control2Screen[0] - controlSize, control2Screen[1] - controlSize, control2Screen[0] + controlSize, control2Screen[1] + controlSize, controlPointPaint)
                                }
                            }
                        }

                        // Draw red circle for adding anchor point preview - check if THIS stroke matches
                        addAnchorPointHere?.let { location ->
                            if (addAnchorPointGestureInProgress &&
                                location.stroke == stroke &&
                                location.pointIndex != -1 &&
                                location.pointIndex < stroke.pointsForDrawing.size
                            ) {
                                val previewPoint =
                                    stroke.pointsForDrawing[location.pointIndex].point
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
                    }

                    // Draw green circles for all anchor points being edited
                    // Check independently of drawEndpoints and gesture flags
                    if (!twoFingerGestureOccured && !threeFingerGestureOccured) { // Do not draw highlighted endpoint during a 2- or 3- finger gesture
                        val radius = haloPaintToUse.strokeWidth / 2f
                        val endpointPaint = Paint().apply {
                            style = Paint.Style.FILL
                            color = Color.GREEN
                        }

                        // Check if this stroke has any anchor points being edited
                        anchorPointsToEdit.forEach { anchor ->
                            if (anchor.stroke == stroke && anchor.pointIndex < stroke.pointsForDrawing.size) {
                                // Draw green circle on the SMOOTHED position of this anchor point
                                val pointToHighlight =
                                    stroke.pointsForDrawing[anchor.pointIndex].point
                                val transformedPoint =
                                    floatArrayOf(pointToHighlight.x, pointToHighlight.y)
                                globalTransform.mapPoints(transformedPoint)
                                canvas.drawCircle(
                                    transformedPoint[0],
                                    transformedPoint[1],
                                    radius,
                                    endpointPaint
                                )
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

        if (advancedGestureInProgress)
            return true

        performClick()
        val screenPoint = PointF(event.x, event.y)
        when (currentState) {
            State.NORMAL_DRAWING,
            State.CHOSEN_STROKE_IN_NORMAL_MODE,
            State.STROKE_EDITING -> {
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

        if (advancedGestureInProgress)
            return true

        deselectAndDeHighlight() // Note: doesn't cause a redraw on its own
        setState(State.NORMAL_DRAWING)
        return true
    }

    override fun onFirstFingerDown(event: MotionEvent): Boolean {

        twoFingerGestureOccured = false // this is the only place it becomes "false"
        threeFingerGestureOccured = false // this is the only place it becomes "false"
        dragGestureHasEnded = false // this is the only place it becomes "false"

        firstFingerDownTime = System.currentTimeMillis()  // Record when first finger landed
        firstFingerDownPosition = PointF(event.x, event.y)  // Record where first finger landed

        if (advancedGestureInProgress)
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
        if (globalSetStateIsNeeded) setState(currentState) // Refresh UI and Canvas
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

        // Calculate time difference between first and second finger
        val timeBetweenFingers = System.currentTimeMillis() - firstFingerDownTime
        val firstFingerPosition = PointF(event.getX(0), event.getY(0))
        val firstFingerTravel = distance(firstFingerDownPosition!!, firstFingerPosition)

        val simultaneousThreshold = ViewConfiguration.getTapTimeout() // milliseconds - tune this value as needed
        val travelThreshold = ViewConfiguration.get(context).scaledTouchSlop // tune this value as needed

        // Determine if this should be a control point edit gesture:
        // 1. Must be in stroke editing mode
        // 2. Must have an anchor point being edited
        // 3. Fingers must NOT land simultaneously (sequential touch)
        val shouldEditControlPoint = currentState == State.STROKE_EDITING &&
                anchorPointsToEdit.isNotEmpty() &&
                    ((timeBetweenFingers > simultaneousThreshold) or
                     (firstFingerTravel > travelThreshold)) // if the first finger traveled far

        if (shouldEditControlPoint) {
            val primaryAnchor = anchorPointsToEdit.firstOrNull()
            if (primaryAnchor != null && primaryAnchor.isBezierAnchor) {
                if (BezierUtils.USE_SCALE_ROTATE_CONTROL_EDIT) {
                    // Alternative mode: record initial two-finger state for scale/rotate
                    if (event.pointerCount >= 2) {
                        isSecondFingerEditing = true
                        // Don't need secondFingerControlEdit in this mode
                    }
                } else {
                    // Original mode: find closest control point for second finger
                    if (event.pointerCount >= 2) {
                        val secondFingerWorldPoint = toWorldCoordinates(event.getX(1), event.getY(1))
                        secondFingerControlEdit = BezierUtils.findClosestControlPoint(primaryAnchor.stroke, primaryAnchor.pointIndex, secondFingerWorldPoint)
                        if (secondFingerControlEdit != null) {
                            isSecondFingerEditing = true
                        }
                    }
                }
                // Don't set twoFingerGestureOccured - this prevents canvas transformation
                redrawHistory()
                return true
            }
        }
        
        // Otherwise, this is a normal two-finger gesture (canvas transformation)
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

        // Clear second finger editing state
        secondFingerControlEdit = null
        isSecondFingerEditing = false

        run {
            // Check if we're adding an anchor point
            if (addAnchorPointGestureInProgress && addAnchorPointHere != null) {
                addAnchorPointAtIndex(addAnchorPointHere!!.pointIndex)
                addAnchorPointHere = null
                return@run
            }

            // Check if finger was released over the remove anchor point button while editing
            if (currentState == State.STROKE_EDITING && isFingerOverRemoveButton) {
                // Remove anchor points from all affected strokes
                if (anchorPointsToEdit.isNotEmpty()) {
                    anchorPointsToEdit.forEach { anchor ->
                        StrokeUtils.removeAnchorPointAtIndex(anchor.stroke, anchor.pointIndex, anchor.snapshotUnsmoothedPoints, anchor.isBezierAnchor)
                    }

                    // Clear editing state
                    anchorPointsToEdit.clear()
                }

                setState(State.CHOSEN_STROKE_IN_NORMAL_MODE)
                isFingerOverRemoveButton = false
                return@run
            }

            if (threeFingerGestureOccured)
                return@run

            if (selectionGestureInProgress) {
                val highlightedStrokes = getHighlightedStrokes
                if (highlightedStrokes.size == 1) {
                    val singleHighlightedStroke = highlightedStrokes.first()
                    currentPaint = Paint(singleHighlightedStroke.paint)
                    setState(State.CHOSEN_STROKE_IN_NORMAL_MODE)
                } else if (highlightedStrokes.isNotEmpty()) {
                    setState(State.CHOSEN_STROKE_IN_NORMAL_MODE)
                } else {
                    setState(State.NORMAL_DRAWING)
                }
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

                State.CHOSEN_STROKE_IN_NORMAL_MODE -> {
                    val screenPoint = PointF(event.x, event.y)
                    if (selectStrokeAt(screenPoint))
                        setState(State.CHOSEN_STROKE_IN_NORMAL_MODE)
                    else
                        setState(State.NORMAL_DRAWING)
                }

                State.STROKE_EDITING -> {
                    anchorPointsToEdit.clear()
                    setState(State.CHOSEN_STROKE_IN_NORMAL_MODE)
                }

                else -> {}
            }
        }

        // De-initialization code that ALWAYS runs
        addAnchorPointHere = null  // Clear the location
        strokesToTransform.clear()
        dragGestureHasEnded = true
        if (globalSetStateIsNeeded) setState(currentState) // Update drawing and UI
        return true
    }


    override fun onSingleFingerDrag(event: MotionEvent, dx: Float, dy: Float): Boolean {

        if (dragGestureHasEnded)
            return true

        if (selectionGestureInProgress)
            return true

        val inverseGlobalTransform = Matrix()
        globalTransform.invert(inverseGlobalTransform)

        // Handle add anchor point gesture - find closest point across ALL highlighted strokes
        if (addAnchorPointGestureInProgress) {
            val worldPoint = toWorldCoordinates(event.x, event.y)
            val result = StrokeUtils.findClosestPointAcrossHighlightedStrokes(
                worldPoint,
                getHighlightedStrokes
            )

            addAnchorPointHere = if (result != null) {
                AnchorPointLocation(result.stroke, result.pointIndex)
            } else {
                null
            }

            invalidate() // Redraw to show the red circle
            return true
        }

        // Continue transforming strokes (translation only) after transitioning from 2 to 1 finger
        if (threeFingerGestureOccured && strokesToTransform.isNotEmpty()) {
            val worldDelta = floatArrayOf(dx, dy)
            inverseGlobalTransform.mapVectors(worldDelta)

            // Create transformation matrix with translation only (no scale or rotation)
            val deltaMatrix = Matrix()
            deltaMatrix.postTranslate(worldDelta[0], worldDelta[1])

            // Apply transformation to all strokes
            strokesToTransform.forEach { stroke ->
                StrokeUtils.transformStroke(stroke, deltaMatrix)
            }
            redrawHistory()
            return true
        }

        if (twoFingerGestureOccured) {
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

                // Only move first finger anchor point(s) - not the second finger control point
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
                            val initialHeight =
                                centerScreen.y - initialTouchY  // yes, it can be negative

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
                            postTransform(worldspaceTransform, screenspaceTransform)  // 2. Next, apply our transformation, in screen-space
                            postTransform(worldspaceTransform, inverseGlobalTransform)// 3. Finally, transform back to world-space

                            // Applying the transformations, in world-space
                            StrokeUtils.transformStroke(current, worldspaceTransform)
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

    override fun onTwoFingerDrag(
        event: MotionEvent,
        dx0: Float,
        dy0: Float,
        dx1: Float,
        dy1: Float,
        scale: Float,
        rotate: Float
    ): Boolean {

        if (dragGestureHasEnded)
            return true

        val invertedGlobal = Matrix()
        globalTransform.invert(invertedGlobal)

        // Handle second finger for bezier control point editing
        // If second finger is editing a control point, handle it specially
        if (currentState == State.STROKE_EDITING && isSecondFingerEditing) {
            if (BezierUtils.USE_SCALE_ROTATE_CONTROL_EDIT) {
                // Alternative mode: use scale and rotate to transform control points
                // Anchor moves according to midpoint delta
                val moveDelta = floatArrayOf(dx0, dy0)
                invertedGlobal.mapVectors(moveDelta )

                // Use the scale and rotate values passed from CustomGestureDetector
                BezierUtils.moveBezierAnchorWithScaleRotate(
                    anchorPointsToEdit.firstOrNull(),
                    moveDelta[0], moveDelta[1],
                    scale * 1.0f, rotate * 2f // Amplify the effect by 2x... Can't do this for the scale yet...
                )

                redrawHistory()
                return true
            } else if (secondFingerControlEdit != null) {
                // Original mode: per-finger deltas
                // Transform per-finger deltas to world coordinates
                val finger0Delta = floatArrayOf(dx0, dy0)
                val finger1Delta = floatArrayOf(dx1, dy1)
                invertedGlobal.mapVectors(finger0Delta)
                invertedGlobal.mapVectors(finger1Delta)

                // Move anchor (finger 0) and control point (finger 1) together
                // This also handles the opposite control point automatically
                BezierUtils.moveBezierAnchorAndControlPoint(secondFingerControlEdit, anchorPointsToEdit.firstOrNull(),
                    finger0Delta[0], finger0Delta[1], finger1Delta[0], finger1Delta[1])

                redrawHistory()
                return true  // Early return - don't do canvas transformation
            }
        }

        // Calculate midpoint deltas for normal two-finger gestures
        val midDx = (dx0 + dx1) / 2f
        val midDy = (dy0 + dy1) / 2f

        // Continue with normal two-finger processing (canvas transformation)
        val worldDelta = floatArrayOf(midDx, midDy)
        invertedGlobal.mapVectors(worldDelta)

        if (selectionGestureInProgress) {
            if (event.pointerCount >= 2) {
                val p1 = toWorldCoordinates(event.getX(0), event.getY(0))
                val p2 = toWorldCoordinates(event.getX(1), event.getY(1))

                selectionCircle = calculateCircleFrom2Points(p1, p2)
                selectionCircle?.let { (center, radius, _) ->
                    strokes.forEach { stroke ->
                        var strokeInCircle = false
                        stroke.forEachStroke { s ->
                            if (s.pointsForDrawing.any {
                                    isPointInCircle(
                                        it.point,
                                        center,
                                        radius
                                    )
                                }) {
                                strokeInCircle = true
                            }
                        }

                        stroke.setHighlightedRecursively(strokeInCircle || (stroke == singleHighlightedStroke))
                    }
                }
            }
        } else if (threeFingerGestureOccured) {
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
                strokesToTransform.forEach { stroke ->
                    StrokeUtils.transformStroke(
                        stroke,
                        deltaMatrix
                    )
                }
            }
        } else {
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

    /**
     * Draw a Voronoi diagram background based on bezier anchor points of highlighted strokes.
     * Colors alternate between 10% blue and 10% yellow based on anchor index.
     */
    private fun drawVoronoiBackground(canvas: Canvas) {
        // Collect all bezier anchor points from highlighted strokes
        val anchorPoints = mutableListOf<PointF>()
        val anchorIndices = mutableListOf<Int>() // Track original index for coloring

        var globalIndex = 0
        getHighlightedStrokes.forEach { stroke ->
            stroke.forEachStroke { s ->
                if (s.bezierAnchorPoints.isNotEmpty()) {
                    s.bezierAnchorPoints.forEach { anchor ->
                        anchorPoints.add(PointF(anchor.x, anchor.y))
                        anchorIndices.add(globalIndex)
                        globalIndex++
                    }
                }
            }
        }

        if (anchorPoints.size < 2) {
            // Not enough points for Voronoi, just fill with orange
            canvas.drawColor(Color.argb(25, 255, 165, 0))
            return
        }

        // Check if first and last anchor would have the same color
        val firstColorIdx = anchorIndices.first() % VORONOI_COLORS.size
        val lastColorIdx = anchorIndices.last() % VORONOI_COLORS.size
        val lastAnchorIdx = anchorIndices.size - 1
        val useWhiteForLast = (firstColorIdx == lastColorIdx) && anchorPoints.size > 1

        // Transform anchor points to screen coordinates
        val screenAnchors = anchorPoints.map { pt ->
            val transformed = floatArrayOf(pt.x, pt.y)
            globalTransform.mapPoints(transformed)
            PointF(transformed[0], transformed[1])
        }

        // Draw Voronoi regions by checking each pixel's closest anchor
        // For performance, we sample at a lower resolution and draw rectangles
        val sampleStep = 11 //16 //if (isAnchorPointDragging()) 8 else 4 // Sample every 8 pixels for performance
        val squareSize = 8 //15 //12
        val paint = Paint().apply { style = Paint.Style.FILL }

        for (y in 0 until height step sampleStep) {
            for (x in 0 until width step sampleStep) {
                val testPoint = PointF(x.toFloat() + squareSize/2, y.toFloat() + squareSize/2)

                // Find closest anchor point
                var closestIdx = 0
                var closestDist = Float.MAX_VALUE

                for (i in screenAnchors.indices) {
                    val d = distance(testPoint, screenAnchors[i])
                    if (d < closestDist) {
                        closestDist = d
                        closestIdx = i
                    }
                }

                // Color based on anchor index (alternating), with white for last if collision
                paint.color = if (useWhiteForLast && closestIdx == lastAnchorIdx) {
                    Color.argb(25, 255, 255, 255) // 10% opacity white
                } else {
                    var c = VORONOI_COLORS[(anchorIndices[closestIdx]*3) % VORONOI_COLORS.size] * 50
                    Color.argb(25*3, c.red, c.green, c.blue) // Assign opacity
                }

                canvas.drawRect(
                    x.toFloat(),
                    y.toFloat(),
                    (x + squareSize).toFloat(),
                    (y + squareSize).toFloat(),
                    paint
                )
            }
        }
    }

    override fun onThreeFingerDrag(
        event: MotionEvent,
        dx: Float,
        dy: Float,
        scale: Float,
        rotate: Float
    ): Boolean {

        if (dragGestureHasEnded)
            return true

        val invertedGlobal = Matrix()
        globalTransform.invert(invertedGlobal)
        val worldDelta = floatArrayOf(dx, dy)
        invertedGlobal.mapVectors(worldDelta)

        // Get all highlighted strokes and include currentStroke
        if (strokesToTransform.isEmpty()) {
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
                StrokeUtils.transformStroke(stroke, deltaMatrix)
            }

            redrawHistory()
        }

        return true
    }
}
