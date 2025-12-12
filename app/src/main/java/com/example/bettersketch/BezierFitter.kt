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
         * Sample a cubic Bezier curve into a polyline with the specified number of points.
         */
        private fun sampleBezierToPolyline(p0: PointF, c1: PointF, c2: PointF, p3: PointF, numPoints: Int): List<PointF> {
            if (numPoints < 2) return listOf(p0, p3)
            return (0 until numPoints).map { i ->
                val t = i.toFloat() / (numPoints - 1)
                BezierUtils.evaluateCubicBezier(p0, c1, c2, p3, t)
            }
        }

        /**
         * Find intersection between two line segments (p1-p2) and (p3-p4).
         * Returns the intersection point and the parameter t along the first segment, or null if no intersection.
         */
        private fun lineSegmentIntersection(p1: PointF, p2: PointF, p3: PointF, p4: PointF): Pair<PointF, Float>? {
            val d1x = p2.x - p1.x
            val d1y = p2.y - p1.y
            val d2x = p4.x - p3.x
            val d2y = p4.y - p3.y

            val cross = d1x * d2y - d1y * d2x
            if (abs(cross) < EPSILON) return null  // Parallel or collinear

            val dx = p3.x - p1.x
            val dy = p3.y - p1.y

            val t1 = (dx * d2y - dy * d2x) / cross
            val t2 = (dx * d1y - dy * d1x) / cross

            // Check if intersection is within both segments (excluding endpoints to avoid duplicates)
            if (t1 > EPSILON && t1 < 1f - EPSILON && t2 > EPSILON && t2 < 1f - EPSILON) {
                val ix = p1.x + t1 * d1x
                val iy = p1.y + t1 * d1y
                return Pair(PointF(ix, iy), t1)
            }
            return null
        }

        /**
         * Data class representing an intersection point with its position on both curves.
         */
        private data class IntersectionPoint(
            val point: PointF,
            val originalCurveIndex: Int,      // Index in original curve (segment start)
            val originalCurveT: Float,        // Parameter within that segment
            val bezierCurveIndex: Int,        // Index in bezier polyline (segment start)
            val bezierCurveT: Float           // Parameter within that segment
        )

        /**
         * Find all intersections between two polylines.
         * Returns intersection points sorted by position on the original curve.
         */
        private fun findPolylineIntersections(
            originalCurve: List<PointF>,
            bezierCurve: List<PointF>
        ): List<IntersectionPoint> {
            val intersections = mutableListOf<IntersectionPoint>()

            for (i in 0 until originalCurve.size - 1) {
                val o1 = originalCurve[i]
                val o2 = originalCurve[i + 1]

                for (j in 0 until bezierCurve.size - 1) {
                    val b1 = bezierCurve[j]
                    val b2 = bezierCurve[j + 1]

                    val intersection = lineSegmentIntersection(o1, o2, b1, b2)
                    if (intersection != null) {
                        val (point, tOriginal) = intersection
                        // Calculate t for bezier segment
                        val dx = b2.x - b1.x
                        val dy = b2.y - b1.y
                        val tBezier = if (abs(dx) > abs(dy)) {
                            (point.x - b1.x) / dx
                        } else {
                            (point.y - b1.y) / dy
                        }
                        intersections.add(IntersectionPoint(point, i, tOriginal, j, tBezier))
                    }
                }
            }

            // Sort by position on original curve
            return intersections.sortedWith(compareBy({ it.originalCurveIndex }, { it.originalCurveT }))
        }

        /**
         * Calculate signed area of a polygon using the Shoelace formula (Gauss's area formula).
         * The area will be positive for counter-clockwise polygons and negative for clockwise.
         */
        private fun calculateSignedPolygonArea(points: List<PointF>): Float {
            if (points.size < 3) return 0f

            var area = 0f
            for (i in points.indices) {
                val j = (i + 1) % points.size
                // Using the cross product formulation: sum of (x_i * y_{i+1} - x_{i+1} * y_i)
                area += points[i].x * points[j].y
                area -= points[j].x * points[i].y
            }
            return area / 2f
        }

        /**
         * Extract points from a polyline between two indices (inclusive of start, exclusive of end),
         * with optional fractional positions at the boundaries.
         */
        private fun extractPolylineSegment(
            polyline: List<PointF>,
            startIndex: Int,
            startT: Float,
            endIndex: Int,
            endT: Float
        ): List<PointF> {
            val result = mutableListOf<PointF>()

            // Add interpolated start point
            if (startIndex < polyline.size - 1) {
                val p1 = polyline[startIndex]
                val p2 = polyline[startIndex + 1]
                result.add(PointF(p1.x + startT * (p2.x - p1.x), p1.y + startT * (p2.y - p1.y)))
            }

            // Add intermediate points
            for (i in (startIndex + 1)..endIndex) {
                if (i < polyline.size) {
                    result.add(polyline[i])
                }
            }

            // Add interpolated end point
            if (endIndex < polyline.size - 1) {
                val p1 = polyline[endIndex]
                val p2 = polyline[endIndex + 1]
                result.add(PointF(p1.x + endT * (p2.x - p1.x), p1.y + endT * (p2.y - p1.y)))
            }

            return result
        }

        /**
         * Calculate the area-based error between the original curve segment and a Bezier approximation.
         * This finds intersections between the curves and sums the absolute areas of all closed loops.
         */
        private fun calculateAreaBasedError(
            segmentPoints: List<PointF>,
            p0: PointF,
            c1: PointF,
            c2: PointF,
            p3: PointF
        ): Float {
            if (segmentPoints.size < 2) return 0f

            // Sample Bezier curve with the same number of points as the original
            val bezierPolyline = sampleBezierToPolyline(p0, c1, c2, p3, segmentPoints.size)

            // Find all intersections (excluding endpoints which are already common)
            val intersections = findPolylineIntersections(segmentPoints, bezierPolyline)

            // Create list of "boundary points" including start, intersections, and end
            data class BoundaryPoint(
                val point: PointF,
                val originalIndex: Int,
                val originalT: Float,
                val bezierIndex: Int,
                val bezierT: Float
            )

            val boundaries = mutableListOf<BoundaryPoint>()

            // Add start point (common to both curves)
            boundaries.add(BoundaryPoint(segmentPoints.first(), 0, 0f, 0, 0f))

            // Add intersection points
            for (intersection in intersections) {
                boundaries.add(BoundaryPoint(
                    intersection.point,
                    intersection.originalCurveIndex,
                    intersection.originalCurveT,
                    intersection.bezierCurveIndex,
                    intersection.bezierCurveT
                ))
            }

            // Add end point (common to both curves)
            boundaries.add(BoundaryPoint(
                segmentPoints.last(),
                segmentPoints.size - 2,
                1f,
                bezierPolyline.size - 2,
                1f
            ))

            // Calculate total area from all loops
            var totalArea = 0f

            for (i in 0 until boundaries.size - 1) {
                val start = boundaries[i]
                val end = boundaries[i + 1]

                // Extract original curve segment (forward direction)
                val originalSegment = extractPolylineSegment(
                    segmentPoints,
                    start.originalIndex,
                    start.originalT,
                    end.originalIndex,
                    end.originalT
                )

                // Extract bezier curve segment (will be reversed to close the loop)
                val bezierSegment = extractPolylineSegment(
                    bezierPolyline,
                    start.bezierIndex,
                    start.bezierT,
                    end.bezierIndex,
                    end.bezierT
                )

                // Form closed polygon: original forward + bezier reversed
                val closedPolygon = mutableListOf<PointF>()
                closedPolygon.addAll(originalSegment)
                closedPolygon.addAll(bezierSegment.reversed().drop(1))  // drop(1) to avoid duplicate endpoint

                // Calculate and accumulate absolute area
                val loopArea = calculateSignedPolygonArea(closedPolygon)
                totalArea += abs(loopArea)
            }

            return totalArea
        }

        /**
         * Fit cubic Bezier curves with fixed anchor points at specified indices.
         * The anchor positions are taken from unsmoothedPoints at the given indices,
         * and optimal control points are computed for each segment.
         *
         * @param stroke The stroke to fit
         * @param anchorIndices Indices into unsmoothedPoints where anchors should be placed
         * @return FitResult with anchors at the specified indices and optimized control points
         */
        fun fitWithFixedAnchors(stroke: Stroke, anchorIndices: List<Int>): FitResult? {
            val points = stroke.unsmoothedPoints.map { it.point }
            if (points.size < 2 || anchorIndices.size < 2) return null

            // Validate indices
            val validIndices = anchorIndices.filter { it >= 0 && it < points.size }.distinct().sorted()
            if (validIndices.size < 2) return null

            // Calculate stroke scale for normalization
            val bounds = stroke.getBounds()
            val scale = sqrt(bounds.width().pow(2) + bounds.height().pow(2))
            if (scale <= 0f) return null

            // Extract anchor points at the specified indices
            val anchors = validIndices.map { PointF(points[it].x, points[it].y) }

            // Fit each segment between consecutive anchors
            val controlPoints1 = mutableListOf<PointF>()  // Outgoing controls
            val controlPoints2 = mutableListOf<PointF>()  // Incoming controls

            // First anchor: dummy incoming control (same as anchor)
            controlPoints2.add(PointF(anchors[0].x, anchors[0].y))

            for (i in 0 until validIndices.size - 1) {
                val startIdx = validIndices[i]
                val endIdx = validIndices[i + 1]

                // Extract the segment of points between these anchors
                val segmentPoints = points.subList(startIdx, endIdx + 1)

                // Compute tangents at the segment endpoints
                val tHat1 = computeSegmentLeftTangent(segmentPoints)
                val tHat2 = computeSegmentRightTangent(segmentPoints)

                // Fit optimal control points for this segment
                val (c1, c2) = fitSegmentControlPoints(segmentPoints, tHat1, tHat2)

                controlPoints1.add(c1)
                controlPoints2.add(c2)
            }

            // Last anchor: dummy outgoing control (same as anchor)
            controlPoints1.add(PointF(anchors.last().x, anchors.last().y))

            // Calculate average error
            val totalError = calculateAverageErrorForFixedAnchors(points, anchors, controlPoints1, controlPoints2, validIndices)
            val normalizedError = totalError / scale

            return FitResult(
                anchorPoints = anchors,
                controlPoints1 = controlPoints1,
                controlPoints2 = controlPoints2,
                anchorIndices = validIndices,
                error = totalError,
                normalizedError = normalizedError
            )
        }

        /**
         * Fit optimal control points for a single segment with fixed endpoints.
         * Uses gradient descent to optimize control point positions directly.
         *
         * The 4 degrees of freedom are:
         * - dx1, dy1: offset from p0 to control point c1
         * - dx2, dy2: offset from p3 to control point c2
         */
        private fun fitSegmentControlPoints(
            segmentPoints: List<PointF>,
            initialTHat1: PointF,
            initialTHat2: PointF
        ): Pair<PointF, PointF> {
            val p0 = segmentPoints.first()
            val p3 = segmentPoints.last()

            if (segmentPoints.size <= 2) {
                // Simple heuristic for very short segments
                val dist = distance(p0, p3) / 3.0f
                val c1 = PointF(p0.x + initialTHat1.x * dist, p0.y + initialTHat1.y * dist)
                val c2 = PointF(p3.x + initialTHat2.x * dist, p3.y + initialTHat2.y * dist)
                return Pair(c1, c2)
            }

            val segLength = distance(p0, p3)

            // Initialize with tangent-based guess (or start from zero)
            val initialDist = segLength / 3.0f
            var dx1 = initialTHat1.x * initialDist
            var dy1 = initialTHat1.y * initialDist
            var dx2 = initialTHat2.x * initialDist
            var dy2 = initialTHat2.y * initialDist

            // Gradient descent parameters
            val maxIterations = 100  // Reduced - if not converged by now, won't help much
            val initialLearningRate = segLength * 0.1f
            var learningRate = initialLearningRate
            val minLearningRate = segLength * 1e-4f  // Less aggressive minimum
            val convergenceThreshold = segLength * 0.01f  // More practical threshold
            val finiteDiffStep = segLength * 0.001f  // Slightly larger for stability

            var prevError = Float.MAX_VALUE
            var bestError = Float.MAX_VALUE
            var bestDx1 = dx1
            var bestDy1 = dy1
            var bestDx2 = dx2
            var bestDy2 = dy2
            var stagnationCount = 0  // Track iterations without significant improvement

            for (iter in 0 until maxIterations) {
                // Current control points
                val c1 = PointF(p0.x + dx1, p0.y + dy1)
                val c2 = PointF(p3.x + dx2, p3.y + dy2)

                // Calculate area-based error
                val currentError = calculateAreaBasedError(segmentPoints, p0, c1, c2, p3)

                // Track best solution
                val improvement = bestError - currentError
                if (currentError < bestError) {
                    bestError = currentError
                    bestDx1 = dx1
                    bestDy1 = dy1
                    bestDx2 = dx2
                    bestDy2 = dy2

                    // Reset stagnation if we made significant improvement (> 1% of current error)
                    if (improvement > currentError * 0.01f) {
                        stagnationCount = 0
                        // Boost learning rate when making good progress (but don't exceed initial)
                        learningRate = minOf(learningRate * 1.2f, initialLearningRate)
                    } else {
                        stagnationCount++
                    }
                } else {
                    stagnationCount++
                }

                // Check for stagnation (no significant progress for many iterations)
                if (stagnationCount > 20) {
                    //android.util.Log.d("BezierFit", "Stagnated at iter=$iter lr=${"%.4f".format(learningRate)} err=${"%.1f".format(bestError)}")
                    break
                }

                // Check convergence based on relative improvement
                val relativeChange = abs(prevError - currentError) / maxOf(currentError, 1f)
                if (relativeChange < 1e-5f && currentError < prevError) {
                    //android.util.Log.d("BezierFit", "Converged at iter=$iter lr=${"%.4f".format(learningRate)} err=${"%.1f".format(currentError)}")
                    break
                }

                // Adaptive learning rate - reduce if error increased
                if (currentError > prevError) {
                    learningRate *= 0.5f
                    if (learningRate < minLearningRate) {
                        //android.util.Log.d("BezierFit", "Learning rate exhausted at iter=$iter lr=${"%.4f".format(learningRate)} err=${"%.1f".format(bestError)}")
                        break
                    }
                    // Revert to best known solution
                    dx1 = bestDx1
                    dy1 = bestDy1
                    dx2 = bestDx2
                    dy2 = bestDy2
                    //android.util.Log.d("BezierFit", "Continued at iter=$iter lr=${"%.4f".format(learningRate)} err=${"%.1f".format(bestError)}")
                    continue
                }
                prevError = currentError

                // Calculate gradients numerically using finite differences
                val errorPlusDx1 = calculateAreaBasedError(segmentPoints, p0, PointF(p0.x + dx1 + finiteDiffStep, p0.y + dy1), c2, p3)
                val gradDx1 = (errorPlusDx1 - currentError) / finiteDiffStep

                val errorPlusDy1 = calculateAreaBasedError(segmentPoints, p0, PointF(p0.x + dx1, p0.y + dy1 + finiteDiffStep), c2, p3)
                val gradDy1 = (errorPlusDy1 - currentError) / finiteDiffStep

                val errorPlusDx2 = calculateAreaBasedError(segmentPoints, p0, c1, PointF(p3.x + dx2 + finiteDiffStep, p3.y + dy2), p3)
                val gradDx2 = (errorPlusDx2 - currentError) / finiteDiffStep

                val errorPlusDy2 = calculateAreaBasedError(segmentPoints, p0, c1, PointF(p3.x + dx2, p3.y + dy2 + finiteDiffStep), p3)
                val gradDy2 = (errorPlusDy2 - currentError) / finiteDiffStep

                // Normalize gradient for stable step size
                val gradNorm = sqrt(gradDx1 * gradDx1 + gradDy1 * gradDy1 + gradDx2 * gradDx2 + gradDy2 * gradDy2)
                if (gradNorm > EPSILON) {
                    dx1 -= learningRate * gradDx1 / gradNorm
                    dy1 -= learningRate * gradDy1 / gradNorm
                    dx2 -= learningRate * gradDx2 / gradNorm
                    dy2 -= learningRate * gradDy2 / gradNorm
                }

                // Clamp control point distances to reasonable range (tighter bound)
                val maxDist = segLength * 1.5f
                val dist1 = sqrt(dx1 * dx1 + dy1 * dy1)
                if (dist1 > maxDist) {
                    val scale = maxDist / dist1
                    dx1 *= scale
                    dy1 *= scale
                }
                val dist2 = sqrt(dx2 * dx2 + dy2 * dy2)
                if (dist2 > maxDist) {
                    val scale = maxDist / dist2
                    dx2 *= scale
                    dy2 *= scale
                }

                if (iter % 20 == 0) {
                    //android.util.Log.d("BezierFit", "iter=$iter err=${"%.1f".format(currentError)} lr=${"%.4f".format(learningRate)} gradNorm=${"%.1f".format(gradNorm)}")
                }
            }

            // Use best solution found
            val c1 = PointF(p0.x + bestDx1, p0.y + bestDy1)
            val c2 = PointF(p3.x + bestDx2, p3.y + bestDy2)

            return Pair(c1, c2)
        }

        /**
         * Compute left tangent for a segment (direction at start)
         */
        private fun computeSegmentLeftTangent(points: List<PointF>): PointF {
            if (points.size < 2) return PointF(1f, 0f)
            val tangent = subtract(points[1], points[0])
            return normalize(tangent)
        }

        /**
         * Compute right tangent for a segment (direction at end, pointing inward)
         */
        private fun computeSegmentRightTangent(points: List<PointF>): PointF {
            if (points.size < 2) return PointF(-1f, 0f)
            val tangent = subtract(points[points.size - 2], points.last())
            return normalize(tangent)
        }

        /**
         * Calculate average error for fixed anchor fitting
         */
        private fun calculateAverageErrorForFixedAnchors(
            allPoints: List<PointF>,
            anchors: List<PointF>,
            controlPoints1: List<PointF>,
            controlPoints2: List<PointF>,
            anchorIndices: List<Int>
        ): Float {
            if (anchors.size < 2) return 0f

            var totalError = 0f
            var pointCount = 0

            for (segIdx in 0 until anchors.size - 1) {
                val p0 = anchors[segIdx]
                val p1 = controlPoints1[segIdx]
                val p2 = controlPoints2[segIdx + 1]
                val p3 = anchors[segIdx + 1]

                val startIdx = anchorIndices[segIdx]
                val endIdx = anchorIndices[segIdx + 1]

                for (i in startIdx..endIdx) {
                    // Calculate parameter t for this point within the segment
                    val t = if (endIdx > startIdx) {
                        (i - startIdx).toFloat() / (endIdx - startIdx)
                    } else {
                        0.5f
                    }

                    val bezierPoint = BezierUtils.evaluateCubicBezier(p0, p1, p2, p3, t)
                    val dist = distance(allPoints[i], bezierPoint)
                    totalError += dist
                    pointCount++
                }
            }

            return if (pointCount > 0) totalError / pointCount else 0f
        }

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
                    controlPoints2.add(PointF(segment.p0.x, segment.p0.y))  // Dummy incoming (COPY, not reference)
                }

                // Add the end anchor of this segment
                anchors.add(segment.p3)
                controlPoints2.add(segment.p2)  // Incoming to this anchor

                // Add outgoing control for this anchor (if not the last segment)
                if (segIndex < bezierSegments.size - 1) {
                    controlPoints1.add(bezierSegments[segIndex + 1].p1)
                } else {
                    // Last anchor: add dummy outgoing control (same as anchor)
                    controlPoints1.add(PointF(segment.p3.x, segment.p3.y))  // Dummy outgoing (COPY, not reference)
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

        private fun distance(p1: PointF, p2: PointF): Float = GeometryUtils.distance(p1, p2)
            
        private fun distanceSquared(p1: PointF, p2: PointF): Float = GeometryUtils.distanceSquared(p1, p2)

        /**
         * Evaluate Bezier curve at parameter t
         */
        private fun evaluateBezier(bezier: BezierSegment, t: Float): PointF {
            return BezierUtils.evaluateCubicBezier(bezier.p0, bezier.p1, bezier.p2, bezier.p3, t)
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