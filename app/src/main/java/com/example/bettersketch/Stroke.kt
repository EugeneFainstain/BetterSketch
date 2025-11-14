package com.example.bettersketch

import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF

data class PathPoint(val point: PointF, val distance: Float)

class Stroke(
    val points: MutableList<PathPoint>,
    val paint: Paint,
    val totalDistance: Float
) {
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
