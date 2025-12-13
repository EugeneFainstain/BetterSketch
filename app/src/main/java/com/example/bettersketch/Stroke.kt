package com.example.bettersketch

import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.sqrt

data class PathPoint(var point: PointF, var distance: Float)

enum class AnalyticalShapeType {
    NONE,           // No analytical shape
    SQUARE,         // Square shape
    CIRCLE,         // Circle shape
    POLYNOMIAL      // Polynomial curve
}

class Stroke(
    val paint: Paint,
    var smoothness: Int
) {
    val pointsForDrawing: MutableList<PathPoint> = mutableListOf() // Smoothed points for drawing
    val originalPoints: MutableList<PathPoint> = mutableListOf() // Original points for undo/reset
    val unsmoothedPoints: MutableList<PathPoint> = mutableListOf() // Unsmoothed points for editing
    val shapeParameterPoints: MutableList<PathPoint> = mutableListOf() // Shape parameter points (for SQUARE, CIRCLE, POLYNOMIAL)
    val interpolatedPolylinePoints: MutableList<PathPoint> = mutableListOf() // Interpolated polyline points (same count as originalPoints)
    val polylineIndices: MutableList<Int> = mutableListOf() // Indices of the original points that correspond to the polyline vertices
    val distancesForWeights: MutableList<Float> = mutableListOf() // Distances along path at stroke finalization, used for weight calculation during editing

    // Bezier curve data
    val bezierAnchorPoints: MutableList<PointF> = mutableListOf()      // Optimal computed anchors
    val bezierControlPoints1: MutableList<PointF> = mutableListOf()    // "Before" control points (one per anchor, outgoing from anchor)
    val bezierControlPoints2: MutableList<PointF> = mutableListOf()    // "After" control points (one per anchor, incoming to anchor)
    val bezierAnchorIndices: MutableList<Int> = mutableListOf()        // Indices of Bezier anchors
    var renderAsBezier: Boolean = false                                // Toggle for bezier rendering
    var noBezierHandles: Boolean = false                               // Toggle Bezier/Linear interpolation

    var totalDistance: Float = 0f
    var isModified: Boolean = false
    val childStrokes: MutableList<Stroke> = mutableListOf()
    val isGroup: Boolean get() = childStrokes.isNotEmpty()
    var isHighlighted: Boolean = false

    var analyticalShapeType: AnalyticalShapeType = AnalyticalShapeType.NONE // Type of analytical shape
    var renderAsPolyline: Boolean = false // Flag to indicate that the curve has been approximated by a polyline
    var needsToRegenerate: Boolean = false // Flag to regenerate unsmoothedPoints from analytical

    fun hasBezierData(): Boolean {
        return bezierAnchorPoints.isNotEmpty() &&
                bezierControlPoints1.isNotEmpty() &&
                bezierControlPoints2.isNotEmpty()
    }

    fun toggleBezierRepresentation() {
        if (!hasBezierData()) {
            // Cannot toggle if there's no bezier data
            return
        }

        renderAsBezier = !renderAsBezier
        isModified = true

        // If turning OFF bezier mode, restore unsmoothedPoints from originalPoints
        if (!renderAsBezier) {
            unsmoothedPoints.clear()
            unsmoothedPoints.addAll(
                originalPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) }
            )

            // Recalculate distances
            val (recalculatedPoints, newTotalDistance) = calculatePathPointsWithDistances(
                unsmoothedPoints.map { it.point }
            )
            unsmoothedPoints.clear()
            unsmoothedPoints.addAll(recalculatedPoints)
            totalDistance = newTotalDistance
        }

        // Regenerate points for drawing based on current mode
        if (renderAsBezier) {
            BezierUtils.regenerateBezierCurve(this)
        }

        applySmoothing()
    }

    // Secondary constructor for creating a stroke from existing points (like the original constructor)
    constructor(incomingPoints: List<PathPoint>, paint: Paint, totalDistance: Float, smoothness: Int) : this(paint, smoothness) {
        // incomingPoints are considered the initial unsmoothed points
        this.originalPoints.addAll(incomingPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })
        this.unsmoothedPoints.addAll(incomingPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })
        this.totalDistance = totalDistance // This totalDistance is based on incomingPoints

        // Capture distances for weight calculations
        this.distancesForWeights.addAll(incomingPoints.map { it.distance })

        applySmoothing() // Apply smoothing to generate 'points' from 'unsmoothedPoints'
    }

    // Constructor for grouping existing strokes
    constructor(strokesToGroup: MutableList<Stroke>, paint: Paint) : this(Paint(paint), 0) {
        this.childStrokes.addAll(strokesToGroup)
        this.paint.strokeWidth = 10f;
    }

    fun getAssociatedPolylinePointsOnSmoothedCurve(): List<PointF> {
        if (polylineIndices.isEmpty() || pointsForDrawing.isEmpty()) {
            return emptyList()
        }

        return polylineIndices.mapNotNull { index ->
            pointsForDrawing.getOrNull(index)?.point
        }
    }

    fun getAssociatedBezierAnchorPointsOnSmoothedCurve(): List<PointF> {
        // When in bezier mode, return the actual bezier anchor points directly
        // (not indices, as they may not correspond after interpolation)
        if (renderAsBezier && bezierAnchorPoints.isNotEmpty()) {
            return bezierAnchorPoints.map { PointF(it.x, it.y) }
        }

        // When NOT in bezier mode, use indices to show where anchors would be
        if (bezierAnchorIndices.isEmpty() || pointsForDrawing.isEmpty()) {
            return emptyList()
        }

        return bezierAnchorIndices.mapNotNull { index ->
            pointsForDrawing.getOrNull(index)?.point
        }
    }

    fun addPoint(newPoint: PointF) {
        val lastUnsmoothedPoint = unsmoothedPoints.lastOrNull()?.point
        val currentSegmentDistance = if (lastUnsmoothedPoint != null) {
            val dx = newPoint.x - lastUnsmoothedPoint.x
            val dy = newPoint.y - lastUnsmoothedPoint.y
            sqrt(dx * dx + dy * dy)
        } else 0f

        totalDistance += currentSegmentDistance
        val pathPoint = PathPoint(newPoint, totalDistance)
        unsmoothedPoints.add(pathPoint)
        originalPoints.add(PathPoint(PointF(newPoint.x, newPoint.y), totalDistance)) // Deep copy for originalPoints

        applySmoothing() // Re-smooth every time a point is added
    }

    fun applySmoothing() {
        // Check if we need to regenerate from analytical points first
        if (needsToRegenerate)
            regenerateUnsmoothedPointsFromAnalytical()

        // Check if we need to regenerate from bezier curve
        if (renderAsBezier && hasBezierData()) {
            BezierUtils.regenerateBezierCurve(this)
        }

        // Choose the source points based on mode
        val sourcePoints = when {
            renderAsBezier && hasBezierData() -> unsmoothedPoints
            renderAsPolyline && interpolatedPolylinePoints.isNotEmpty() -> interpolatedPolylinePoints
            else -> unsmoothedPoints
        }

        if (this.smoothness == 0) {
            this.pointsForDrawing.clear()
            this.pointsForDrawing.addAll(sourcePoints.map { p -> PathPoint(PointF(p.point.x, p.point.y), p.distance) })
            return
        }

        var smoothedPoints = sourcePoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) }.toMutableList()

        repeat(this.smoothness) {
            if (smoothedPoints.size < 3) return@repeat

            val iterationResult = mutableListOf<PathPoint>()
            iterationResult.add(smoothedPoints.first()) // Keep first point

            for (i in 1 until smoothedPoints.size - 1) {
                val prev = smoothedPoints[i - 1].point
                val next = smoothedPoints[i + 1].point
                val current = smoothedPoints[i]

                val avgX = (prev.x + 2*current.point.x + next.x) / 4f
                val avgY = (prev.y + 2*current.point.y + next.y) / 4f

                iterationResult.add(PathPoint(PointF(avgX, avgY), current.distance))
            }

            iterationResult.add(smoothedPoints.last()) // Keep last point
            smoothedPoints = iterationResult
        }

        // Recalculate distances for the final smoothed points
        val pointFs = smoothedPoints.map { it.point }
        val (finalPoints, newTotalDistance) = calculatePathPointsWithDistances(pointFs)
        this.pointsForDrawing.clear()
        this.pointsForDrawing.addAll(finalPoints)
        this.totalDistance = newTotalDistance // Update totalDistance based on smoothed points
    }


    fun togglePolylineRepresentation() {
        if (polylineIndices.isEmpty()) {
            // Cannot toggle if there's no polyline data
            return
        }

        renderAsPolyline = !renderAsPolyline
        isModified = true

        PolylineUtils.regenerateInterpolatedPolylinePoints(this)
        applySmoothing()
    }
    fun forEachStroke(action: (Stroke) -> Unit) {
        if (isGroup) {
            childStrokes.forEach { it.forEachStroke(action) }
        } else {
            action(this)
        }
    }

    fun setHighlightedRecursively(highlight: Boolean) {
        this.isHighlighted = highlight
        if (isGroup) {
            childStrokes.forEach { it.setHighlightedRecursively(highlight) }
        }
    }

    fun getBounds(): RectF {
        val bounds = RectF()
        forEachStroke { stroke ->
            if (stroke.pointsForDrawing.isNotEmpty()) {
                val strokeBounds = RectF(stroke.pointsForDrawing.first().point.x, stroke.pointsForDrawing.first().point.y, stroke.pointsForDrawing.first().point.x, stroke.pointsForDrawing.first().point.y)
                for (i in 1 until stroke.pointsForDrawing.size) {
                    strokeBounds.union(stroke.pointsForDrawing[i].point.x, stroke.pointsForDrawing[i].point.y)
                }
                bounds.union(strokeBounds)
            }
        }
        return bounds
    }

    fun getSingleColor(): Int? {
        val colors = mutableSetOf<Int>()
        forEachStroke { stroke ->
            colors.add(stroke.paint.color)
        }
        return if (colors.size == 1) colors.first() else null
    }

    fun setColor(color: Int) {
        forEachStroke { stroke ->
            stroke.isModified = true
            stroke.paint.color = color
        }
    }

    fun newFrom(): Stroke {
        val newStroke = Stroke(Paint(this.paint), this.smoothness)
        newStroke.copyFrom(this, forDuplication = true) // Use copyFrom with forDuplication flag
        return newStroke
    }

    fun copyFrom(other: Stroke, forDuplication: Boolean) {
        this.paint.set(other.paint)
        this.smoothness = other.smoothness
        this.analyticalShapeType = other.analyticalShapeType
        this.renderAsPolyline = other.renderAsPolyline
        this.renderAsBezier = other.renderAsBezier
        this.pointsForDrawing.clear()
        this.pointsForDrawing.addAll(other.pointsForDrawing.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })

        this.unsmoothedPoints.clear()
        this.unsmoothedPoints.addAll(other.unsmoothedPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })

        this.shapeParameterPoints.clear()
        this.shapeParameterPoints.addAll(other.shapeParameterPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })

        this.interpolatedPolylinePoints.clear()
        this.interpolatedPolylinePoints.addAll(other.interpolatedPolylinePoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })

        this.polylineIndices.clear()
        this.polylineIndices.addAll(other.polylineIndices)

        this.distancesForWeights.clear()
        this.distancesForWeights.addAll(other.distancesForWeights)

