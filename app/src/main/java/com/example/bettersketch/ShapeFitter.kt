package com.example.bettersketch

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

class ShapeFitter {
    companion object {
        fun fit(strokeToReplace: Stroke, strokeForFitting: Stroke): ShapeFitResult? {
            if (strokeForFitting.pointsForDrawing.size < 2) return null

            val startPoint = strokeForFitting.pointsForDrawing.first().point
            val endPoint = strokeForFitting.pointsForDrawing.last().point
            val distance = sqrt((startPoint.x - endPoint.x).pow(2) + (startPoint.y - endPoint.y).pow(2))

            val bounds = strokeForFitting.getBounds()
            val maxDimension = max(bounds.width(), bounds.height())

            val fits = mutableListOf<ShapeFitResult>()

            // Optimization: if the stroke is not a closed loop, don't try to fit a closed-loop shape.
            // We determine this by checking if the distance between the start and end points
            // is greater than 20% of the largest dimension of the stroke's bounding box.
            if (distance > maxDimension * 0.2f) {

                var bestPolyFit: ShapeFitResult.Polynomial? = null
                var minError = Float.MAX_VALUE

                for (degree in 1..4) {
                    PolynomFitter.fitPolynomial(strokeForFitting, degree)?.let {
                        val weightedError = it.normalizedError
                        if (weightedError < minError) {
                            minError = weightedError
                            bestPolyFit = ShapeFitResult.Polynomial(strokeToReplace, it)
                        }
                    }
                }
                bestPolyFit?.let { fits.add(it) }

                PolyLineFitter.fit(strokeForFitting)?.let {
                    fits.add(ShapeFitResult.PolyLine(strokeToReplace, it))
                }

            } else {
                // If the stroke is likely a closed shape, try to fit a square and a circle.
                SquareFitter.fitSquare(strokeForFitting)?.let {
                    fits.add(ShapeFitResult.Square(strokeToReplace, it))
                }
                CircleFitter.fitCircle(strokeForFitting)?.let {
                    fits.add(ShapeFitResult.Circle(strokeToReplace, it))
                }
            }

            return fits.minByOrNull {
                when (it) {
                    is ShapeFitResult.Square -> it.fitResult.normalizedError
                    is ShapeFitResult.Circle -> it.fitResult.normalizedError
                    is ShapeFitResult.Polynomial -> {
                        if (it.fitResult.degree == 1) {
                            it.fitResult.normalizedError / 2f // Preference for lines...
                        } else {
                            it.fitResult.normalizedError
                        }
                    }
                    is ShapeFitResult.PolyLine -> it.fitResult.error
                }
            }
        }
    }
}