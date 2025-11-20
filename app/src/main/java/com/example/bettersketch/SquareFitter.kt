package com.example.bettersketch

import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.*

object SquareFitter {
    public var strokeForFitting: Stroke = Stroke(mutableListOf(), Paint(), 0f, 0)

    data class SquareParams(
        val centerX: Float,
        val centerY: Float,
        val sideLength: Float,
        val angle: Float // in radians
    )
    
    data class FitResult(
        val params: SquareParams,
        var normalizedError: Float,
        val fittedStroke: Stroke
    )
    
    /**
     * Fits a square to the given stroke using gradient descent optimization.
     * 
     * @param stroke The input stroke to fit
     * @param qualityThreshold Maximum normalized error to accept the fit (e.g., 0.15)
     * @param maxIterations Maximum number of gradient descent iterations
     * @param learningRate Initial learning rate for gradient descent
     * @return FitResult containing the fitted square parameters and stroke, or null if fit quality is poor
     */

    /**
     * Fits a square using iterative angle optimization
     * Center and side length are computed analytically, only angle is optimized
     */
    fun fitSquare(
        qualityThreshold: Float = 0.15f
    ): FitResult? {
        if (strokeForFitting == null || strokeForFitting!!.points.size < 4) return null

        val points = strokeForFitting!!.points.map { it.point }

        // Step 1: Compute centroid (center)
        var sumX = 0f
        var sumY = 0f
        for (point in points) {
            sumX += point.x
            sumY += point.y
        }
        val centerX = sumX / points.size
        val centerY = sumY / points.size

        // Step 2: Compute side length from RMS distance
        // For a perfect square, average distance² from center = (sideLength²)/6
        // So sideLength = sqrt(6 * avgDistance²)
        var sumDistanceSquared = 0f
        for (point in points) {
            val dx = point.x - centerX
            val dy = point.y - centerY
            sumDistanceSquared += dx * dx + dy * dy
        }
        val avgDistanceSquared = sumDistanceSquared / points.size
        val sideLength = sqrt(3f * avgDistanceSquared)

        // Step 3: Find best angle through iterative search
        // Try multiple starting angles and refine the best one
        val candidateAngles = listOf(0f, PI / 4f, PI / 2f, 3f * PI / 4f)
        var bestAngle = 0f
        var bestCost = Float.MAX_VALUE

        for (startAngle in candidateAngles) {
            val (optimizedAngle, cost) = optimizeAngle(
                centerX, centerY, sideLength, startAngle.toFloat(), points
            )
            if (cost < bestCost) {
                bestCost = cost
                bestAngle = optimizedAngle
            }
        }

        val params = SquareParams(
            centerX = centerX,
            centerY = centerY,
            sideLength = sideLength,
            angle = bestAngle
        )

        // Calculate quality metric
        val avgDistance = evaluateAngle(centerX, centerY, sideLength, bestAngle, points)
        val normalizedError = avgDistance / sideLength

        if (normalizedError > qualityThreshold) {
            return null
        }

        val fittedStroke = createSquareStroke(params, strokeForFitting!!.paint)
        return FitResult(params, normalizedError, fittedStroke)
    }

    /**
     * Optimizes the angle using golden section search
     */
    private fun optimizeAngle(
        centerX: Float,
        centerY: Float,
        sideLength: Float,
        startAngle: Float,
        points: List<PointF>
    ): Pair<Float, Float> {
        val goldenRatio = 0.618033988749895f
        val tolerance = 0.001f // ~0.057 degrees
        val searchRange = PI.toFloat() / 4f // Search ±45° from start angle

        var a = startAngle - searchRange
        var b = startAngle + searchRange
        var c = b - (b - a) * goldenRatio
        var d = a + (b - a) * goldenRatio

        var fc = evaluateAngle(centerX, centerY, sideLength, c, points)
        var fd = evaluateAngle(centerX, centerY, sideLength, d, points)

        while (abs(b - a) > tolerance) {
            if (fc < fd) {
                b = d
                d = c
                fd = fc
                c = b - (b - a) * goldenRatio
                fc = evaluateAngle(centerX, centerY, sideLength, c, points)
            } else {
                a = c
                c = d
                fc = fd
                d = a + (b - a) * goldenRatio
                fd = evaluateAngle(centerX, centerY, sideLength, d, points)
            }
        }

        val bestAngle = (a + b) / 2f
        val bestCost = evaluateAngle(centerX, centerY, sideLength, bestAngle, points)
        return Pair(bestAngle, bestCost)
    }

