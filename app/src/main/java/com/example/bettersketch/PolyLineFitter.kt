
package com.example.bettersketch

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class PolyLineFitter {
    data class FitResult(val points: List<PointF>, var error: Float, val fittedStroke: Stroke, val k: Int)

    companion object {
        // Epsilon values to try (based on stroke scale)
        private val EPSILON_MULTIPLIERS = listOf(0.01f, 0.015f, 0.02f, 0.025f, 0.03f, 0.04f, 0.05f, 0.075f, 0.1f)
        private const val QUALITY_THRESHOLD = 0.95f // 95% good

        fun fit(stroke: Stroke): FitResult? {
            val points = stroke.unsmoothedPoints.map { it.point }
            if (points.size < 3) return null

            // Calculate stroke scale (diagonal of bounding box)
            val bounds = stroke.getBounds()
            val scale = sqrt(bounds.width().pow(2) + bounds.height().pow(2)) / 4
            if (scale <= 0f) return null

            var bestFit: FitResult? = null

            // Try different epsilon values
            for (epsilonMultiplier in EPSILON_MULTIPLIERS) {
                val epsilon = scale * epsilonMultiplier

                // Step 2: Run forward and backward greedy segmentation
                val forwardBreakpoints = greedySegmentation(points, epsilon)
                val backwardBreakpoints = greedySegmentation(points.reversed(), epsilon)

                // MUST have same K
                if (forwardBreakpoints.size != backwardBreakpoints.size) {
                    continue
                }

                // Step 3: Average corresponding breakpoints
                val (averagedIndices, averagedCoords) = averageBreakpoints(forwardBreakpoints, backwardBreakpoints, points)

                // Step 4: Fit line to averaged coordinates
                val lines = fitLinesToSegments(averagedCoords)

                // Step 5: Calculate intersections
                val intersectionPoints = calculateIntersections(lines, averagedCoords)

                // Calculate error using original points and averaged indices
                val error = calculateFitError(points, averagedIndices, lines)
                val normalizedError = error / scale

                // Find segmentation that is 95% good
                if (normalizedError <= QUALITY_THRESHOLD * epsilonMultiplier) {
                    val (pathPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(intersectionPoints)
                    val fittedStroke = Stroke(
                        pathPoints.toMutableList(),
                        stroke.paint,
                        newTotalDistance,
                        0
                    )
                    val fitResult = FitResult(intersectionPoints, normalizedError, fittedStroke, averagedCoords.size - 1)

                    if (bestFit == null || fitResult.k < bestFit.k) {
                        bestFit = fitResult
                    }
                }
            }

            return bestFit
        }

        /**
         * Greedy segmentation algorithm (forward direction only).
         * Returns list of breakpoint indices (including 0 and last index)
         */
        private fun greedySegmentation(points: List<PointF>, epsilon: Float): List<Int> {
            val n = points.size
            val breakpoints = mutableListOf<Int>()

            var current = 0
            breakpoints.add(current)

            while (current < n - 1) {
                var probe = current + 1

                // Extend segment as far as possible while staying within epsilon
                while (probe < n) {
                    val segment = points.subList(current, probe + 1)
                    val error = segmentError(segment)

                    if (error > epsilon)
                        break

                    probe++
                }

                // Update current to the furthest point that was still within epsilon
                // Note: this will eventually add the last point as the last breakpoint.
                current = probe - 1
                breakpoints.add(current)
            }

            return breakpoints
        }

        /**
         * Calculate maximum perpendicular distance from points to best-fit line
         */
        private fun segmentError(segment: List<PointF>): Float {
            if (segment.size < 2) return 0f

            val line = fitLineToPoints(segment)

            var maxError = 0f
            for (point in segment) {
                val error = perpendicularDistance(point, line)
                if (error > maxError) {
                    maxError = error
                }
            }

            return maxError
        }

        /**
         * Average corresponding breakpoint pairs from forward and backward passes.
         * Returns both the averaged indices and averaged coordinates.
         */
        private fun averageBreakpoints(
            forward: List<Int>,
            backward: List<Int>,
            points: List<PointF>
        ): Pair<List<Int>, List<PointF>> {
            require(forward.size == backward.size) { "Forward and backward must have same length" }

            val backwardReversed = backward.reversed()
            val averagedIndices = mutableListOf<Int>()
            val averagedCoords = mutableListOf<PointF>()
            val n = points.size

            for (i in forward.indices) {
                // Average indices for segment definition
                val backwardIdx = n - 1 - backwardReversed[i]
                val avgIdx = (forward[i] + backwardIdx) / 2
                averagedIndices.add(avgIdx)

                // Average coordinates for line fitting
                val forwardPoint = points[forward[i]]
                val backwardPoint = points[backwardIdx]
                val avgX = (forwardPoint.x + backwardPoint.x) / 2f
                val avgY = (forwardPoint.y + backwardPoint.y) / 2f
                averagedCoords.add(PointF(avgX, avgY))
            }

            return Pair(averagedIndices.distinct().sorted(), averagedCoords)
        }

        /**
         * Represents a line in 2D: ax + by + c = 0
         */
        private data class Line(val a: Float, val b: Float, val c: Float)

        /**
         * Fit a line to a set of points using least squares (L2)
         */
        private fun fitLineToPoints(points: List<PointF>): Line {
            if (points.size < 2) {
                return Line(0f, 0f, 0f)
            }

            // Calculate centroid
            var sumX = 0f
            var sumY = 0f
            for (p in points) {
                sumX += p.x
                sumY += p.y
            }
            val cx = sumX / points.size
            val cy = sumY / points.size

            // Calculate covariance
            var sumXX = 0f
            var sumYY = 0f
            var sumXY = 0f
            for (p in points) {
                val dx = p.x - cx
                val dy = p.y - cy
                sumXX += dx * dx
                sumYY += dy * dy
                sumXY += dx * dy
            }

            // Use principal component analysis
            // The line passes through centroid and aligns with principal direction
            if (abs(sumXY) < 1e-6f && abs(sumXX - sumYY) < 1e-6f) {
                // Points are aligned or clustered
                if (points.size >= 2) {
                    val p1 = points.first()
                    val p2 = points.last()
                    val dx = p2.x - p1.x
                    val dy = p2.y - p1.y
                    // Line: dy*x - dx*y + (dx*p1.y - dy*p1.x) = 0
                    return Line(dy, -dx, dx * p1.y - dy * p1.x)
                }
                return Line(0f, 1f, -cy)
            }

            // Find principal direction using eigenvalue decomposition
            val theta = 0.5f * kotlin.math.atan2(2 * sumXY, sumXX - sumYY)
            val cos = kotlin.math.cos(theta)
            val sin = kotlin.math.sin(theta)

            // Line equation: sin*(x-cx) - cos*(y-cy) = 0
            // or: sin*x - cos*y + (cos*cy - sin*cx) = 0
            return Line(sin, -cos, cos * cy - sin * cx)
        }

        /**
         * Calculate perpendicular distance from point to line
         */
        private fun perpendicularDistance(point: PointF, line: Line): Float {
            val numerator = abs(line.a * point.x + line.b * point.y + line.c)
            val denominator = sqrt(line.a * line.a + line.b * line.b)
            return if (denominator > 0) numerator / denominator else 0f
        }

        /**
         * Fit lines to segments defined by breakpoint coordinates
         */
        private fun fitLinesToSegments(breakpoints: List<PointF>): List<Line> {
            val lines = mutableListOf<Line>()

            for (i in 0 until breakpoints.size - 1) {
                // Fit line through two consecutive breakpoints
                val p1 = breakpoints[i]
                val p2 = breakpoints[i + 1]

                // Create line through these two points
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y

                // Line equation: dy*x - dx*y + (dx*p1.y - dy*p1.x) = 0
                lines.add(Line(dy, -dx, dx * p1.y - dy * p1.x))
            }

            return lines
        }

        /**
         * Calculate intersection points of adjacent lines
         */
        private fun calculateIntersections(
            lines: List<Line>,
            breakpoints: List<PointF>
        ): List<PointF> {
            if (lines.isEmpty()) return emptyList()

            val intersections = mutableListOf<PointF>()

            // First point: use first breakpoint
            intersections.add(breakpoints.first())

            // Intermediate points: intersections of adjacent lines
            for (i in 0 until lines.size - 1) {
                val intersection = intersectLines(lines[i], lines[i + 1])
                if (intersection != null) {
                    intersections.add(intersection)
                } else {
                    // Lines are parallel, use breakpoint
                    intersections.add(breakpoints[i + 1])
                }
            }

            // Last point: use last breakpoint
            intersections.add(breakpoints.last())

            return intersections
        }

        /**
         * Find intersection of two lines
         * Returns null if lines are parallel
         */
        private fun intersectLines(line1: Line, line2: Line): PointF? {
            val det = line1.a * line2.b - line2.a * line1.b
            if (abs(det) < 1e-6f) return null // Lines are parallel

            val x = (line1.b * line2.c - line2.b * line1.c) / det
            val y = (line2.a * line1.c - line1.a * line2.c) / det
            return PointF(x, y)
        }

        /**
         * Calculate total fitting error
         */
        private fun calculateFitError(
            points: List<PointF>,
            breakpointIndices: List<Int>,
            lines: List<Line>
        ): Float {
            var totalError = 0f
            var pointCount = 0

            for (i in 0 until breakpointIndices.size - 1) {
                val startIdx = breakpointIndices[i]
                val endIdx = breakpointIndices[i + 1]

                // Calculate error for all points in this segment
                for (j in startIdx..endIdx) {
                    val error = perpendicularDistance(points[j], lines[i])
                    totalError += error
                    pointCount++
                }
            }

            return if (pointCount > 0) totalError / pointCount else 0f
        }
    }
}