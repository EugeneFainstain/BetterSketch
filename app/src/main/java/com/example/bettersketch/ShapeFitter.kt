package com.example.bettersketch

import android.graphics.PointF
import com.example.bettersketch.GeometryUtils.distance
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

class ShapeFitter {
    
    companion object {
        /**
         * Fits the best analytical shape (Square, Circle, or Polynomial) to the stroke
         */
        fun shapeFit(strokeToReplace: Stroke, strokeForFitting: Stroke): ShapeFitResult? {
            return fit(strokeToReplace, strokeForFitting, polylineFittingMode = false) as? ShapeFitResult
        }
        
        /**
         * Fits a polyline approximation to the stroke
         */
        fun polylineFit(strokeToReplace: Stroke, strokeForFitting: Stroke): PolyLineFitter.FitResult? {
            val result = fit(strokeToReplace, strokeForFitting, polylineFittingMode = true) as? PolyLineFitter.FitResult
            
            // Populate originalPoints and regenerate for the fitted polyline stroke
            result?.let {
                val fittedStroke = it.fittedStroke
                
                // Copy original points from the stroke being replaced
                fittedStroke.originalPoints.clear()
                fittedStroke.originalPoints.addAll(
                    strokeToReplace.unsmoothedPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) }
                )
                
                // Regenerate the stroke from its polyline points
                if (fittedStroke.needsToRegenerate) {
                    fittedStroke.regenerateUnsmoothedPointsFromAnalytical()
                }
            }
            
            return result
        }
        
        /**
         * Private function that performs the actual fitting
         * @param polylineFittingMode if true, returns polyline fit only; 
         *                            if false, returns best shape fit (Square/Circle/Polynomial only)
         */
        private fun fit(
            strokeToReplace: Stroke, 
            strokeForFitting: Stroke,
            polylineFittingMode: Boolean
        ): Any? {
            if (strokeForFitting.pointsForDrawing.size < 2) {
                return null
            }

            // If in polyline fitting mode, compute and return only the polyline
            if (polylineFittingMode) {
                return PolyLineFitter.fit(strokeForFitting)
            }

            // Otherwise, compute the best shape fit (Square, Circle, or Polynomial ONLY)
            val startPoint = strokeForFitting.pointsForDrawing.first().point
            val endPoint = strokeForFitting.pointsForDrawing.last().point
            val dist = distance(startPoint, endPoint)

            val bounds = strokeForFitting.getBounds()
            val maxDimension = max(bounds.width(), bounds.height())

            val fits = mutableListOf<ShapeFitResult>()

            // Optimization: if the stroke is not a closed loop, don't try to fit a closed-loop shape.
            // We determine this by checking if the distance between the start and end points
            // is greater than 20% of the largest dimension of the stroke's bounding box.
            if (dist > maxDimension * 0.2f) {
                // Open stroke: Try polynomial fits only
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

            } else {
                // Closed stroke: Try to fit square and circle
                SquareFitter.fitSquare(strokeForFitting)?.let {
                    fits.add(ShapeFitResult.Square(strokeToReplace, it))
                }
                CircleFitter.fitCircle(strokeForFitting)?.let {
                    fits.add(ShapeFitResult.Circle(strokeToReplace, it))
                }
            }

            val bestFit = fits.minByOrNull {
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
                }
            }

            // Regenerate the best fitted stroke's unsmoothed points from analytical
            bestFit?.let { result ->
                val fittedStroke = when (result) {
                    is ShapeFitResult.Square -> result.fitResult.fittedStroke
                    is ShapeFitResult.Circle -> result.fitResult.fittedStroke
                    is ShapeFitResult.Polynomial -> result.fitResult.fittedStroke
                }

                // Copy original points from the original stroke
                fittedStroke.originalPoints.clear()
                fittedStroke.originalPoints.addAll(
                    strokeToReplace.unsmoothedPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) }
                )

                if (fittedStroke.needsToRegenerate)
                    fittedStroke.regenerateUnsmoothedPointsFromAnalytical()
            }

            return bestFit
        }
    }
}