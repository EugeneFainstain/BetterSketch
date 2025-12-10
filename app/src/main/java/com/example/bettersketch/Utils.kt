package com.example.bettersketch

import android.graphics.PointF
import kotlin.math.sqrt
import android.graphics.Matrix

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

    /**
     * Calculate the dot product of two 2D vectors (represented as PointF)
     */
    fun dot(p1: PointF, p2: PointF): Float = p1.x * p2.x + p1.y * p2.y

    /**
     * Add two points (vector addition)
     */
    fun add(p1: PointF, p2: PointF): PointF = PointF(p1.x + p2.x, p1.y + p2.y)

    /**
     * Subtract two points (vector subtraction)
     */
    fun subtract(p1: PointF, p2: PointF): PointF = PointF(p1.x - p2.x, p1.y - p2.y)

    /**
     * Scale a point by a scalar value
     */
    fun scale(p: PointF, s: Float): PointF = PointF(p.x * s, p.y * s)

    /**
     * Normalize a vector to unit length
     */
    fun normalize(p: PointF): PointF {
        val len = sqrt(p.x * p.x + p.y * p.y)
        return if (len > 1e-6f) PointF(p.x / len, p.y / len) else PointF(0f, 0f)
    }

    /**
     * Calculate the scale factor from a transformation matrix.
     * Uses the Pythagorean theorem to calculate the scale, which is robust against rotation.
     * 
     * @param matrix The transformation matrix
     * @return The scale factor
     */
    fun getScaleFromMatrix(matrix: Matrix): Float {
        val values = FloatArray(9)
        matrix.getValues(values)
        // Use the pythagorean theorem to calculate the scale, which is robust against rotation
        val scaleX = values[Matrix.MSCALE_X]
        val skewY = values[Matrix.MSKEW_Y]
        return sqrt(scaleX * scaleX + skewY * skewY)
    }
}