package com.example.bettersketch

import android.graphics.Paint
import android.graphics.PointF
import com.example.bettersketch.GeometryUtils.distance
import kotlin.math.*

object CircleFitter {
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

    fun fitCircle(
        stroke: Stroke
    ): FitResult? {
        if (stroke.pointsForDrawing.size < 3) return null

        val points = stroke.pointsForDrawing.map { it.point }

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
        val center = PointF(centerX, centerY)

        var sumDistance = 0f
        for (point in points) {
            sumDistance += distance(point, center)
        }
        val radius = sumDistance / points.size

        val params = CircleParams(
            centerX = centerX,
            centerY = centerY,
            radius = radius
        )

        var maxDeviation = 0f
        for (point in points) {
            val distanceFromCenter = distance(point, center)
            val deviation = abs(distanceFromCenter - radius)
            maxDeviation = max(maxDeviation, deviation)
        }
        val normalizedError = maxDeviation / (2f * radius)

        val fittedStroke = createCircleStroke(params, stroke.paint)
        return FitResult(params, normalizedError, fittedStroke)
    }

    private fun createCircleStroke(params: CircleParams, paint: Paint): Stroke {
        val numPoints = 64
        val circlePoints = mutableListOf<PointF>()

        for (i in 0..numPoints) {
            val angle = 2f * PI.toFloat() * i / numPoints
            val x = params.centerX + params.radius * cos(angle)
            val y = params.centerY + params.radius * sin(angle)
            circlePoints.add(PointF(x, y))
        }

        val (pathPoints, totalDistance) = Stroke.calculatePathPointsWithDistances(circlePoints)
        val stroke = Stroke(pathPoints, Paint(paint), totalDistance, 0)
        stroke.analyticalShapeType = AnalyticalShapeType.CIRCLE

        // Store the circle perimeter points in shapeParameterPoints for regeneration
        stroke.shapeParameterPoints.clear()
        stroke.shapeParameterPoints.addAll(pathPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })

        return stroke
    }
}