    /**
     * Evaluates the cost (average distance to square edges) for a given angle
     */
    private fun evaluateAngle(
        centerX: Float,
        centerY: Float,
        sideLength: Float,
        angle: Float,
        points: List<PointF>
    ): Float {
        val params = SquareParams(centerX, centerY, sideLength, angle)
        val corners = getSquareCorners(params)
        val sides = listOf(
            Pair(corners[0], corners[1]),
            Pair(corners[1], corners[2]),
            Pair(corners[2], corners[3]),
            Pair(corners[3], corners[0])
        )

        var totalDistance = 0f
        for (point in points) {
            var minDistance = Float.MAX_VALUE
            for (side in sides) {
                val dist = distanceToLineSegment(point, side.first, side.second)
                minDistance = min(minDistance, dist)
            }
            totalDistance += minDistance
        }

        return totalDistance / points.size
    }

    /**
     * Gets the four corners of the square given parameters
     */
    private fun getSquareCorners(params: SquareParams): List<PointF> {
        val halfSide = params.sideLength / 2f
        val cos = cos(params.angle)
        val sin = sin(params.angle)

        // Corners before rotation (centered at origin)
        val localCorners = listOf(
            PointF(-halfSide, -halfSide),
            PointF(halfSide, -halfSide),
            PointF(halfSide, halfSide),
            PointF(-halfSide, halfSide)
        )

        // Rotate and translate to final position
        return localCorners.map { corner ->
            val rotatedX = corner.x * cos - corner.y * sin
            val rotatedY = corner.x * sin + corner.y * cos
            PointF(
                params.centerX + rotatedX,
                params.centerY + rotatedY
            )
        }
    }

    /**
     * Calculates the minimum distance from a point to a line segment
     */
    private fun distanceToLineSegment(point: PointF, segmentStart: PointF, segmentEnd: PointF): Float {
        val dx = segmentEnd.x - segmentStart.x
        val dy = segmentEnd.y - segmentStart.y
        val lengthSquared = dx * dx + dy * dy

        if (lengthSquared == 0f) {
            // Degenerate segment (point)
            return distance(point, segmentStart)
        }

        // Calculate projection parameter t
        val t = ((point.x - segmentStart.x) * dx + (point.y - segmentStart.y) * dy) / lengthSquared

        return when {
            t < 0f -> distance(point, segmentStart) // Beyond start
            t > 1f -> distance(point, segmentEnd)   // Beyond end
            else -> {
                // Perpendicular distance to segment
                val projectionX = segmentStart.x + t * dx
                val projectionY = segmentStart.y + t * dy
                distance(point, PointF(projectionX, projectionY))
            }
        }
    }

    /**
     * Creates a Stroke object representing the fitted square
     */
    private fun createSquareStroke(params: SquareParams, paint: Paint): Stroke {
        val corners = getSquareCorners(params)

        // Create closed path (5 points: 4 corners + first corner again)
        val squarePoints = mutableListOf<PointF>()
        squarePoints.addAll(corners)
        squarePoints.add(corners[0]) // Close the square

        val (pathPoints, totalDistance) = Stroke.calculatePathPointsWithDistances(squarePoints)
        return Stroke(pathPoints, Paint(paint), totalDistance, 0) // smoothness = 0 for geometric shapes
    }

    private fun distance(p1: PointF, p2: PointF): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }
}