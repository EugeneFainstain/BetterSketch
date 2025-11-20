package com.example.bettersketch

import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.*

object CircleFitter {
    var strokeForFitting: Stroke = Stroke(mutableListOf(), Paint(), 0f, 0)

    data class CircleParams(
        val centerX: Float,
        val centerY: Float,
        val radius: Float
    )

    data class FitResult(
        val params: CircleParams,
        var normalizedError: Float,
        val fittedStroke: Stroke
    )

    /**
     * Fits a circle to the given stroke using analytical solution.
     * Center is computed as the center of the bounding box, radius as average distance from center.
     *
     * @param qualityThreshold Maximum normalized error to accept the fit (e.g., 0.15)
     * @return FitResult containing the fitted circle parameters and stroke, or null if fit quality is poor
     */
    fun fitCircle(
        qualityThreshold: Float = 0.15f
    ): FitResult? {
        if (strokeForFitting.points.size < 3) return null

        val points = strokeForFitting.points.map { it.point }

        // Step 1: Compute bounding box center
        var minX = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE

        for (point in points) {
            if (point.x < minX) minX = point.x
            if (point.x > maxX) maxX = point.x
            if (point.y < minY) minY = point.y
            if (point.y > maxY) maxY = point.y
        }

        val centerX = (minX + maxX) / 2f
        val centerY = (minY + maxY) / 2f

        // Step 2: Compute radius as average distance from center
        var sumDistance = 0f
        for (point in points) {
            val dx = point.x - centerX
            val dy = point.y - centerY
            sumDistance += sqrt(dx * dx + dy * dy)
        }
        val radius = sumDistance / points.size

        val params = CircleParams(
            centerX = centerX,
            centerY = centerY,
            radius = radius
        )

        // Calculate quality metric: maximum absolute deviation from circle
        var maxDeviation = 0f
        for (point in points) {
            val dx = point.x - centerX
            val dy = point.y - centerY
            val distanceFromCenter = sqrt(dx * dx + dy * dy)
            val deviation = abs(distanceFromCenter - radius)
            maxDeviation = max(maxDeviation, deviation)
        }
        val normalizedError = maxDeviation / (2f*radius)

        if (normalizedError > qualityThreshold) {
            return null
        }

        val fittedStroke = createCircleStroke(params, strokeForFitting.paint)
        return FitResult(params, normalizedError, fittedStroke)
    }


    /**
     * Creates a Stroke object representing the fitted circle
     */
    private fun createCircleStroke(params: CircleParams, paint: Paint): Stroke {
        val numPoints = 64 // Number of points to approximate the circle
        val circlePoints = mutableListOf<PointF>()

        for (i in 0..numPoints) {
            val angle = 2f * PI.toFloat() * i / numPoints
            val x = params.centerX + params.radius * cos(angle)
            val y = params.centerY + params.radius * sin(angle)
            circlePoints.add(PointF(x, y))
        }

        val (pathPoints, totalDistance) = Stroke.calculatePathPointsWithDistances(circlePoints)
        return Stroke(pathPoints, Paint(paint), totalDistance, 0) // smoothness = 0 for geometric shapes
    }
}