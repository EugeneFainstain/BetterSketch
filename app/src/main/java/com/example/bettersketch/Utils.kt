package com.example.bettersketch

import android.graphics.PointF
import kotlin.math.sqrt

/**
 * Utility object for common geometric operations used across the application.
 */
object GeometryUtils {

    /**
     * Linear interpolation between two points.
     * @param p1 Start point
     * @param p2 End point
     * @param t Interpolation parameter (0.0 = p1, 1.0 = p2)
     * @return Interpolated point
     */
    fun lerp(p1: PointF, p2: PointF, t: Float): PointF {
        return PointF(
            p1.x + (p2.x - p1.x) * t,
            p1.y + (p2.y - p1.y) * t
        )
    }

    /**
     * Evaluate a cubic Bezier curve at parameter t.
     * @param p0 Start anchor point
     * @param p1 First control point (outgoing from p0)
     * @param p2 Second control point (incoming to p3)
     * @param p3 End anchor point
     * @param t Parameter (0.0 = p0, 1.0 = p3)
     * @return Point on the curve at parameter t
     */
    fun evaluateCubicBezier(p0: PointF, p1: PointF, p2: PointF, p3: PointF, t: Float): PointF {
        val t2 = t * t
        val t3 = t2 * t
        val mt = 1.0f - t
        val mt2 = mt * mt
        val mt3 = mt2 * mt

        return PointF(
            p0.x * mt3 + 3 * p1.x * mt2 * t + 3 * p2.x * mt * t2 + p3.x * t3,
            p0.y * mt3 + 3 * p1.y * mt2 * t + 3 * p2.y * mt * t2 + p3.y * t3
        )
    }

    /**
     * Calculate Euclidean distance between two points.
     */
    fun distance(p1: PointF, p2: PointF): Float {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * Calculate squared Euclidean distance between two points (faster when you don't need the actual distance).
     */
    fun distanceSquared(p1: PointF, p2: PointF): Float {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        return dx * dx + dy * dy
    }
}