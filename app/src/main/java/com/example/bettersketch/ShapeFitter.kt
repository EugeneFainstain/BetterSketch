package com.example.bettersketch

class ShapeFitter {
    companion object {
        fun fit(strokeToReplace: Stroke, strokeForFitting: Stroke): ShapeFitResult? {
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