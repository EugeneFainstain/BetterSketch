package com.example.bettersketch

import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.*

object SquareFitter {
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

    fun fitSquare(
        stroke: Stroke
    ): FitResult? {
        if (stroke.pointsForDrawing.size < 4) return null

        val points = stroke.pointsForDrawing.map { it.point }

        // Step 1: Compute centroid (center)
        var sumX = 0f
        var sumY = 0f
        for (point in points) {
            sumX += point.x
            sumY += point.y
        }
        var centerX = sumX / points.size
        var centerY = sumY / points.size

        // Step 2: Compute side length from RMS distance
        var sumDistanceSquared = 0f
        for (point in points) {
            val dx = point.x - centerX
            val dy = point.y - centerY
            sumDistanceSquared += dx * dx + dy * dy
        }
        val avgDistanceSquared = sumDistanceSquared / points.size
        var sideLength = sqrt(3f * avgDistanceSquared)

        // Step 3: Find best angle through iterative search
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

        // Step 4: Cyclic coordinate descent
        repeat(10) {
            centerX = optimizeCenterX(centerX, centerY, sideLength, bestAngle, points)
            centerY = optimizeCenterY(centerX, centerY, sideLength, bestAngle, points)
            sideLength = optimizeSideLength(centerX, centerY, sideLength, bestAngle, points)
            bestAngle = optimizeAngle(centerX, centerY, sideLength, bestAngle, points).first
        }

        val params = SquareParams(
            centerX = centerX,
            centerY = centerY,
            sideLength = sideLength,
            angle = bestAngle
        )

        val maxDistance = evaluateMaxDistance(centerX, centerY, sideLength, bestAngle, points)
        val normalizedError = maxDistance / sideLength

        val fittedStroke = createSquareStroke(params, stroke.paint, stroke.pointsForDrawing.size)
        return FitResult(params, normalizedError, fittedStroke)
    }

    private fun optimizeCenterX(
        initialCenterX: Float,
        centerY: Float,
        sideLength: Float,
        angle: Float,
        points: List<PointF>
    ): Float {
        val goldenRatio = 0.618033988749895f
        val tolerance = 0.1f
        val searchRange = sideLength * 0.5f

        var a = initialCenterX - searchRange
        var b = initialCenterX + searchRange
        var c = b - (b - a) * goldenRatio
        var d = a + (b - a) * goldenRatio

        var fc = evaluateFit(c, centerY, sideLength, angle, points)
        var fd = evaluateFit(d, centerY, sideLength, angle, points)

        while (abs(b - a) > tolerance) {
            if (fc < fd) {
                b = d
                d = c
                fd = fc
                c = b - (b - a) * goldenRatio
                fc = evaluateFit(c, centerY, sideLength, angle, points)
            } else {
                a = c
                c = d
                fc = fd
                d = a + (b - a) * goldenRatio
                fd = evaluateFit(d, centerY, sideLength, angle, points)
            }
        }

        return (a + b) / 2f
    }

    private fun optimizeCenterY(
        centerX: Float,
        initialCenterY: Float,
        sideLength: Float,
        angle: Float,
        points: List<PointF>
    ): Float {
        val goldenRatio = 0.618033988749895f
        val tolerance = 0.1f
        val searchRange = sideLength * 0.5f

        var a = initialCenterY - searchRange
        var b = initialCenterY + searchRange
        var c = b - (b - a) * goldenRatio
        var d = a + (b - a) * goldenRatio

        var fc = evaluateFit(centerX, c, sideLength, angle, points)
        var fd = evaluateFit(centerX, d, sideLength, angle, points)

        while (abs(b - a) > tolerance) {
            if (fc < fd) {
                b = d
                d = c
                fd = fc
                c = b - (b - a) * goldenRatio
                fc = evaluateFit(centerX, c, sideLength, angle, points)
            } else {
                a = c
                c = d
                fc = fd
                d = a + (b - a) * goldenRatio
                fd = evaluateFit(centerX, d, sideLength, angle, points)
            }
        }

        return (a + b) / 2f
    }

    private fun optimizeSideLength(
        centerX: Float,
        centerY: Float,
        initialSideLength: Float,
        angle: Float,
        points: List<PointF>
    ): Float {
        val goldenRatio = 0.618033988749895f
        val tolerance = 0.1f
        val searchRange = initialSideLength * 0.5f

        var a = max(1f, initialSideLength - searchRange)
        var b = initialSideLength + searchRange
        var c = b - (b - a) * goldenRatio
        var d = a + (b - a) * goldenRatio

        var fc = evaluateFit(centerX, centerY, c, angle, points)
        var fd = evaluateFit(centerX, centerY, d, angle, points)

        while (abs(b - a) > tolerance) {
            if (fc < fd) {
                b = d
                d = c
                fd = fc
                c = b - (b - a) * goldenRatio
                fc = evaluateFit(centerX, centerY, c, angle, points)
            } else {
                a = c
                c = d
                fc = fd
                d = a + (b - a) * goldenRatio
                fd = evaluateFit(centerX, centerY, d, angle, points)
            }
        }

        return (a + b) / 2f
    }

