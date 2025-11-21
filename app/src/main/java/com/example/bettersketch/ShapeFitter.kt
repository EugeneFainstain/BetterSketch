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

            // Always try to fit a polynomial
            PolynomFitter.fitPolynomial(strokeForFitting, 3)?.let {
                fits.add(ShapeFitResult.Polynomial(strokeToReplace, it))
            }

            // If the stroke is likely an open shape, only try to fit a line.
            if (distance > maxDimension * 0.1f) {
                LineFitter.fitLine(strokeForFitting, qualityThreshold = 0.1f)?.let {
                    fits.add(ShapeFitResult.Line(strokeToReplace, it))
                }
            } else {
                // If the stroke is likely a closed shape, try to fit a square and a circle.
                SquareFitter.fitSquare(strokeForFitting, qualityThreshold = 0.2f)?.let {
                    fits.add(ShapeFitResult.Square(strokeToReplace, it))
                }
                CircleFitter.fitCircle(strokeForFitting, qualityThreshold = 0.2f)?.let {
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