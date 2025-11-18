package com.example.bettersketch

import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.sqrt

data class PathPoint(var point: PointF, val distance: Float) // Changed 'val point' to 'var point'

class Stroke(
    incomingPoints: List<PathPoint>,
    val paint: Paint,
    var totalDistance: Float, // Changed from val to var
    var smoothness: Int
) {
    val points: MutableList<PathPoint>
    // This is mutable ONLY so that global canvas transformations can be applied to it.
    // It should not be structurally changed (add/remove points)after initialization.
    val originalPoints: MutableList<PathPoint>
    var isModified: Boolean = false

    init {
        // Create deep copies of the incoming points to ensure the Stroke owns its own data.
        this.originalPoints = incomingPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) }.toMutableList()
        this.points = incomingPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) }.toMutableList()
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
    }
}
