package com.example.bettersketch

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class PolyLineFitter {
    data class FitResult(val points: List<PointF>, var error: Float)

    companion object {
        fun fit(stroke: Stroke): FitResult? {
            val points = stroke.unsmoothedPoints.map { it.point }
            if (points.size < 2) return null

            val distances = stroke.unsmoothedPoints.map { it.distance }
            val totalDistance = stroke.totalDistance

            var bestFit: FitResult? = null

            for (k in 3..20) {
                val centers = runKMeans(distances, k, totalDistance)
                val polylinePoints = centers.map { centerDist ->
                    getPointAtDistance(stroke, centerDist)
                }

                val error = calculateFitError(stroke, polylinePoints)

                if (bestFit == null || error < bestFit.error) {
                    bestFit = FitResult(polylinePoints, error)
                }
            }

////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////
            bestFit?.error = 0f
////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////
////////////////////////////////////////////////////////////

            return bestFit
        }

        private fun runKMeans(distances: List<Float>, k: Int, totalDistance: Float): List<Float> {
            if (k <= 2) {
                return listOf(0f, totalDistance)
            }

            val centers = mutableListOf<Float>()
            centers.add(0f)
            centers.add(totalDistance)

            // Initialize remaining centers
            for (i in 2 until k) {
                centers.add(totalDistance * i / (k - 1))
            }
            centers.sort()

            val assignments = IntArray(distances.size)

            repeat(100) { // Max 100 iterations
                // Assign points to centers
                for (i in distances.indices) {
                    var minDistance = Float.MAX_VALUE
                    var bestCenter = -1
                    for (j in centers.indices) {
                        val dist = abs(distances[i] - centers[j])
                        if (dist < minDistance) {
                            minDistance = dist
                            bestCenter = j
                        }
                    }
                    assignments[i] = bestCenter
                }

                // Update centers
                val newCenters = MutableList(k) { 0f }
                val counts = IntArray(k)
                for (i in distances.indices) {
                    val centerIndex = assignments[i]
                    newCenters[centerIndex] += distances[i]
                    counts[centerIndex]++
                }

                var changed = false
                for (i in 1 until k - 1) { // Don't move the first and last centers
                    val newCenter = if (counts[i] > 0) newCenters[i] / counts[i] else centers[i]
                    if (abs(newCenter - centers[i]) > 1e-4) {
                        changed = true
                    }
                    centers[i] = newCenter
                }

                if (!changed) return centers.sorted()
            }

            return centers.sorted()
        }

        private fun getPointAtDistance(stroke: Stroke, distance: Float): PointF {
            val points = stroke.unsmoothedPoints
            if (distance <= 0f) return points.first().point
            if (distance >= stroke.totalDistance) return points.last().point

            for (i in 0 until points.size - 1) {
                val p1 = points[i]
                val p2 = points[i + 1]
                if (distance >= p1.distance && distance <= p2.distance) {
                    val t = (distance - p1.distance) / (p2.distance - p1.distance)
                    return PointF(
                        p1.point.x + t * (p2.point.x - p1.point.x),
                        p1.point.y + t * (p2.point.y - p1.point.y)
                    )
                }
            }
            return points.last().point // Should not happen
        }

        private fun calculateFitError(stroke: Stroke, polylinePoints: List<PointF>): Float {
            var totalError = 0f
            for (pathPoint in stroke.unsmoothedPoints) {
                var minDistance = Float.MAX_VALUE
                for (i in 0 until polylinePoints.size - 1) {
                    val dist = distanceToSegment(pathPoint.point, polylinePoints[i], polylinePoints[i + 1])
                    if (dist < minDistance) {
                        minDistance = dist
                    }
                }
                totalError += minDistance
            }
            return totalError
        }

        private fun distanceToSegment(p: PointF, v: PointF, w: PointF): Float {
            val l2 = distSq(v, w)
            if (l2 == 0f) return dist(p, v)
            var t = ((p.x - v.x) * (w.x - v.x) + (p.y - v.y) * (w.y - v.y)) / l2
            t = t.coerceIn(0f, 1f)
            return dist(p, PointF(v.x + t * (w.x - v.x), v.y + t * (w.y - v.y)))
        }

        private fun dist(p1: PointF, p2: PointF): Float {
            return sqrt(distSq(p1, p2))
        }

        private fun distSq(p1: PointF, p2: PointF): Float {
            return (p1.x - p2.x).pow(2) + (p1.y - p2.y).pow(2)
        }
    }
}
