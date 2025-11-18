package com.example.bettersketch

import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.sqrt

data class PathPoint(var point: PointF, val distance: Float)

class Stroke(
    pointFs: List<PointF>, // Changed to take PointF list
    val paint: Paint,
    var smoothness: Int
) {
    val points: MutableList<PathPoint>
    // This is mutable ONLY so that global canvas transformations can be applied to it.
    // It should not be structurally changed (add/remove points)after initialization.
    val originalPoints: MutableList<PathPoint>
    var totalDistance: Float // Now calculated internally
    var isModified: Boolean = false

    init {
        val (calculatedPathPoints, calculatedTotalDistance) = calculatePathPointsWithDistances(pointFs)
        this.originalPoints = calculatedPathPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) }.toMutableList()
        this.points = calculatedPathPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) }.toMutableList()
        this.totalDistance = calculatedTotalDistance
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

    fun updatePointsFromPointFs(newPointFs: List<PointF>) {
        val (calculatedPathPoints, calculatedTotalDistance) = calculatePathPointsWithDistances(newPointFs)
        this.points.clear()
        this.points.addAll(calculatedPathPoints)
        this.totalDistance = calculatedTotalDistance
    }

    // Moved from DrawingView.kt and made private
    private fun calculatePathPointsWithDistances(points: List<PointF>): Pair<MutableList<PathPoint>, Float> {
        if (points.isEmpty()) {
            return Pair(mutableListOf(), 0f)
        }

        val pathPoints = mutableListOf<PathPoint>()
        var currentTotalDistance = 0f

        pathPoints.add(PathPoint(points.first(), 0f))

        for (i in 1 until points.size) {
            val p1 = points[i - 1]
            val p2 = points[i]
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            currentTotalDistance += sqrt(dx * dx + dy * dy)
            pathPoints.add(PathPoint(p2, currentTotalDistance))
        }
        return Pair(pathPoints, currentTotalDistance)
    }
}
