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
    val bezierAnchorIndices: MutableList<Int> = mutableListOf()        // Closest unsmoothedPoints indices to anchors
    val bezierAnchorPointsForDrawingIndices: MutableList<Int> = mutableListOf()  // Index of each anchor in pointsForDrawing
    var renderAsBezier: Boolean = false                                 // Toggle for bezier rendering

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
            regenerateBezierCurve()
        }

        applySmoothing()
    }

    /**
     * Regenerate pointsForDrawing from bezier curve data
     */
    fun regenerateBezierCurve() {
        if (!hasBezierData() || !renderAsBezier) return

        val pointCount = originalPoints.size * 4
        if (pointCount < 2) return

        // Interpolate points along the bezier curve
        val interpolatedPoints = interpolateAlongBezierCurve(pointCount)

        // Update unsmoothed points with bezier-interpolated points
        val (pathPoints, newTotalDistance) = calculatePathPointsWithDistances(interpolatedPoints)
        unsmoothedPoints.clear()
        unsmoothedPoints.addAll(pathPoints)
        totalDistance = newTotalDistance
    }

    /**
     * Interpolate points along the bezier curve, with anchors pinned at specific indices
     */
    private fun interpolateAlongBezierCurve(targetPointCount: Int): List<PointF> {
        if (bezierAnchorPoints.size < 2) return emptyList()

        val numSegments = bezierAnchorPoints.size - 1
        if (numSegments < 1 ||
            bezierControlPoints1.size != bezierAnchorPoints.size ||
            bezierControlPoints2.size != bezierAnchorPoints.size) {
            return emptyList()
        }

        // First, estimate the arc length of each segment
        val segmentLengths = mutableListOf<Float>()
        var totalLength = 0f

        for (segIndex in 0 until numSegments) {
            val p0 = bezierAnchorPoints[segIndex]
            val p1 = bezierControlPoints1[segIndex]
            val p2 = bezierControlPoints2[segIndex + 1]
            val p3 = bezierAnchorPoints[segIndex + 1]

            val length = estimateBezierArcLength(p0, p1, p2, p3)
            segmentLengths.add(length)
            totalLength += length
        }

        if (totalLength <= 0f) return listOf(bezierAnchorPoints.first())

        // Allocate points to each segment proportionally to its arc length
        val pointsPerSegment = IntArray(numSegments)

        for (segIndex in 0 until numSegments) {
            val ratio = segmentLengths[segIndex] / totalLength
            val idealPointCount = (targetPointCount - 1) * ratio
            pointsPerSegment[segIndex] = idealPointCount.toInt().coerceAtLeast(1)
        }

        // Update bezierAnchorPointsForDrawingIndices - track where each anchor appears in pointsForDrawing
        bezierAnchorPointsForDrawingIndices.clear()
        var cumulativePoints = 0
        for (i in bezierAnchorPoints.indices) {
            bezierAnchorPointsForDrawingIndices.add(cumulativePoints)
            if (i < numSegments) {
                cumulativePoints += pointsPerSegment[i]
            }
        }

        // Update bezierAnchorIndices to reflect where anchors map to in the interpolated points
        // Note - this is a bug because it doesn't account for quadrupling of Bezier points
        bezierAnchorIndices.clear()
        bezierAnchorIndices.addAll(bezierAnchorPointsForDrawingIndices)

        // Generate points with anchors pinned
        val interpolatedPoints = mutableListOf<PointF>()

        for (segIndex in 0 until numSegments) {
            val p0 = bezierAnchorPoints[segIndex]
            val p1 = bezierControlPoints1[segIndex]
            val p2 = bezierControlPoints2[segIndex + 1]
            val p3 = bezierAnchorPoints[segIndex + 1]

            val numPointsInSegment = pointsPerSegment[segIndex]

            // Add points for this segment (excluding the end anchor)
            for (i in 0 until numPointsInSegment) {
                val t = i.toFloat() / numPointsInSegment
                val point = evaluateCubicBezier(p0, p1, p2, p3, t)
                interpolatedPoints.add(point)
            }
        }

        // Always add the last anchor explicitly to ensure it's pinned
        interpolatedPoints.add(PointF(bezierAnchorPoints.last().x, bezierAnchorPoints.last().y))

        return interpolatedPoints
    }
    
    /**
     * Estimate the arc length of a cubic Bezier curve
     */
    public fun estimateBezierArcLength(p0: PointF, p1: PointF, p2: PointF, p3: PointF): Float {
        // Use adaptive sampling to estimate arc length
        val samples = 20
        var length = 0f
        var prevPoint = p0

        for (i in 1..samples) {
            val t = i.toFloat() / samples
            val point = evaluateCubicBezier(p0, p1, p2, p3, t)
            val dx = point.x - prevPoint.x
            val dy = point.y - prevPoint.y
            length += sqrt(dx * dx + dy * dy)
            prevPoint = point
        }

        return length
    }

    /**
     * Evaluate cubic bezier at parameter t
     */
    private fun evaluateCubicBezier(p0: PointF, p1: PointF, p2: PointF, p3: PointF, t: Float): PointF {
        return GeometryUtils.evaluateCubicBezier(p0, p1, p2, p3, t)
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
            regenerateBezierCurve()
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

        regenerateInterpolatedPolylinePoints()
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

        this.bezierAnchorPointsForDrawingIndices.clear()
        this.bezierAnchorPointsForDrawingIndices.addAll(other.bezierAnchorPointsForDrawingIndices)

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
     * @return A new Stroke with uniformly sampled points, or null if N < 2 or stroke has no points
     */
    fun generateUniformSampled(N: Int): Stroke {
        if (N < 2 || pointsForDrawing.isEmpty()) return Stroke(mutableListOf(), Paint(), 0f, 0)

        val uniformPoints = mutableListOf<PointF>()
        val spacing = totalDistance / (N - 1)

        // Always add the first point
        uniformPoints.add(PointF(pointsForDrawing.first().point.x, pointsForDrawing.first().point.y))

        // Generate N-2 intermediate points at uniform distances
        for (i in 1 until N - 1) {
            val targetDistance = i * spacing
            val interpolatedPoint = interpolatePointAtDistance(targetDistance)
            if (interpolatedPoint != null) {
                uniformPoints.add(interpolatedPoint)
            }
        }

        // Always add the last point
        uniformPoints.add(PointF(pointsForDrawing.last().point.x, pointsForDrawing.last().point.y))

        // Create new stroke with uniformly sampled points
        val (pathPoints, newTotalDistance) = calculatePathPointsWithDistances(uniformPoints)
        return Stroke(pathPoints, Paint(this.paint), newTotalDistance, 0) // smoothness = 0 to preserve exact points
    }

    /**
     * Interpolates a point at a specific distance along the stroke path.
     *
     * @param targetDistance The distance along the path where the point should be interpolated
     * @return The interpolated PointF, or null if targetDistance is out of bounds
     */
    private fun interpolatePointAtDistance(targetDistance: Float): PointF? {
        if (targetDistance < 0 || targetDistance > totalDistance || pointsForDrawing.size < 2) {
            return null
        }

        // Find the two points that bracket the target distance
        for (i in 1 until pointsForDrawing.size) {
            val prevPoint = pointsForDrawing[i - 1]
            val currPoint = pointsForDrawing[i]

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
        return PointF(pointsForDrawing.last().point.x, pointsForDrawing.last().point.y)
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
                interpolateAlongPolyLine(shapeParameterPoints.map { it.point }, pointCount)
            }
            AnalyticalShapeType.CIRCLE -> {
                // For circles: shapeParameterPoints contains points around the circle perimeter
                interpolateAlongPolyLine(shapeParameterPoints.map { it.point }, pointCount)
            }
            AnalyticalShapeType.POLYNOMIAL -> {
                // For polynomials: shapeParameterPoints are the curve points
                interpolateAlongPolyLine(shapeParameterPoints.map { it.point }, pointCount)
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
     * Regenerates interpolatedPolylinePoints from polylinePoints (vertex-only representation).
     * This creates a piece-wise linear interpolation between vertices.
     */


    fun regenerateInterpolatedPolylinePoints() {
        // Try to regenerate interpolatedPolylinePoints, or skip if conditions aren't met
        // Early exit conditions - if any fail, skip to applySmoothing
        if (polylineIndices.isEmpty() || unsmoothedPoints.isEmpty()) return

        val pointCount = originalPoints.size
        if (pointCount < 2) return

        // Extract vertices from unsmoothedPoints using polylineIndices
        val vertices = polylineIndices.mapNotNull { index ->
            if (index >= 0 && index < unsmoothedPoints.size) {
                unsmoothedPoints[index].point
            } else {
                null
            }
        }

        if (vertices.isEmpty()) return

        // Interpolate along the polyline vertices with vertices placed at their specific indices
        val interpolatedPoints = interpolateAlongPolyLineWithIndices(
            vertices,
            polylineIndices
        )

        // Update interpolatedPolylinePoints
        val (pathPoints, newTotalDistance) = calculatePathPointsWithDistances(interpolatedPoints)
        interpolatedPolylinePoints.clear()
        interpolatedPolylinePoints.addAll(pathPoints)
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



    /**
     * Helper function to interpolate points along a polyline WITH vertex placement at specific indices.
     * Takes vertices and the indices where they should appear in the output.
     * Returns the interpolated points only.
     */
    private fun interpolateAlongPolyLineWithIndices(vertices: List<PointF>, vertexIndices: List<Int>): List<PointF> {
        if (vertices.size < 2 || vertexIndices.size < 2) return vertices

        // The target point count is determined by the last index + 1
        val targetPointCount = vertexIndices.last() + 1
        if (targetPointCount < 2) return vertices

        val interpolatedPoints = MutableList<PointF?>(targetPointCount) { null }

        // Place each vertex at its designated index
        for (i in vertices.indices) {
            val index = vertexIndices[i]
            interpolatedPoints[index] = vertices[i]
        }

        // Fill in the gaps between vertices with linear interpolation
        for (i in 0 until vertices.size - 1) {
            val startIdx = vertexIndices[i]
            val endIdx = vertexIndices[i + 1]
            val startPoint = vertices[i]
            val endPoint = vertices[i + 1]

            val segmentPointCount = endIdx - startIdx + 1

            // Interpolate points between startIdx and endIdx
            for (j in 0 until segmentPointCount) {
                val t = j.toFloat() / (segmentPointCount - 1).toFloat()
                val x = startPoint.x + t * (endPoint.x - startPoint.x)
                val y = startPoint.y + t * (endPoint.y - startPoint.y)
                interpolatedPoints[startIdx + j] = PointF(x, y)
            }
        }

        // Return the list, filtering out any nulls (shouldn't be any, but safe)
        return interpolatedPoints.filterNotNull()
    }

    /**
     * Helper function to interpolate points along a polyline.
     * Used by regenerateUnsmoothedPointsFromAnalytical for all shape types.
     * Private - only used internally by the stroke.
     */
    private fun interpolateAlongPolyLine(vertices: List<PointF>, targetPointCount: Int): List<PointF> {
        if (vertices.size < 2 || targetPointCount < 2) return vertices

        val interpolatedPoints = mutableListOf<PointF>()

        // Calculate cumulative distances for each vertex
        val vertexDistances = mutableListOf(0f)
        var totalDistance = 0f
        for (i in 1 until vertices.size) {
            val dx = vertices[i].x - vertices[i - 1].x
            val dy = vertices[i].y - vertices[i - 1].y
            totalDistance += sqrt(dx * dx + dy * dy)
            vertexDistances.add(totalDistance)
        }

        if (totalDistance <= 0f) {
            // Degenerate case: all vertices are at the same point
            return listOf(vertices.first())
        }

        // Generate uniform spacing points and include vertex points
        val targetDistances = mutableSetOf<Float>()
        val spacing = totalDistance / (targetPointCount - 1)
        
        // Add uniformly spaced points
        for (i in 0 until targetPointCount) {
            targetDistances.add(i * spacing)
        }
        
        // Add all vertex distances to ensure they're included
        targetDistances.addAll(vertexDistances)

        // Interpolate at all target distances
        for (targetDist in targetDistances) {
            val point = interpolatePointOnPolyLine(vertices, targetDist)
            interpolatedPoints.add(point)
        }

        return interpolatedPoints
    }

    /**
     * Interpolates a point at a specific distance along the polyline.
     * Private - only used internally by the stroke.
     */
    private fun interpolatePointOnPolyLine(vertices: List<PointF>, targetDistance: Float): PointF {
        if (vertices.size < 2) return vertices.first()

        var accumulatedDistance = 0f

        for (i in 1 until vertices.size) {
            val start = vertices[i - 1]
            val end = vertices[i]
            val dx = end.x - start.x
            val dy = end.y - start.y
            val segmentLength = sqrt(dx * dx + dy * dy)

            if (accumulatedDistance + segmentLength >= targetDistance) {
                // Target distance is within this segment
                val remainingDistance = targetDistance - accumulatedDistance
                val t = if (segmentLength > 0f) remainingDistance / segmentLength else 0f
                val x = start.x + t * dx
                val y = start.y + t * dy
                return PointF(x, y)
            }

            accumulatedDistance += segmentLength
        }

        // If we reach here, return the last vertex
        return vertices.last()
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
}