    private fun optimizeAngle(
        centerX: Float,
        centerY: Float,
        sideLength: Float,
        startAngle: Float,
        points: List<PointF>
    ): Pair<Float, Float> {
        val goldenRatio = 0.618033988749895f
        val tolerance = 0.001f
        val searchRange = PI.toFloat() / 4f

        var a = startAngle - searchRange
        var b = startAngle + searchRange
        var c = b - (b - a) * goldenRatio
        var d = a + (b - a) * goldenRatio

        var fc = evaluateFit(centerX, centerY, sideLength, c, points)
        var fd = evaluateFit(centerX, centerY, sideLength, d, points)

        while (abs(b - a) > tolerance) {
            if (fc < fd) {
                b = d
                d = c
                fd = fc
                c = b - (b - a) * goldenRatio
                fc = evaluateFit(centerX, centerY, sideLength, c, points)
            } else {
                a = c
                c = d
                fc = fd
                d = a + (b - a) * goldenRatio
                fd = evaluateFit(centerX, centerY, sideLength, d, points)
            }
        }

        val bestAngle = (a + b) / 2f
        val bestCost = evaluateFit(centerX, centerY, sideLength, bestAngle, points)
        return Pair(bestAngle, bestCost)
    }

    private fun evaluateFit(
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
            totalDistance += minDistance * minDistance * minDistance * minDistance
        }

        return totalDistance / points.size
    }

    private fun evaluateMaxDistance(
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

        var maxDistance = 0f
        for (point in points) {
            var minDistance = Float.MAX_VALUE
            for (side in sides) {
                val dist = distanceToLineSegment(point, side.first, side.second)
                minDistance = min(minDistance, dist)
            }
            maxDistance = max(maxDistance, minDistance)
        }

        return maxDistance
    }

    private fun getSquareCorners(params: SquareParams): List<PointF> {
        val halfSide = params.sideLength / 2f
        val cos = cos(params.angle)
        val sin = sin(params.angle)

        val localCorners = listOf(
            PointF(-halfSide, -halfSide),
            PointF(halfSide, -halfSide),
            PointF(halfSide, halfSide),
            PointF(-halfSide, halfSide)
        )

        return localCorners.map { corner ->
            val rotatedX = corner.x * cos - corner.y * sin
            val rotatedY = corner.x * sin + corner.y * cos
            PointF(
                params.centerX + rotatedX,
                params.centerY + rotatedY
            )
        }
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

    private fun createSquareStroke(params: SquareParams, paint: Paint, targetPointCount: Int): Stroke {
        val stroke = Stroke(paint, 0)
        
        // Get the 4 corners of the square
        val corners = getSquareCorners(params)
        
        // Store the square geometry in analyticalPoints
        val squarePerimeterPoints = corners + corners[0] // Close the square
        val (analyticalPathPoints, analyticalTotalDistance) = Stroke.calculatePathPointsWithDistances(squarePerimeterPoints)
        stroke.analyticalPoints.addAll(analyticalPathPoints)
        
        // Generate interpolated points along the square perimeter
        val interpolatedPoints = mutableListOf<PointF>()
        val perimeter = params.sideLength * 4f
        val spacing = perimeter / (targetPointCount - 1)
        
        for (i in 0 until targetPointCount) {
            val targetDistance = i * spacing
            val point = interpolatePointOnSquarePerimeter(corners, targetDistance)
            interpolatedPoints.add(point)
        }
        
        // Create the stroke with interpolated points
        val (pathPoints, totalDistance) = Stroke.calculatePathPointsWithDistances(interpolatedPoints)
        stroke.unsmoothedPoints.addAll(pathPoints)
        stroke.pointsForDrawing.addAll(pathPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })
        stroke.originalPoints.addAll(pathPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) })
        stroke.totalDistance = totalDistance
        
        return stroke
    }
    
    private fun interpolatePointOnSquarePerimeter(corners: List<PointF>, targetDistance: Float): PointF {
        val sideLength = distance(corners[0], corners[1])
        val perimeter = sideLength * 4f
        val normalizedDistance = (targetDistance % perimeter) / perimeter
        
        val segmentIndex = (normalizedDistance * 4).toInt()
        val segmentProgress = (normalizedDistance * 4) - segmentIndex
        
        val start = corners[segmentIndex % 4]
        val end = corners[(segmentIndex + 1) % 4]
        
        val x = start.x + segmentProgress * (end.x - start.x)
        val y = start.y + segmentProgress * (end.y - start.y)
        
        return PointF(x, y)
    }

    private fun distance(p1: PointF, p2: PointF): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }
}