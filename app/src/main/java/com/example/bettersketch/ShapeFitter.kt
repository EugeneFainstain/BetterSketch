package com.example.bettersketch

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

class ShapeFitter {
    companion object {
        fun fit(strokeToReplace: Stroke, strokeForFitting: Stroke): ShapeFitResult? {
            if (strokeForFitting.points.size < 2) return null

            val startPoint = strokeForFitting.points.first().point
            val endPoint = strokeForFitting.points.last().point
            val distance = sqrt((startPoint.x - endPoint.x).pow(2) + (startPoint.y - endPoint.y).pow(2))

            val bounds = strokeForFitting.getBounds()
            val maxDimension = max(bounds.width(), bounds.height())

            // Optimization: if the stroke is not a closed loop, don't try to fit a shape.
            // We determine this by checking if the distance between the start and end points
            // is greater than 20% of the largest dimension of the stroke's bounding box.
            if (distance > maxDimension * 0.2f) {
                return null
            }

            val squareFit = SquareFitter.fitSquare(strokeForFitting, qualityThreshold = 0.2f)
            val circleFit = CircleFitter.fitCircle(strokeForFitting, qualityThreshold = 0.2f)

            return when {
                squareFit != null && circleFit != null -> {
                    if (squareFit.normalizedError < circleFit.normalizedError) {
                        ShapeFitResult.Square(strokeToReplace, squareFit)
                    } else {
                        ShapeFitResult.Circle(strokeToReplace, circleFit)
                    }
                }
                squareFit != null -> ShapeFitResult.Square(strokeToReplace, squareFit)
                circleFit != null -> ShapeFitResult.Circle(strokeToReplace, circleFit)
                else -> null
            }
        }
    }
}