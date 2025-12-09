package com.example.bettersketch

import android.graphics.PointF
import kotlin.math.*

/**
 * Bezier curve fitter based on Philip J. Schneider's algorithm from Graphics Gems.
 * Fits cubic Bezier splines to a sequence of digitized points.
 */
class BezierFitter {

    data class FitResult(
        val anchorPoints: List<PointF>,           // Bezier anchor points (on-curve)
        val controlPoints1: List<PointF>,         // "Before/Outgoing" control points (one per anchor)
        val controlPoints2: List<PointF>,         // "After/Incoming" control points (one per anchor)
        val anchorIndices: List<Int>,             // Closest original point indices to anchors
        val error: Float,                         // Average fitting error
        val normalizedError: Float                // Error normalized by stroke scale
    )

    companion object {
        private const val MAX_ITERATIONS = 4      // Max iterations for Newton-Raphson
        private const val EPSILON = 1.0e-6f       // Convergence threshold

        /**
         * Fit cubic Bezier curves to the stroke points
         * @param stroke The stroke to fit
         * @param errorTolerance Maximum allowed error (typically stroke width)
         */
        fun fit(stroke: Stroke, errorTolerance: Float = 4.0f): FitResult? {
            val points = stroke.unsmoothedPoints.map { it.point }
            if (points.size < 2) return null

            // Calculate stroke scale for normalization
            val bounds = stroke.getBounds()
            val scale = sqrt(bounds.width().pow(2) + bounds.height().pow(2))
            if (scale <= 0f) return null

            val adjustedError = maxOf(errorTolerance, scale * 0.01f)

            // Fit the curve
            val bezierSegments = fitCurve(points, adjustedError)
            if (bezierSegments.isEmpty()) return null

            // Extract anchors and control points
            val anchors = mutableListOf<PointF>()
            val controlPoints1 = mutableListOf<PointF>()  // Outgoing controls
            val controlPoints2 = mutableListOf<PointF>()  // Incoming controls

            // Process segments to extract anchors and controls
            // For N anchors, we have N-1 segments
            // Each anchor gets two control points: one outgoing (controlPoints1) and one incoming (controlPoints2)

            bezierSegments.forEachIndexed { segIndex, segment ->
                if (segIndex == 0) {
                    // First segment: add first anchor
                    anchors.add(segment.p0)
                    controlPoints1.add(segment.p1)  // Outgoing from first anchor
                    controlPoints2.add(segment.p0)  // Dummy incoming (same as anchor)
                }

                // Add the end anchor of this segment
                anchors.add(segment.p3)
                controlPoints2.add(segment.p2)  // Incoming to this anchor

                // Add outgoing control for this anchor (if not the last segment)
                if (segIndex < bezierSegments.size - 1) {
                    controlPoints1.add(bezierSegments[segIndex + 1].p1)
                } else {
                    // Last anchor: add dummy outgoing control (same as anchor)
                    controlPoints1.add(segment.p3)
                }
            }

            // Find closest original point indices for each anchor
            val anchorIndices = anchors.map { anchor ->
                findClosestPointIndex(anchor, points)
            }

            // Calculate average error
            val totalError = calculateAverageError(points, bezierSegments)
            val normalizedError = totalError / scale

            return FitResult(
                anchorPoints = anchors,
                controlPoints1 = controlPoints1,
                controlPoints2 = controlPoints2,
                anchorIndices = anchorIndices,
                error = totalError,
                normalizedError = normalizedError
            )
        }

        /**
         * Represents a cubic Bezier segment with 4 control points
         */
        private data class BezierSegment(
            val p0: PointF,  // Start anchor
            val p1: PointF,  // First control point
            val p2: PointF,  // Second control point
            val p3: PointF   // End anchor
        )

        /**
         * Main fitting function - recursively fits Bezier curves
         */
        private fun fitCurve(
            points: List<PointF>,
            error: Float
        ): List<BezierSegment> {
            if (points.size < 2) return emptyList()
            if (points.size == 2) {
                // Single line segment - CREATE COPIES!
                val p0 = PointF(points[0].x, points[0].y)
                val p3 = PointF(points[1].x, points[1].y)
                val dist = distance(p0, p3) / 3.0f
                val p1 = PointF(p0.x + dist, p0.y)
                val p2 = PointF(p3.x - dist, p3.y)
                return listOf(BezierSegment(p0, p1, p2, p3))
            }

            // Estimate tangent directions at endpoints
            val tHat1 = computeLeftTangent(points, 0)
            val tHat2 = computeRightTangent(points, points.size - 1)

            return fitCubic(points, tHat1, tHat2, error)
        }

        /**
         * Fit a Bezier curve to a subset of digitized points
         */
        private fun fitCubic(
            points: List<PointF>,
            tHat1: PointF,
            tHat2: PointF,
            error: Float
        ): List<BezierSegment> {
            if (points.size < 2) return emptyList()

            // Use heuristic if region only has two points - CREATE COPIES!
            if (points.size == 2) {
                val p0 = PointF(points[0].x, points[0].y)
                val p3 = PointF(points[1].x, points[1].y)
                val dist = distance(p0, p3) / 3.0f
                val p1 = PointF(p0.x + tHat1.x * dist, p0.y + tHat1.y * dist)
                val p2 = PointF(p3.x + tHat2.x * dist, p3.y + tHat2.y * dist)
                return listOf(BezierSegment(p0, p1, p2, p3))
            }

            // Parameterize points
            val u = chordLengthParameterize(points)

            // Generate bezier curve
            var bezier = generateBezier(points, u, tHat1, tHat2)
            var maxError = computeMaxError(points, bezier, u)
            var splitPoint = maxError.second

            if (maxError.first < error) {
                return listOf(bezier)
            }

            // If error not too large, try reparameterization
            if (maxError.first < error * error) {
                for (i in 0 until MAX_ITERATIONS) {
                    val uPrime = reparameterize(points, u, bezier)
                    bezier = generateBezier(points, uPrime, tHat1, tHat2)
                    maxError = computeMaxError(points, bezier, uPrime)
                    splitPoint = maxError.second

                    if (maxError.first < error) {
                        return listOf(bezier)
                    }
                    u.clear()
                    u.addAll(uPrime)
                }
            }

            // Fitting failed -- split at max error point and recursively fit
            val tHatCenter = computeCenterTangent(points, splitPoint)
            val leftSegment = points.subList(0, splitPoint + 1)
            val rightSegment = points.subList(splitPoint, points.size)

            val leftBeziers = fitCubic(leftSegment, tHat1, tHatCenter, error)
            val rightBeziers = fitCubic(rightSegment, negate(tHatCenter), tHat2, error)

            return leftBeziers + rightBeziers
        }

        /**
         * Generate a Bezier curve that approximates the points
         */
        private fun generateBezier(
            points: List<PointF>,
            u: List<Float>,
            tHat1: PointF,
            tHat2: PointF
        ): BezierSegment {
            // Create COPIES of the first and last points, not references!
            val p0 = PointF(points.first().x, points.first().y)
            val p3 = PointF(points.last().x, points.last().y)

            // Compute the A's
            val A = Array(points.size) { i ->
                val ui = u[i]
                val b1 = bezierBasis(1, ui)
                val b2 = bezierBasis(2, ui)
                Pair(
                    PointF(tHat1.x * b1, tHat1.y * b1),
                    PointF(tHat2.x * b2, tHat2.y * b2)
                )
            }

            // Create the C and X matrices
            var C00 = 0.0f
            var C01 = 0.0f
            var C11 = 0.0f
            var X0 = 0.0f
            var X1 = 0.0f

            for (i in points.indices) {
                val ui = u[i]
                val a0 = A[i].first
                val a1 = A[i].second

                C00 += dot(a0, a0)
                C01 += dot(a0, a1)
                C11 += dot(a1, a1)

                val tmp = subtract(
                    points[i],
                    add(
                        scale(p0, bezierBasis(0, ui)),
                        add(
                            scale(p0, bezierBasis(1, ui)),
                            add(
                                scale(p3, bezierBasis(2, ui)),
                                scale(p3, bezierBasis(3, ui))
                            )
                        )
                    )
                )

                X0 += dot(a0, tmp)
                X1 += dot(a1, tmp)
            }

            // Compute the determinants of C and X
            val det_C0_C1 = C00 * C11 - C01 * C01
            val det_C0_X = C00 * X1 - C01 * X0
            val det_X_C1 = X0 * C11 - X1 * C01

            // Derive alpha values
            val alphaL = if (abs(det_C0_C1) < EPSILON) 0.0f else det_X_C1 / det_C0_C1
            val alphaR = if (abs(det_C0_C1) < EPSILON) 0.0f else det_C0_X / det_C0_C1

            // If alpha negative, use the Wu/Barsky heuristic
            val segLength = distance(p0, p3)
            val epsilon = 1.0e-6f * segLength

            val finalAlphaL = if (alphaL < epsilon) segLength / 3.0f else alphaL
            val finalAlphaR = if (alphaR < epsilon) segLength / 3.0f else alphaR

            // Control points
            val p1 = add(p0, scale(tHat1, finalAlphaL))
            val p2 = add(p3, scale(tHat2, finalAlphaR))

            return BezierSegment(p0, p1, p2, p3)
        }

        /**
         * Compute maximum squared distance of points to fitted Bezier curve
         */
        private fun computeMaxError(
            points: List<PointF>,
            bezier: BezierSegment,
            u: List<Float>
        ): Pair<Float, Int> {
            var maxDist = 0.0f
            var splitPoint = points.size / 2

            for (i in 1 until points.size - 1) {
                val p = evaluateBezier(bezier, u[i])
                val dist = distanceSquared(points[i], p)
                if (dist > maxDist) {
                    maxDist = dist
                    splitPoint = i
                }
            }

            return Pair(maxDist, splitPoint)
        }

        /**
         * Calculate average error across all points
         */
        private fun calculateAverageError(
            points: List<PointF>,
            segments: List<BezierSegment>
        ): Float {
            if (points.isEmpty() || segments.isEmpty()) return 0f

            var totalError = 0f
            var pointsProcessed = 0

            // Estimate how many points per segment
            val pointsPerSegment = maxOf(2, points.size / segments.size)

            segments.forEachIndexed { segIndex, segment ->
                val startIdx = segIndex * pointsPerSegment
                val endIdx = minOf(startIdx + pointsPerSegment, points.size)

                for (i in startIdx until endIdx) {
                    // Find closest point on bezier curve
                    val t = i.toFloat() / (points.size - 1)
                    val bezierPoint = evaluateBezier(segment, t)
                    val dist = distance(points[i], bezierPoint)
                    totalError += dist
                    pointsProcessed++
                }
            }

            return if (pointsProcessed > 0) totalError / pointsProcessed else 0f
        }

        /**
         * Chord-length parameterization of points
         */
        private fun chordLengthParameterize(points: List<PointF>): MutableList<Float> {
            val u = MutableList(points.size) { 0.0f }
            u[0] = 0.0f

            for (i in 1 until points.size) {
                u[i] = u[i - 1] + distance(points[i], points[i - 1])
            }

            val total = u.last()
            if (total > 0f) {
                for (i in 1 until u.size) {
                    u[i] /= total
                }
            }

            return u
        }

        /**
         * Reparameterize points using Newton-Raphson
         */
        private fun reparameterize(
            points: List<PointF>,
            u: List<Float>,
            bezier: BezierSegment
        ): List<Float> {
            return points.indices.map { i ->
                newtonRaphsonRootFind(bezier, points[i], u[i])
            }
        }

        /**
         * Newton-Raphson iteration to find better parameter value
         */
        private fun newtonRaphsonRootFind(
            bezier: BezierSegment,
            point: PointF,
            u: Float
        ): Float {
            // Compute Q(u)
            val q = evaluateBezier(bezier, u)

            // Compute Q'(u) and Q''(u)
            val q1 = evaluateBezierDerivative(bezier, u, 1)
            val q2 = evaluateBezierDerivative(bezier, u, 2)

            // Compute f(u)/f'(u)
            val qMinusP = subtract(q, point)
            val numerator = dot(qMinusP, q1)
            val denominator = dot(q1, q1) + dot(qMinusP, q2)

            return if (abs(denominator) < EPSILON) {
                u
            } else {
                u - numerator / denominator
            }
        }

        /**
         * Evaluate Bezier curve at parameter t
         */
        private fun evaluateBezier(bezier: BezierSegment, t: Float): PointF {
            val t2 = t * t
            val t3 = t2 * t
            val mt = 1.0f - t
            val mt2 = mt * mt
            val mt3 = mt2 * mt

            return PointF(
                bezier.p0.x * mt3 + 3 * bezier.p1.x * mt2 * t + 3 * bezier.p2.x * mt * t2 + bezier.p3.x * t3,
                bezier.p0.y * mt3 + 3 * bezier.p1.y * mt2 * t + 3 * bezier.p2.y * mt * t2 + bezier.p3.y * t3
            )
        }

        /**
         * Evaluate Bezier derivative at parameter t
         */
        private fun evaluateBezierDerivative(bezier: BezierSegment, t: Float, derivative: Int): PointF {
            return when (derivative) {
                1 -> {
                    val mt = 1.0f - t
                    val mt2 = mt * mt
                    val t2 = t * t
                    PointF(
                        3 * mt2 * (bezier.p1.x - bezier.p0.x) + 6 * mt * t * (bezier.p2.x - bezier.p1.x) + 3 * t2 * (bezier.p3.x - bezier.p2.x),
                        3 * mt2 * (bezier.p1.y - bezier.p0.y) + 6 * mt * t * (bezier.p2.y - bezier.p1.y) + 3 * t2 * (bezier.p3.y - bezier.p2.y)
                    )
                }
                2 -> {
                    val mt = 1.0f - t
                    PointF(
                        6 * mt * (bezier.p2.x - 2 * bezier.p1.x + bezier.p0.x) + 6 * t * (bezier.p3.x - 2 * bezier.p2.x + bezier.p1.x),
                        6 * mt * (bezier.p2.y - 2 * bezier.p1.y + bezier.p0.y) + 6 * t * (bezier.p3.y - 2 * bezier.p2.y + bezier.p1.y)
                    )
                }
                else -> PointF(0f, 0f)
            }
        }

        /**
         * Bernstein basis function for cubic Bezier
         */
        private fun bezierBasis(i: Int, t: Float): Float {
            val mt = 1.0f - t
            return when (i) {
                0 -> mt * mt * mt
                1 -> 3 * mt * mt * t
                2 -> 3 * mt * t * t
                3 -> t * t * t
                else -> 0f
            }
        }

        /**
         * Compute left tangent at point
         */
        private fun computeLeftTangent(points: List<PointF>, index: Int): PointF {
            val tangent = subtract(points[index + 1], points[index])
            return normalize(tangent)
        }

        /**
         * Compute right tangent at point
         */
        private fun computeRightTangent(points: List<PointF>, index: Int): PointF {
            val tangent = subtract(points[index - 1], points[index])
            return normalize(tangent)
        }

        /**
         * Compute center tangent at point
         */
        private fun computeCenterTangent(points: List<PointF>, index: Int): PointF {
            val v1 = subtract(points[index - 1], points[index])
            val v2 = subtract(points[index], points[index + 1])
            val tangent = PointF((v1.x + v2.x) / 2.0f, (v1.y + v2.y) / 2.0f)
            return normalize(tangent)
        }

        /**
         * Find closest point index in original points to a target point
         */
        private fun findClosestPointIndex(target: PointF, points: List<PointF>): Int {
            var minDist = Float.MAX_VALUE
            var closestIndex = 0

            points.forEachIndexed { index, point ->
                val dist = distanceSquared(target, point)
                if (dist < minDist) {
                    minDist = dist
                    closestIndex = index
                }
            }

            return closestIndex
        }

        // Vector math utilities
        private fun distance(p1: PointF, p2: PointF): Float {
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            return sqrt(dx * dx + dy * dy)
        }

        private fun distanceSquared(p1: PointF, p2: PointF): Float {
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            return dx * dx + dy * dy
        }

        private fun add(p1: PointF, p2: PointF) = PointF(p1.x + p2.x, p1.y + p2.y)

        private fun subtract(p1: PointF, p2: PointF) = PointF(p1.x - p2.x, p1.y - p2.y)

        private fun scale(p: PointF, s: Float) = PointF(p.x * s, p.y * s)

        private fun dot(p1: PointF, p2: PointF) = p1.x * p2.x + p1.y * p2.y

        private fun negate(p: PointF) = PointF(-p.x, -p.y)

        private fun normalize(p: PointF): PointF {
            val len = sqrt(p.x * p.x + p.y * p.y)
            return if (len > EPSILON) PointF(p.x / len, p.y / len) else PointF(0f, 0f)
        }
    }
}