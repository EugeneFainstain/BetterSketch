package com.example.bettersketch

import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.sqrt

object LineFitter {

    data class LineParams(
        val start: PointF,
        val end: PointF
    )

    data class FitResult(
        val params: LineParams,
        val normalizedError: Float,
        val fittedStroke: Stroke
    )

    fun fitLine(stroke: Stroke): FitResult? {
        if (stroke.points.size < 2) return null

        val points = stroke.points.map { it.point }
        val startPoint = points.first()
        val endPoint = points.last()

        val params = LineParams(startPoint, endPoint)

        // Calculate the error as the average distance of all points to the line segment
        val totalDistance = points.sumOf { distanceToLineSegment(it, startPoint, endPoint).toDouble() }.toFloat()
        val averageError = totalDistance / points.size

        // Normalize the error by the length of the line
        val lineLength = distance(startPoint, endPoint)
        if (lineLength == 0f) return null
        val normalizedError = averageError / lineLength

        val fittedStroke = createLineStroke(params, stroke.paint)
        return FitResult(params, normalizedError, fittedStroke)
    }

    private fun createLineStroke(params: LineParams, paint: Paint): Stroke {
        val linePoints = listOf(params.start, params.end)
        val (pathPoints, totalDistance) = Stroke.calculatePathPointsWithDistances(linePoints)
        return Stroke(pathPoints, Paint(paint), totalDistance, 0)
    }

    private fun distance(p1: PointF, p2: PointF): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun distanceToLineSegment(point: PointF, segmentStart: PointF, segmentEnd: PointF): Float {
        val dx = segmentEnd.x - segmentStart.x
        val dy = segmentEnd.y - segmentStart.y
        val lengthSquared = dx * dx + dy * dy

        if (lengthSquared == 0f) {
            return distance(point, segmentStart)
        }

        val t = ((point.x - segmentStart.x) * dx + (point.y - segmentStart.y) * dy) / lengthSquared

        return when {
            t < 0f -> distance(point, segmentStart)
            t > 1f -> distance(point, segmentEnd)
            else -> {
                val projectionX = segmentStart.x + t * dx
                val projectionY = segmentStart.y + t * dy
                distance(point, PointF(projectionX, projectionY))
            }
        }
    }
}