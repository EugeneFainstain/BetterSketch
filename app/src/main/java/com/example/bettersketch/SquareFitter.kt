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
        val normalizedError: Float,
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
    fun fitSquare(
        stroke: Stroke,
        qualityThreshold: Float = 0.15f,
        maxIterations: Int = 100,
        learningRate: Float = 1.0f
    ): FitResult? {
        if (stroke.points.isEmpty()) return null
        
        // Initialize parameters from bounding box
        val bounds = stroke.getBounds()
        var params = SquareParams(
            centerX = bounds.centerX(),
            centerY = bounds.centerY(),
            sideLength = max(bounds.width(), bounds.height()),
            angle = 0f
        )
        
        // Gradient descent optimization
        var currentLearningRate = learningRate
        var prevCost = Float.MAX_VALUE
        
        for (iteration in 0 until maxIterations) {
            val cost = calculateCost(stroke, params)
            
            // Check for convergence
            if (abs(prevCost - cost) < 0.001f) {
                break
            }
            
            // Calculate gradients using finite differences
            val epsilon = 0.1f
            val gradCenterX = (calculateCost(stroke, params.copy(centerX = params.centerX + epsilon)) - cost) / epsilon
            val gradCenterY = (calculateCost(stroke, params.copy(centerY = params.centerY + epsilon)) - cost) / epsilon
            val gradSideLength = (calculateCost(stroke, params.copy(sideLength = params.sideLength + epsilon)) - cost) / epsilon
            val gradAngle = (calculateCost(stroke, params.copy(angle = params.angle + 0.01f)) - cost) / 0.01f
            
            // Update parameters
            params = SquareParams(
                centerX = params.centerX - currentLearningRate * gradCenterX,
                centerY = params.centerY - currentLearningRate * gradCenterY,
                sideLength = max(1f, params.sideLength - currentLearningRate * gradSideLength),
                angle = params.angle - currentLearningRate * 0.1f * gradAngle // Smaller step for angle
            )
            
            // Adaptive learning rate
            if (cost < prevCost) {
                currentLearningRate *= 1.05f
            } else {
                currentLearningRate *= 0.5f
            }
            
            prevCost = cost
        }
        
        // Calculate final quality metric
        val avgDistance = calculateCost(stroke, params)
        val normalizedError = avgDistance / params.sideLength
        
        if (normalizedError > qualityThreshold) {
            return null // Fit quality too poor
        }
        
        // Create the fitted square stroke
        val fittedStroke = createSquareStroke(params, stroke.paint)
        
        return FitResult(params, normalizedError, fittedStroke)
    }
    
    /**
     * Calculates the cost function: average minimum distance from points to square sides
     */
    private fun calculateCost(stroke: Stroke, params: SquareParams): Float {
        val corners = getSquareCorners(params)
        val sides = listOf(
            Pair(corners[0], corners[1]),
            Pair(corners[1], corners[2]),
            Pair(corners[2], corners[3]),
            Pair(corners[3], corners[0])
        )
        
        var totalDistance = 0f
        
        for (pathPoint in stroke.points) {
            val point = pathPoint.point
            var minDistance = Float.MAX_VALUE
            
            for (side in sides) {
                val dist = distanceToLineSegment(point, side.first, side.second)
                minDistance = min(minDistance, dist)
            }
            
            totalDistance += minDistance
        }
        
        return totalDistance / stroke.points.size
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