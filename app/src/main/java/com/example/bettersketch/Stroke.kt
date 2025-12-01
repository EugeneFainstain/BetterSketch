package com.example.bettersketch

import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.sqrt

data class PathPoint(var point: PointF, var distance: Float)

enum class AnalyticalShapeType {
    NONE,           // No analytical shape
    SQUARE,         // Square shape
    CIRCLE,         // Circle shape
    POLYLINE,       // Polyline (connected line segments)
    POLYNOMIAL      // Polynomial curve
}

class Stroke(
    val paint: Paint,
    var smoothness: Int
) {
    val pointsForDrawing: MutableList<PathPoint> = mutableListOf() // Smoothed points for drawing
    val originalPoints: MutableList<PathPoint> = mutableListOf() // Original points for undo/reset
    val unsmoothedPoints: MutableList<PathPoint> = mutableListOf() // Unsmoothed points for editing
    val polylinePoints: MutableList<PathPoint> = mutableListOf() // Analytical polyline points
    val polylineIndices: MutableList<Int> = mutableListOf() // Indices of the original points that correspond to the polyline vertices
    var totalDistance: Float = 0f
    var isModified: Boolean = false
    val childStrokes: MutableList<Stroke> = mutableListOf()
    val isGroup: Boolean get() = childStrokes.isNotEmpty()
    var isHighlighted: Boolean = false

    var analyticalShapeType: AnalyticalShapeType = AnalyticalShapeType.NONE // Type of analytical shape
    var isPolyline: Boolean = false // Flag to indicate that the curve has been approximated by a polyline
    var needsToRegenerate: Boolean = false // Flag to regenerate unsmoothedPoints from analytical


    // Secondary constructor for creating a stroke from existing points (like the original constructor)
    constructor(incomingPoints: List<PathPoint>, paint: Paint, totalDistance: Float, smoothness: Int) : this(paint, smoothness) {
        // incomingPoints are considered the initial unsmoothed points
        this.originalPoints.addAll(incomingPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })
        this.unsmoothedPoints.addAll(incomingPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })
        this.totalDistance = totalDistance // This totalDistance is based on incomingPoints
        applySmoothing() // Apply smoothing to generate 'points' from 'unsmoothedPoints'
    }

    // Constructor for grouping existing strokes
    constructor(strokesToGroup: MutableList<Stroke>, paint: Paint) : this(Paint(paint), 0) {
        this.childStrokes.addAll(strokesToGroup)
        this.paint.strokeWidth = 10f;
    }

    fun getAssociatedPolylinePointsOnSmoothedCurve(): List<PointF> {
        if (polylinePoints.isEmpty() || pointsForDrawing.isEmpty()) {
            return emptyList()
        }

        val associatedPoints = mutableListOf<PointF>()
        val totalDrawingDistance = pointsForDrawing.last().distance
        val totalPolylineDistance = polylinePoints.last().distance

        if (totalDrawingDistance == 0f || totalPolylineDistance == 0f) {
            return emptyList()
        }

        for (polylinePoint in polylinePoints) {
            val distanceRatio = polylinePoint.distance / totalPolylineDistance
            val targetDrawingDistance = distanceRatio * totalDrawingDistance

            // Find the closest point in pointsForDrawing by distance
            var minDistanceDiff = Float.MAX_VALUE
            var closestPoint: PointF? = null

            // Find the two points that bracket the target distance
            var found = false
            for (i in 1 until pointsForDrawing.size) {
                val prevPoint = pointsForDrawing[i - 1]
                val currPoint = pointsForDrawing[i]

                if (targetDrawingDistance >= prevPoint.distance && targetDrawingDistance <= currPoint.distance) {
                    // Interpolate between prevPoint and currPoint
                    val segmentLength = currPoint.distance - prevPoint.distance
                    val t = if (segmentLength == 0f) 0f else (targetDrawingDistance - prevPoint.distance) / segmentLength
                    val x = prevPoint.point.x + t * (currPoint.point.x - prevPoint.point.x)
                    val y = prevPoint.point.y + t * (currPoint.point.y - prevPoint.point.y)
                    closestPoint = PointF(x, y)
                    found = true
                    break
                }
            }

            if (!found) {
                // If not found (e.g., for the very last point due to float precision), take the last point
                closestPoint = pointsForDrawing.last().point
            }


            closestPoint?.let {
                associatedPoints.add(it)
            }
        }

        return associatedPoints
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

        // Choose the source points based on whether we're in polyline mode
        val sourcePoints = if (isPolyline && polylinePoints.isNotEmpty()) {
            polylinePoints
        } else {
            unsmoothedPoints
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

                val avgX = (prev.x + next.x) / 2f
                val avgY = (prev.y + next.y) / 2f

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
        if (polylinePoints.isEmpty()) {
            // Cannot toggle if there's no polyline data
            return
        }

        isPolyline = !isPolyline
        isModified = true

        if (isPolyline) {
            // Switch to polyline representation
            analyticalShapeType = AnalyticalShapeType.POLYLINE
            regenerateUnsmoothedPointsFromAnalytical()
        } else {
            // Switch back to original representation
            analyticalShapeType = AnalyticalShapeType.NONE
            unsmoothedPoints.clear()
            unsmoothedPoints.addAll(originalPoints.map { p -> PathPoint(PointF(p.point.x, p.point.y), p.distance) })

            // Recalculate distances for the restored points
            val (recalculatedPoints, newTotalDistance) = calculatePathPointsWithDistances(unsmoothedPoints.map { it.point })
            unsmoothedPoints.clear()
            unsmoothedPoints.addAll(recalculatedPoints)
            totalDistance = newTotalDistance

            // Re-apply smoothing
            applySmoothing()
        }
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
        this.isPolyline = other.isPolyline
        this.pointsForDrawing.clear()
        this.pointsForDrawing.addAll(other.pointsForDrawing.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })

        this.unsmoothedPoints.clear()
        this.unsmoothedPoints.addAll(other.unsmoothedPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })

        this.polylinePoints.clear()
        this.polylinePoints.addAll(other.polylinePoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })

        this.polylineIndices.clear()
        this.polylineIndices.addAll(other.polylineIndices)

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
     * Regenerates unsmoothedPoints from analytical points based on the analytical shape type.
     * This is useful after transformations to ensure the unsmoothed points accurately
     * represent the analytical shape geometry.
     *
     * @param targetPointCount The desired number of interpolated points
     */
    fun regenerateUnsmoothedPointsFromAnalytical() {

        needsToRegenerate = false

        if (!isPolyline) return

        val pointCount = originalPoints.size
        if (pointCount < 2) return

        val interpolatedPoints = mutableListOf<PointF>()

        when (analyticalShapeType) {
            AnalyticalShapeType.SQUARE -> {
                // For squares: polylinePoints contains the 4 corners (+ closed point)
                interpolatedPoints.addAll(interpolateAlongPolyLine(polylinePoints.map { it.point }, pointCount))
            }
            AnalyticalShapeType.CIRCLE -> {
                // For circles: polylinePoints contains points around the circle perimeter
                interpolatedPoints.addAll(interpolateAlongPolyLine(polylinePoints.map { it.point }, pointCount))
            }
            AnalyticalShapeType.POLYLINE -> {
                // For polylines: polylinePoints are the vertices
                val result = interpolateAlongPolyLineWithIndices(polylinePoints.map { it.point }, pointCount)
                interpolatedPoints.addAll(result.first)
                // Update polylineIndices to match the new unsmoothedPoints
                polylineIndices.clear()
                polylineIndices.addAll(result.second)
            }
            AnalyticalShapeType.POLYNOMIAL -> {
                // For polynomials: polylinePoints are the curve points
                interpolatedPoints.addAll(interpolateAlongPolyLine(polylinePoints.map { it.point }, pointCount))
            }
            AnalyticalShapeType.NONE -> {
                // No analytical shape, cannot regenerate
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
     * Helper function to interpolate points along a polyline WITH tracking of vertex indices.
     * Returns both the interpolated points and the indices where vertices ended up.
     * Used by regenerateUnsmoothedPointsFromAnalytical for polylines.
     */
    private fun interpolateAlongPolyLineWithIndices(vertices: List<PointF>, targetPointCount: Int): Pair<List<PointF>, List<Int>> {
        if (vertices.size < 2 || targetPointCount < 2) return Pair(vertices, vertices.indices.toList())

        val interpolatedPoints = mutableListOf<PointF>()
        val vertexIndices = mutableListOf<Int>()

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
            return Pair(listOf(vertices.first()), listOf(0))
        }

        // Generate uniform spacing points and include vertex points
        val targetDistances = mutableListOf<Pair<Float, Int?>>() // (distance, vertexIndex if this is a vertex)
        val spacing = totalDistance / (targetPointCount - 1)
        
        // Add uniformly spaced points
        for (i in 0 until targetPointCount) {
            targetDistances.add(Pair(i * spacing, null))
        }
        
        // Add all vertex distances with their indices
        for (i in vertexDistances.indices) {
            targetDistances.add(Pair(vertexDistances[i], i))
        }

        // Sort by distance and remove duplicates (keep vertex marker if present)
        val sortedDistances = targetDistances
            .groupBy { it.first }
            .mapValues { entry -> entry.value.firstOrNull { it.second != null } ?: entry.value.first() }
            .toSortedMap()
            .values.toList()

        // Interpolate at all target distances and track vertex indices
        for ((distance, vertexIdx) in sortedDistances) {
            val point = interpolatePointOnPolyLine(vertices, distance)
            interpolatedPoints.add(point)
            
            if (vertexIdx != null) {
                vertexIndices.add(interpolatedPoints.size - 1)
            }
        }

        return Pair(interpolatedPoints, vertexIndices)
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
        for (targetDist in targetDistances.sorted()) {
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