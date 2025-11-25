
package com.example.bettersketch

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class PolyLineFitter {
    data class FitResult(val points: List<PointF>, var error: Float, val fittedStroke: Stroke, val k: Int)

    companion object {
        // Epsilon values to try (based on stroke scale)
        private val EPSILON_MULTIPLIERS =
            listOf(0.01f, 0.015f, 0.02f, 0.025f, 0.03f, 0.04f, 0.05f, 0.075f, 0.1f)
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
                val (averagedIndices, averagedCoords) = averageBreakpoints(
                    forwardBreakpoints,
                    backwardBreakpoints,
                    points
                )

                // Step 4: Fit lines to segments using parametric least squares on original points
                val lines = fitLinesToSegments(points, averagedIndices)

                // Step 5: Calculate intersections of adjacent fitted lines
                val intersectionPoints = calculateIntersections(lines, points, averagedIndices)

                // Calculate error
                val error = calculateFitError(points, averagedIndices, lines)
                val normalizedError = error / scale

                // Find segmentation that is 95% good
                if (normalizedError <= QUALITY_THRESHOLD * epsilonMultiplier) {
                    val fittedStroke = createPolyLineStroke(
                        intersectionPoints,
                        stroke.paint,
                        stroke.pointsForDrawing.size
                    )
                    val fitResult = FitResult(
                        intersectionPoints,
                        normalizedError,
                        fittedStroke,
                        averagedIndices.size - 1
                    )

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
         * Fit a line to equidistantly sampled points using parametric form.
         * Fits x(t) and y(t) separately where t is the point index.
         */
        private fun fitLineToPoints(points: List<PointF>): Line {
            if (points.size < 2) {
                return Line(0f, 0f, 0f)
            }

            if (points.size == 2) {
                // Just connect the two points
                val p1 = points[0]
                val p2 = points[1]
                val dx = p2.x - p1.x
                val dy = p2.y - p1.y
                return Line(dy, -dx, dx * p1.y - dy * p1.x)
            }

            val n = points.size

            // Calculate mean index (for centered fitting)
            val meanT = (n - 1) / 2f

            // Calculate means of x and y
            var sumX = 0f
            var sumY = 0f
            for (p in points) {
                sumX += p.x
                sumY += p.y
            }
            val meanX = sumX / n
            val meanY = sumY / n

            // Fit x(t) = ax * t + bx  and  y(t) = ay * t + by
            // Using least squares: slope = Σ((t - meanT) * (val - meanVal)) / Σ((t - meanT)²)

            var sumTSquared = 0f
            var sumTX = 0f
            var sumTY = 0f

            for (i in points.indices) {
                val t = i.toFloat()
                val dt = t - meanT
                sumTSquared += dt * dt
                sumTX += dt * (points[i].x - meanX)
                sumTY += dt * (points[i].y - meanY)
            }

            if (sumTSquared < 1e-6f) {
                // All points at same t (shouldn't happen with size > 2, but handle it)
                return Line(0f, 1f, -meanY)
            }

            // Slopes of x(t) and y(t)
            val ax = sumTX / sumTSquared
            val ay = sumTY / sumTSquared

            // Intercepts (using the fact that line passes through (meanX, meanY) at t = meanT)
            val bx = meanX - ax * meanT
            val by = meanY - ay * meanT

            // The parametric line is: (x, y) = (ax*t + bx, ay*t + by)
            // Direction vector: (ax, ay)
            // Point on line: (bx, by) when t = 0
            // Line in implicit form: ay*(x - bx) - ax*(y - by) = 0
            // Simplifying: ay*x - ax*y + (ax*by - ay*bx) = 0

            return Line(ay, -ax, ax * by - ay * bx)
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
         * Fit lines to segments defined by breakpoint indices.
         * Each line is fitted to the original points in that segment using parametric least squares.
         */
        private fun fitLinesToSegments(
            points: List<PointF>,
            breakpointIndices: List<Int>
        ): List<Line> {
            val lines = mutableListOf<Line>()

            for (i in 0 until breakpointIndices.size - 1) {
                val startIdx = breakpointIndices[i]
                val endIdx = breakpointIndices[i + 1]

                // Extract the segment of original points
                val segment = points.subList(startIdx, endIdx + 1)

                // Fit line using parametric least squares
                val line = fitLineToPoints(segment)
                lines.add(line)
            }

            return lines
        }

        /**
         * Calculate intersection points of adjacent fitted lines.
         * Uses projections for the first and last points.
         */
        private fun calculateIntersections(
            lines: List<Line>,
            originalPoints: List<PointF>,
            breakpointIndices: List<Int>
        ): List<PointF> {
            if (lines.isEmpty()) return emptyList()

            val intersections = mutableListOf<PointF>()

            // First point: project first original point onto first line
            val firstPoint = originalPoints[breakpointIndices.first()]
            intersections.add(projectPointOntoLine(firstPoint, lines.first()))

            // Intermediate points: intersections of adjacent lines
            for (i in 0 until lines.size - 1) {
                val intersection = intersectLines(lines[i], lines[i + 1])
                if (intersection != null) {
                    intersections.add(intersection)
                } else {
                    // Lines are parallel, project breakpoint onto one of the lines
                    val breakpointIdx = breakpointIndices[i + 1]
                    intersections.add(projectPointOntoLine(originalPoints[breakpointIdx], lines[i]))
                }
            }

            // Last point: project last original point onto last line
            val lastPoint = originalPoints[breakpointIndices.last()]
            intersections.add(projectPointOntoLine(lastPoint, lines.last()))

            return intersections
        }

        /**
         * Project a point onto a line
         */
        private fun projectPointOntoLine(point: PointF, line: Line): PointF {
            val denom = line.a * line.a + line.b * line.b
            if (denom < 1e-6f) return point

            val t = -(line.a * point.x + line.b * point.y + line.c) / denom
            return PointF(point.x + t * line.a, point.y + t * line.b)
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

        /**
         * Create a fitted polyline stroke with analytical points at the line vertices
         * and interpolated points distributed along the segments matching the original stroke's point count
         */
        private fun createPolyLineStroke(
            vertices: List<PointF>,
            paint: android.graphics.Paint,
            targetPointCount: Int
        ): Stroke {
            val stroke = Stroke(paint, 0)
            stroke.analyticalShapeType = AnalyticalShapeType.POLYLINE

            // Store the analytical line vertices
            val (analyticalPathPoints, analyticalTotalDistance) = Stroke.calculatePathPointsWithDistances(
                vertices
            )
            stroke.analyticalPoints.addAll(analyticalPathPoints)

            // Generate interpolated points using Stroke utility
            val interpolatedPoints = stroke.interpolateAlongPolyLine(vertices, targetPointCount)

            // Create the stroke with interpolated points
            val (pathPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(
                interpolatedPoints
            )
            stroke.unsmoothedPoints.addAll(pathPoints)
            stroke.pointsForDrawing.addAll(pathPoints.map {
                PathPoint(
                    PointF(
                        it.point.x,
                        it.point.y
                    ), it.distance
                )
            })
            stroke.originalPoints.addAll(pathPoints.map {
                PathPoint(
                    PointF(it.point.x, it.point.y),
                    it.distance
                )
            })
            stroke.totalDistance = newTotalDistance

            return stroke
        }
    }
}