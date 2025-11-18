package com.example.bettersketch

import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.sqrt

data class PathPoint(var point: PointF, val distance: Float)

class Stroke(
    val paint: Paint,
    var smoothness: Int
) {
    val points: MutableList<PathPoint> = mutableListOf() // Smoothed points for drawing
    val originalPoints: MutableList<PathPoint> = mutableListOf() // Original points for undo/reset
    val unsmoothedPoints: MutableList<PathPoint> = mutableListOf() // Unsmoothed points for editing
    var totalDistance: Float = 0f
    var isModified: Boolean = false
    var originalStrokeWidth: Float = paint.strokeWidth // Store original stroke width

    // Secondary constructor for creating a stroke from existing points (like the original constructor)
    constructor(incomingPoints: List<PathPoint>, paint: Paint, totalDistance: Float, smoothness: Int) : this(paint, smoothness) {
        // incomingPoints are considered the initial unsmoothed points
        this.originalPoints.addAll(incomingPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })
        this.unsmoothedPoints.addAll(incomingPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })
        this.totalDistance = totalDistance // This totalDistance is based on incomingPoints
        applySmoothing() // Apply smoothing to generate 'points' from 'unsmoothedPoints'
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

    /**
     * Calculates the bounding box of the stroke.
     * This is essential for finding the center point of the stroke, which is used as a pivot
     * for scaling and rotation transformations.
     */
    fun getBounds(): RectF {
        if (points.isEmpty()) return RectF()
        val bounds = RectF(points.first().point.x, points.first().point.y, points.first().point.x, points.first().point.y)
        for (i in 1 until points.size) {
            bounds.union(points[i].point.x, points[i].point.y)
        }
        return bounds
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
