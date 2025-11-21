package com.example.bettersketch

import android.graphics.Paint
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

object PolynomFitter {

    data class PolynomialParams(
        val coeffsX: DoubleArray,
        val coeffsY: DoubleArray
    )

    data class FitResult(
        val params: PolynomialParams,
        val normalizedError: Float,
        val fittedStroke: Stroke,
        val degree: Int
    )

    fun fitPolynomial(stroke: Stroke, degree: Int): FitResult? {
        val points = stroke.points.map { it.point }
        if (points.size <= degree) return null

        val t = DoubleArray(points.size) { it.toDouble() }
        val x = DoubleArray(points.size) { points[it].x.toDouble() }
        val y = DoubleArray(points.size) { points[it].y.toDouble() }

        val coeffsX = solveLeastSquares(t, x, degree) ?: return null
        val coeffsY = solveLeastSquares(t, y, degree) ?: return null

        val params = PolynomialParams(coeffsX, coeffsY)

        // Create the fitted stroke and calculate error
        val numPointsFitted = 100
        val fittedPoints = mutableListOf<PointF>()
        for (i in 0..numPointsFitted) {
            val tValue = t.last() * (i.toDouble() / numPointsFitted)
            val xVal = evaluatePolynomial(coeffsX, tValue)
            val yVal = evaluatePolynomial(coeffsY, tValue)
            fittedPoints.add(PointF(xVal.toFloat(), yVal.toFloat()))
        }

        val (pathPoints, totalDistance) = Stroke.calculatePathPointsWithDistances(fittedPoints)
        val fittedStroke = Stroke(pathPoints, Paint(stroke.paint), totalDistance, 0)

        // Error calculation (RMSE)
        var sumErrorSq = 0.0
        for (i in points.indices) {
            val fittedX = evaluatePolynomial(coeffsX, t[i])
            val fittedY = evaluatePolynomial(coeffsY, t[i])
            sumErrorSq += (points[i].x - fittedX).pow(2) + (points[i].y - fittedY).pow(2)
        }
        val rmse = sqrt(sumErrorSq / points.size)

        // Normalize error by the diagonal of the bounding box for scale invariance
        val bounds = stroke.getBounds()
        val diagonal = sqrt(bounds.width().pow(2) + bounds.height().pow(2))
        val normalizedError = if (diagonal > 0) (rmse / diagonal).toFloat() else 0f

//        return FitResult(params, normalizedError * degree, fittedStroke, degree)
        return FitResult(params, normalizedError * degree * sqrt(degree.toFloat()), fittedStroke, degree)
//        return FitResult(params, normalizedError * degree * degree, fittedStroke, degree)
    }

    private fun evaluatePolynomial(coeffs: DoubleArray, t: Double): Double {
        var result = 0.0
        for (i in coeffs.indices) {
            result += coeffs[i] * t.pow(i)
        }
        return result
    }

    private fun solveLeastSquares(t: DoubleArray, coords: DoubleArray, degree: Int): DoubleArray? {
        val n = degree + 1
        val A = Array(n) { DoubleArray(n) }
        val b = DoubleArray(n)

        for (i in 0 until n) {
            for (j in 0 until n) {
                A[i][j] = t.sumOf { it.pow(i + j) }
            }
            b[i] = t.zip(coords).sumOf { it.first.pow(i) * it.second }
        }

        return solveLinearSystem(A, b)
    }

    private fun solveLinearSystem(A: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val augMatrix = Array(n) { DoubleArray(n + 1) }
        for (i in 0 until n) {
            for (j in 0 until n) {
                augMatrix[i][j] = A[i][j]
            }
            augMatrix[i][n] = b[i]
        }

        // Gaussian elimination
        for (i in 0 until n) {
            // Find pivot
            var maxRow = i
            for (k in i + 1 until n) {
                if (abs(augMatrix[k][i]) > abs(augMatrix[maxRow][i])) {
                    maxRow = k
                }
            }
            val temp = augMatrix[i]
            augMatrix[i] = augMatrix[maxRow]
            augMatrix[maxRow] = temp

            // Check for singular matrix
            if (abs(augMatrix[i][i]) < 1e-10) return null

            // Make pivot 1
            for (k in i + 1..n) {
                augMatrix[i][k] /= augMatrix[i][i]
            }
            augMatrix[i][i] = 1.0


            // Eliminate other rows
            for (k in 0 until n) {
                if (k != i) {
                    val factor = augMatrix[k][i]
                    for (j in i..n) {
                        augMatrix[k][j] -= factor * augMatrix[i][j]
                    }
                }
            }
        }

        // Back substitution
        val x = DoubleArray(n)
        for (i in 0 until n) {
            x[i] = augMatrix[i][n]
        }
        return x
    }
}
