package com.example.bettersketch

import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.sqrt

data class PathPoint(var point: PointF, var distance: Float)

class Stroke(
    val paint: Paint,
    var smoothness: Int
) {
    val points: MutableList<PathPoint> = mutableListOf() // Smoothed points for drawing
    val originalPoints: MutableList<PathPoint> = mutableListOf() // Original points for undo/reset
    val unsmoothedPoints: MutableList<PathPoint> = mutableListOf() // Unsmoothed points for editing
    var totalDistance: Float = 0f
    var isModified: Boolean = false
    val childStrokes: MutableList<Stroke> = mutableListOf()
    val isGroup: Boolean get() = childStrokes.isNotEmpty()
    var isHighlighted: Boolean = false

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
        if (this.smoothness == 0) {
            this.points.clear()
            this.points.addAll(this.unsmoothedPoints.map { p -> PathPoint(PointF(p.point.x, p.point.y), p.distance) })
            return
        }

        var smoothedPoints = this.unsmoothedPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) }.toMutableList()

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
        this.points.clear()
        this.points.addAll(finalPoints)
        this.totalDistance = newTotalDistance // Update totalDistance based on smoothed points
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
            if (stroke.points.isNotEmpty()) {
                val strokeBounds = RectF(stroke.points.first().point.x, stroke.points.first().point.y, stroke.points.first().point.x, stroke.points.first().point.y)
                for (i in 1 until stroke.points.size) {
                    strokeBounds.union(stroke.points[i].point.x, stroke.points[i].point.y)
                }
                bounds.union(strokeBounds)
            }
        }
        return bounds
    }

    fun newFrom(): Stroke {
        val newStroke = Stroke(Paint(this.paint), this.smoothness)
        newStroke.copyFrom(this, forDuplication = true) // Use copyFrom with forDuplication flag
        return newStroke
    }

    fun copyFrom(other: Stroke, forDuplication: Boolean = false) {
        this.paint.set(other.paint)
        this.smoothness = other.smoothness
        this.points.clear()
        this.points.addAll(other.points.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })

        this.unsmoothedPoints.clear()
        this.unsmoothedPoints.addAll(other.unsmoothedPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })

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
        if (N < 2 || points.isEmpty()) return Stroke(mutableListOf(), Paint(), 0f, 0)

        val uniformPoints = mutableListOf<PointF>()
        val spacing = totalDistance / (N - 1)

        // Always add the first point
        uniformPoints.add(PointF(points.first().point.x, points.first().point.y))

        // Generate N-2 intermediate points at uniform distances
        for (i in 1 until N - 1) {
            val targetDistance = i * spacing
            val interpolatedPoint = interpolatePointAtDistance(targetDistance)
            if (interpolatedPoint != null) {
                uniformPoints.add(interpolatedPoint)
            }
        }

        // Always add the last point
        uniformPoints.add(PointF(points.last().point.x, points.last().point.y))

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
        if (targetDistance < 0 || targetDistance > totalDistance || points.size < 2) {
            return null
        }

        // Find the two points that bracket the target distance
        for (i in 1 until points.size) {
            val prevPoint = points[i - 1]
            val currPoint = points[i]

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
        return PointF(points.last().point.x, points.last().point.y)
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