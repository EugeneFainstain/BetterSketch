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
                val forwardBreakpoints = greedySegmentation(points, epsilon, forward = true)
                val backwardBreakpoints = greedySegmentation(points, epsilon, forward = false)

                // MUST have same K
                if (forwardBreakpoints.size != backwardBreakpoints.size) {
                    continue
                }

                // Step 3: Average corresponding breakpoints
                val averagedBreakpoints = averageBreakpoints(forwardBreakpoints, backwardBreakpoints)

                // Step 4: Fit line to each segment (L2 least squares)
                val lines = fitLinesToSegments(points, averagedBreakpoints)

                // Step 5: Calculate intersections of adjacent lines
                val intersectionPoints = calculateIntersections(lines, points, averagedBreakpoints)

                // Calculate error
                val error = calculateFitError(points, averagedBreakpoints, lines)
                val normalizedError = error / scale

                // Step 3: Find segmentation that is 95% good
                if (normalizedError <= QUALITY_THRESHOLD * epsilonMultiplier) {
                    val (pathPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(intersectionPoints)
                    val fittedStroke = Stroke(
                        pathPoints.toMutableList(),
                        stroke.paint,
                        newTotalDistance,
                        0
                    )
                    val fitResult = FitResult(intersectionPoints, normalizedError, fittedStroke, averagedBreakpoints.size - 1)

                    if (bestFit == null || fitResult.k < bestFit.k) {
                        bestFit = fitResult
                    }
                }
            }

            return bestFit
        }

        /**
         * Greedy segmentation algorithm.
         * Returns list of breakpoint indices (including 0 and last index)
         */
        private fun greedySegmentation(points: List<PointF>, epsilon: Float, forward: Boolean): List<Int> {
            val n = points.size
            val breakpoints = mutableListOf<Int>()

            if (forward) {
                breakpoints.add(0)
                var currentStart = 0

                while (currentStart < n - 1) {
                    var currentEnd = currentStart + 1

                    // Extend segment as far as possible while staying within epsilon
                    while (currentEnd < n) {
                        val segment = points.subList(currentStart, currentEnd + 1)
                        val error = segmentError(segment)

                        if (error > epsilon) {
                            break
                        }
                        currentEnd++
                    }

                    // Take the furthest point that was still within epsilon
                    val nextBreakpoint = (currentEnd - 1).coerceAtLeast(currentStart + 1)
                    breakpoints.add(nextBreakpoint)
                    currentStart = nextBreakpoint
                }

                // Ensure last point is included
                if (breakpoints.last() != n - 1) {
                    breakpoints.add(n - 1)
                }
            } else {
                // Backward pass
                breakpoints.add(n - 1)
                var currentEnd = n - 1

                while (currentEnd > 0) {
                    var currentStart = currentEnd - 1

                    // Extend segment backwards as far as possible
                    while (currentStart >= 0) {
                        val segment = points.subList(currentStart, currentEnd + 1)
                        val error = segmentError(segment)

                        if (error > epsilon) {
                            break
                        }
                        currentStart--
                    }

                    val nextBreakpoint = (currentStart + 1).coerceAtMost(currentEnd - 1)
                    breakpoints.add(nextBreakpoint)
                    currentEnd = nextBreakpoint
                }

                if (breakpoints.last() != 0) {
                    breakpoints.add(0)
                }

                breakpoints.reverse()
            }

            return breakpoints.distinct().sorted()
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
         * Average corresponding breakpoint pairs from forward and backward passes
         */
        private fun averageBreakpoints(forward: List<Int>, backward: List<Int>): List<Int> {
            require(forward.size == backward.size) { "Forward and backward must have same length" }

            val averaged = mutableListOf<Int>()
            for (i in forward.indices) {
                averaged.add((forward[i] + backward[i]) / 2)
            }

            return averaged.distinct().sorted()
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
         * Fit lines to each segment defined by breakpoints
         */
        private fun fitLinesToSegments(points: List<PointF>, breakpoints: List<Int>): List<Line> {
            val lines = mutableListOf<Line>()

            for (i in 0 until breakpoints.size - 1) {
                val startIdx = breakpoints[i]
                val endIdx = breakpoints[i + 1]
                val segment = points.subList(startIdx, endIdx + 1)
                val line = fitLineToPoints(segment)
                lines.add(line)
            }

            return lines
        }

        /**
         * Calculate intersection points of adjacent lines
         */
        private fun calculateIntersections(
            lines: List<Line>,
            originalPoints: List<PointF>,
            breakpoints: List<Int>
        ): List<PointF> {
            if (lines.isEmpty()) return emptyList()

            val intersections = mutableListOf<PointF>()

            // First point: start of first line (project first original point onto first line)
            intersections.add(projectPointOntoLine(originalPoints[breakpoints[0]], lines[0]))

            // Intermediate points: intersections of adjacent lines
            for (i in 0 until lines.size - 1) {
                val intersection = intersectLines(lines[i], lines[i + 1])
                if (intersection != null) {
                    intersections.add(intersection)
                } else {
                    // Lines are parallel, use midpoint of segment
                    val idx = breakpoints[i + 1]
                    intersections.add(originalPoints[idx])
                }
            }

            // Last point: end of last line
            intersections.add(projectPointOntoLine(originalPoints[breakpoints.last()], lines.last()))

            return intersections
        }

        /**
         * Project a point onto a line
         */
        private fun projectPointOntoLine(point: PointF, line: Line): PointF {
            val a = line.a
            val b = line.b
            val c = line.c

            val denominator = a * a + b * b
            if (denominator < 1e-6f) return point

            // Projection formula
            val x = (b * (b * point.x - a * point.y) - a * c) / denominator
            val y = (a * (-b * point.x + a * point.y) - b * c) / denominator

            return PointF(x, y)
        }

        /**
         * Find intersection of two lines
         * Returns null if lines are parallel
         */
        private fun intersectLines(line1: Line, line2: Line): PointF? {
            val a1 = line1.a
            val b1 = line1.b
            val c1 = line1.c

            val a2 = line2.a
            val b2 = line2.b
            val c2 = line2.c

            val denominator = a1 * b2 - a2 * b1

            if (abs(denominator) < 1e-6f) {
                return null // Parallel lines
            }

            val x = (b1 * c2 - b2 * c1) / denominator
            val y = (a2 * c1 - a1 * c2) / denominator

            return PointF(x, y)
        }

        /**
         * Calculate total fitting error
         */
        private fun calculateFitError(
            points: List<PointF>,
            breakpoints: List<Int>,
            lines: List<Line>
        ): Float {
            var totalError = 0f
            var pointCount = 0

            for (i in 0 until breakpoints.size - 1) {
                val startIdx = breakpoints[i]
                val endIdx = breakpoints[i + 1]

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