// Copy bezier data
        this.bezierAnchorPoints.clear()
        this.bezierAnchorPoints.addAll(other.bezierAnchorPoints.map { PointF(it.x, it.y) })

        this.bezierControlPoints1.clear()
        this.bezierControlPoints1.addAll(other.bezierControlPoints1.map { PointF(it.x, it.y) })

        this.bezierControlPoints2.clear()
        this.bezierControlPoints2.addAll(other.bezierControlPoints2.map { PointF(it.x, it.y) })

        this.bezierAnchorIndices.clear()
        this.bezierAnchorIndices.addAll(other.bezierAnchorIndices)

        this.totalDistance = other.totalDistance

        if (forDuplication) {
            // For duplication, the new stroke's originalPoints should be its current unsmoothedPoints
            this.originalPoints.clear()
            this.originalPoints.addAll(this.unsmoothedPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })
            this.isModified = false
            this.isHighlighted = false // New copy is not highlighted by default
        } else {
            // For regular copy (e.g., restoring from backup), copy originalPoints and other flags as is
            this.originalPoints.clear()
            this.originalPoints.addAll(other.originalPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })
            this.isModified = other.isModified
            this.isHighlighted = other.isHighlighted
        }

        this.childStrokes.clear()
        other.childStrokes.forEach { child ->
            this.childStrokes.add(child.newFrom()) // Child strokes still need deep copies
        }
    }

    /**
     * Generates a new stroke with N uniformly spaced points along the curve.
     * The resulting stroke will look identical visually but with evenly distributed sample points.
     *
     * @param N The desired number of uniformly spaced points
     * @param sourcePoints The source points to sample from (defaults to pointsForDrawing)
     * @return A new Stroke with uniformly sampled points, or null if N < 2 or stroke has no points
     */
    fun generateUniformSampled(N: Int, sourcePoints: List<PathPoint>): Stroke {
        if (N < 2 || sourcePoints.isEmpty()) return Stroke(mutableListOf(), Paint(), 0f, 0)

        // Calculate total distance of source points
        val sourceTotalDistance = if (sourcePoints.isNotEmpty()) sourcePoints.last().distance else 0f
        if (sourceTotalDistance <= 0f) return Stroke(mutableListOf(), Paint(), 0f, 0)

        val uniformPoints = mutableListOf<PointF>()
        val spacing = sourceTotalDistance / (N - 1)

        // Always add the first point
        uniformPoints.add(PointF(sourcePoints.first().point.x, sourcePoints.first().point.y))

        // Generate N-2 intermediate points at uniform distances
        for (i in 1 until N - 1) {
            val targetDistance = i * spacing
            val interpolatedPoint = interpolatePointAtDistanceFrom(targetDistance, sourcePoints)
            if (interpolatedPoint != null) {
                uniformPoints.add(interpolatedPoint)
            }
        }

        // Always add the last point
        uniformPoints.add(PointF(sourcePoints.last().point.x, sourcePoints.last().point.y))

        // Create new stroke with uniformly sampled points
        val (pathPoints, newTotalDistance) = calculatePathPointsWithDistances(uniformPoints)
        return Stroke(pathPoints, Paint(this.paint), newTotalDistance, 0) // smoothness = 0 to preserve exact points
    }

    /**
     * Interpolates a point at a specific distance along a given path.
     *
     * @param targetDistance The distance along the path where the point should be interpolated
     * @param sourcePoints The source points to interpolate from
     * @return The interpolated PointF, or null if targetDistance is out of bounds
     */
    private fun interpolatePointAtDistanceFrom(targetDistance: Float, sourcePoints: List<PathPoint>): PointF? {
        if (sourcePoints.size < 2) return null
        val sourceTotalDistance = sourcePoints.last().distance
        if (targetDistance < 0 || targetDistance > sourceTotalDistance) {
            return null
        }

        // Find the two points that bracket the target distance
        for (i in 1 until sourcePoints.size) {
            val prevPoint = sourcePoints[i - 1]
            val currPoint = sourcePoints[i]

            if (targetDistance <= currPoint.distance) {
                // Interpolate between prevPoint and currPoint
                val segmentLength = currPoint.distance - prevPoint.distance
                if (segmentLength == 0f) {
                    return PointF(prevPoint.point.x, prevPoint.point.y)
                }

                val t = (targetDistance - prevPoint.distance) / segmentLength
                val x = prevPoint.point.x + t * (currPoint.point.x - prevPoint.point.x)
                val y = prevPoint.point.y + t * (currPoint.point.y - prevPoint.point.y)
                return PointF(x, y)
            }
        }

        // If we reach here, return the last point
        return PointF(sourcePoints.last().point.x, sourcePoints.last().point.y)
    }

    /**
     * Regenerates unsmoothedPoints from analytical shape points (for SQUARE, CIRCLE, POLYNOMIAL).
     * This interpolates the analytical shape to match the original point count.
     */
    fun regenerateUnsmoothedPointsFromAnalyticalShape() {
        needsToRegenerate = false

        val pointCount = originalPoints.size
        if (pointCount < 2) return

        val interpolatedPoints = when (analyticalShapeType) {
            AnalyticalShapeType.SQUARE -> {
                // For squares: shapeParameterPoints contains the 4 corners (+ closed point)
                PolylineUtils.interpolateAlongPolyLine(shapeParameterPoints.map { it.point }, pointCount)
            }
            AnalyticalShapeType.CIRCLE -> {
                // For circles: shapeParameterPoints contains points around the circle perimeter
                PolylineUtils.interpolateAlongPolyLine(shapeParameterPoints.map { it.point }, pointCount)
            }
            AnalyticalShapeType.POLYNOMIAL -> {
                // For polynomials: shapeParameterPoints are the curve points
                PolylineUtils.interpolateAlongPolyLine(shapeParameterPoints.map { it.point }, pointCount)
            }
            else -> {
                // Not an analytical shape (square/circle/polynomial)
                return
            }
        }

        // Update unsmoothed points with regenerated points
        val (pathPoints, newTotalDistance) = calculatePathPointsWithDistances(interpolatedPoints)
        unsmoothedPoints.clear()
        unsmoothedPoints.addAll(pathPoints)
        totalDistance = newTotalDistance

        // Reapply smoothing to update pointsForDrawing
        applySmoothing()
    }

    /**
     * Legacy function for backwards compatibility. Routes to appropriate regeneration function.
     */
    fun regenerateUnsmoothedPointsFromAnalytical() {
        needsToRegenerate = false

        when (analyticalShapeType) {
            AnalyticalShapeType.SQUARE,
            AnalyticalShapeType.CIRCLE,
            AnalyticalShapeType.POLYNOMIAL -> {
                regenerateUnsmoothedPointsFromAnalyticalShape()
            }
            AnalyticalShapeType.NONE -> {
                // Nothing to regenerate
            }
        }
    }

    companion object {
        fun calculatePathPointsWithDistances(points: List<PointF>): Pair<MutableList<PathPoint>, Float> {
            if (points.isEmpty()) {
                return Pair(mutableListOf(), 0f)
            }

            val pathPoints = mutableListOf<PathPoint>()
            var totalDistance = 0f

            pathPoints.add(PathPoint(points.first(), 0f))

            for (i in 1 until points.size) {
                val p1 = points[i - 1]
                val p2 = points[i]
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                totalDistance += sqrt(dx * dx + dy * dy)
                pathPoints.add(PathPoint(p2, totalDistance))
            }
            return Pair(pathPoints, totalDistance)
        }

        fun preprocessStroke(points: MutableList<PathPoint>): MutableList<PathPoint> {
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

            // If points were added, we need to recalculate distances and total distance
            if (currentPoints.size != points.size) {
                val pointFs = currentPoints.map { it.point }
                val (finalPoints, _) = calculatePathPointsWithDistances(pointFs)
                return finalPoints
            }

            return points
        }
    }

    /**
     * Postprocess a newly drawn stroke to merge bezier and polyline fitting.
     * This runs only once when the stroke is first drawn.
     *
     * @return true if postprocessing succeeded, false if either fit failed
     */
    fun postProcessAfterDrawing(): Boolean {
        // NOTE: This function is called only once per stroke after drawing.
        // The data structures (bezierAnchorPoints, polylineIndices, etc.) are already empty,
        // so we don't need to clear them before populating. If we fail, the stroke will be
        // abandoned by the caller, so cleanup is unnecessary.

        // Step 1: Resample the stroke uniformly with 4x the original point count
        // This improves fitting accuracy by providing uniform sampling
        val originalPointCount = originalPoints.size
        val upsampledPointCount = originalPointCount * 4 // Quadruple the number of points

        // Generate uniformly sampled points from originalPoints
        val upsampledStroke = generateUniformSampled(upsampledPointCount, originalPoints)
        if (upsampledStroke.unsmoothedPoints.isEmpty()) {
            return false
        }

        // Replace this stroke's points with the upsampled version
        originalPoints.clear()
        originalPoints.addAll(upsampledStroke.unsmoothedPoints.map {
            PathPoint(PointF(it.point.x, it.point.y), it.distance)
        })
        unsmoothedPoints.clear()
        unsmoothedPoints.addAll(originalPoints.map {
            PathPoint(PointF(it.point.x, it.point.y), it.distance)
        })
        totalDistance = upsampledStroke.totalDistance

        // Update distancesForWeights with the new distances
        distancesForWeights.clear()
        distancesForWeights.addAll(unsmoothedPoints.map { it.distance })

        // Reapply smoothing to ensure pointsForDrawing is consistent
        applySmoothing()

        // Step 2: Fit polyline to get anchor indices (using the upsampled stroke)
        val polylineFitResult = ShapeFitter.polylineFit(this, this)
        if (polylineFitResult == null) {
            return false
        }

        // Step 3: Use polyline anchor indices to fit bezier with fixed anchors
        val polylineIndicesList = polylineFitResult.fittedStroke.polylineIndices.toList()
        val bezierFitResult = BezierFitter.fitWithFixedAnchors(this, polylineIndicesList)
        if (bezierFitResult == null) {
            return false
        }

        // Both fits succeeded - store the data
        bezierAnchorPoints.addAll(bezierFitResult.anchorPoints)
        bezierControlPoints1.addAll(bezierFitResult.controlPoints1)
        bezierControlPoints2.addAll(bezierFitResult.controlPoints2)
        bezierAnchorIndices.addAll(bezierFitResult.anchorIndices)
        polylineIndices.addAll(polylineIndicesList)

        return true
    }
}