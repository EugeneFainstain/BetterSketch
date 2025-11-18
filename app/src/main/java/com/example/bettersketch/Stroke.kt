package com.example.bettersketch

import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF

data class PathPoint(var point: PointF, val distance: Float) // Changed 'val point' to 'var point'

class Stroke(
    incomingPoints: List<PathPoint>,
    val paint: Paint,
    var totalDistance: Float, // Changed from val to var
    var smoothness: Int
) {
    val points: MutableList<PathPoint>
    val originalPoints: MutableList<PathPoint>
    val unsmoothedPoints: MutableList<PathPoint> // New property for unsmoothed points
    var isModified: Boolean = false

    init {
        // Create deep copies of the incoming points to ensure the Stroke owns its own data.
        this.originalPoints = incomingPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) }.toMutableList()
        this.unsmoothedPoints = incomingPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) }.toMutableList() // Initialize unsmoothed points
        this.points = incomingPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) }.toMutableList() // Initial state, will be smoothed later
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
}
