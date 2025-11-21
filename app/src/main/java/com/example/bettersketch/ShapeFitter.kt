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

            val fits = mutableListOf<ShapeFitResult>()

            // Optimization: if the stroke is not a closed loop, don't try to fit a closed-loop shape.
            // We determine this by checking if the distance between the start and end points
            // is greater than 20% of the largest dimension of the stroke's bounding box.
            if (distance > maxDimension * 0.2f) {
                LineFitter.fitLine(strokeForFitting, qualityThreshold = 99f)?.let {
                    fits.add(ShapeFitResult.Line(strokeToReplace, it))
                }

                var bestPolyFit: ShapeFitResult.Polynomial? = null
                var minError = Float.MAX_VALUE

                for (degree in 2..15) {
                    PolynomFitter.fitPolynomial(strokeForFitting, degree)?.let {
                        val weightedError = it.normalizedError
                        if (weightedError < minError) {
                            minError = weightedError
                            bestPolyFit = ShapeFitResult.Polynomial(strokeToReplace, it)
                        }
                    }
                }
                bestPolyFit?.let { fits.add(it) }

            } else {
                // If the stroke is likely a closed shape, try to fit a square and a circle.
                SquareFitter.fitSquare(strokeForFitting, qualityThreshold = 99f)?.let {
                    fits.add(ShapeFitResult.Square(strokeToReplace, it))
                }
                CircleFitter.fitCircle(strokeForFitting, qualityThreshold = 99f)?.let {
                    fits.add(ShapeFitResult.Circle(strokeToReplace, it))
                }
            }

            return fits.minByOrNull {
                when (it) {
                    is ShapeFitResult.Square -> it.fitResult.normalizedError
                    is ShapeFitResult.Circle -> it.fitResult.normalizedError
                    is ShapeFitResult.Line -> it.fitResult.normalizedError
                    is ShapeFitResult.Polynomial -> it.fitResult.normalizedError
                }
            }
        }
    }
}