
package com.example.bettersketch

import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.sqrt

object PolylineUtils {

    /**
     * Helper function to interpolate points along a polyline.
     * Used by regenerateUnsmoothedPointsFromAnalytical for all shape types.
     */
    fun interpolateAlongPolyLine(vertices: List<PointF>, targetPointCount: Int): List<PointF> {
        if (vertices.size < 2 || targetPointCount < 2) return vertices

        val interpolatedPoints = mutableListOf<PointF>()

        // Calculate cumulative distances for each vertex
        val vertexDistances = mutableListOf(0f)
        var totalDistance = 0f
        for (i in 1 until vertices.size) {
            val dx = vertices[i].x - vertices[i - 1].x
            val dy = vertices[i].y - vertices[i - 1].y
            totalDistance += sqrt(dx * dx + dy * dy)
            vertexDistances.add(totalDistance)
        }

        if (totalDistance <= 0f) {
            // Degenerate case: all vertices are at the same point
            return listOf(vertices.first())
        }

        // Generate uniform spacing points and include vertex points
        val targetDistances = mutableSetOf<Float>()
        val spacing = totalDistance / (targetPointCount - 1)

        // Add uniformly spaced points
        for (i in 0 until targetPointCount) {
            targetDistances.add(i * spacing)
        }

        // Add all vertex distances to ensure they're included
        targetDistances.addAll(vertexDistances)

        // Interpolate at all target distances
        for (targetDist in targetDistances) {
            val point = interpolatePointOnPolyLine(vertices, targetDist)
            interpolatedPoints.add(point)
        }

        return interpolatedPoints
    }

    /**
     * Helper to create the initial interpolation for a polyline stroke
     */
    public fun interpolateAlongPolyLineWithIndices(
        vertices: List<PointF>,
        vertexIndices: List<Int>,
        targetPointCount: Int
    ): List<PointF> {
        if (vertices.size < 2 || vertexIndices.size < 2) return vertices
        if (targetPointCount < 2) return vertices

        val interpolatedPoints = MutableList<PointF?>(targetPointCount) { null }

        // Place each vertex at its designated index
        for (i in vertices.indices) {
            val index = vertexIndices[i]
            if (index < targetPointCount) {
                interpolatedPoints[index] = vertices[i]
            }
        }

        // Fill in the gaps between vertices with linear interpolation
        for (i in 0 until vertices.size - 1) {
            val startIdx = vertexIndices[i]
            val endIdx = vertexIndices[i + 1]

            if (startIdx >= targetPointCount || endIdx >= targetPointCount) continue

            val startPoint = vertices[i]
            val endPoint = vertices[i + 1]

            val segmentPointCount = endIdx - startIdx + 1

            // Interpolate points between startIdx and endIdx
            for (j in 0 until segmentPointCount) {
                val t = j.toFloat() / (segmentPointCount - 1).toFloat()
                val x = startPoint.x + t * (endPoint.x - startPoint.x)
                val y = startPoint.y + t * (endPoint.y - startPoint.y)
                interpolatedPoints[startIdx + j] = PointF(x, y)
            }
        }

        // Return the list, filtering out any nulls
        return interpolatedPoints.filterNotNull()
    }

    /**
     * Interpolates a point at a specific distance along the polyline.
     * Private - only used internally by interpolateAlongPolyLine.
     */
    private fun interpolatePointOnPolyLine(vertices: List<PointF>, targetDistance: Float): PointF {
        if (vertices.size < 2) return vertices.first()

        var accumulatedDistance = 0f

        for (i in 1 until vertices.size) {
            val start = vertices[i - 1]
            val end = vertices[i]
            val dx = end.x - start.x
            val dy = end.y - start.y
            val segmentLength = sqrt(dx * dx + dy * dy)

            if (accumulatedDistance + segmentLength >= targetDistance) {
                // Target distance is within this segment
                val remainingDistance = targetDistance - accumulatedDistance
                val t = if (segmentLength > 0f) remainingDistance / segmentLength else 0f
                val x = start.x + t * dx
                val y = start.y + t * dy
                return PointF(x, y)
            }

            accumulatedDistance += segmentLength
        }

        // If we reach here, return the last vertex
        return vertices.last()
    }

    /**
     * Add a polyline anchor point at the specified index.
     *
     * @param stroke The stroke to modify
     * @param index Index in unsmoothedPoints where the anchor should be added
     */
    fun addPolylineAnchorPoint(stroke: Stroke, index: Int) {
        // Initialize anchorIndices if empty (first anchor being added)
        if (stroke.anchorIndices.isEmpty()) {
            // Add first and last points as anchors
            stroke.anchorIndices.add(0)
            stroke.anchorIndices.add(stroke.unsmoothedPoints.size - 1)
        }

        // Find where to insert the new anchor in the sorted anchorIndices list
        var insertPosition = stroke.anchorIndices.size
        for (i in stroke.anchorIndices.indices) {
            if (index < stroke.anchorIndices[i]) {
                insertPosition = i
                break
            } else if (index == stroke.anchorIndices[i]) {
                // Already an anchor at this position, don't add
                return
            }
        }

        // Insert the new anchor
        stroke.anchorIndices.add(insertPosition, index)
        stroke.isModified = true

        // Regenerate the stroke
        regenerateInterpolatedPolylinePoints(stroke)
        stroke.applySmoothing()
    }

    /**
     * Remove a polyline anchor point at the specified index.
     *
     * @param stroke The stroke to modify
     * @param pointIndex Index into unsmoothedPoints
     * @param snapshotUnsmoothedPoints Snapshot of unsmoothedPoints for restoration
     * @return True if the anchor was removed, false if removal was not allowed
     */
    fun removePolylineAnchorPointAtIndex(
        stroke: Stroke,
        pointIndex: Int,
        snapshotUnsmoothedPoints: MutableList<PathPoint>?
    ): Boolean {
        if (stroke.anchorIndices.isEmpty()) return false

        // Find which anchor index corresponds to the editing point
        val anchorIndexToRemove = stroke.anchorIndices.indexOfFirst { it == pointIndex }
        if (anchorIndexToRemove == -1) return false

        // Don't allow removing if it would leave fewer than 2 vertices
        if (stroke.anchorIndices.size <= 2) return false

        // Restore unsmoothedPoints to the snapshot if provided
        if (snapshotUnsmoothedPoints != null) {
            stroke.unsmoothedPoints.clear()
            stroke.unsmoothedPoints.addAll(
                snapshotUnsmoothedPoints.map {
                    PathPoint(PointF(it.point.x, it.point.y), it.distance)
                }
            )

            // Recalculate distances
            val (recalculatedPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(
                stroke.unsmoothedPoints.map { it.point }
            )
            stroke.unsmoothedPoints.clear()
            stroke.unsmoothedPoints.addAll(recalculatedPoints)
            stroke.totalDistance = newTotalDistance
        }

        // Remove the anchor index
        stroke.anchorIndices.removeAt(anchorIndexToRemove)
        stroke.isModified = true

        // Regenerate the stroke
        regenerateInterpolatedPolylinePoints(stroke)
        stroke.applySmoothing()

        return true
    }

    /**
     * Calculate weight function for a specific polyline anchor point.
     * Returns weights for each point in the stroke based on distance from the anchor.
     */
    fun calculateWeightsForAnchorPoint(stroke: Stroke, pointIndex: Int): List<Float> {
        // Calculate weight function for this specific anchor point
        if (stroke.anchorIndices.isNotEmpty() && stroke.anchorIndices.size >= 2 &&
            stroke.distancesForWeights.isNotEmpty()) {

            // Find which polyline anchor this corresponds to
            val closestDrawingDistance = if (pointIndex < stroke.distancesForWeights.size) {
                stroke.distancesForWeights[pointIndex]
            } else {
                return List(stroke.unsmoothedPoints.size) { 0f }
            }

            var closestAnchorIdxInArray = 0
            var minDistToAnchor = Float.MAX_VALUE

            for (i in stroke.anchorIndices.indices) {
                val anchorIndexInOriginal = stroke.anchorIndices[i]
                if (anchorIndexInOriginal >= 0 && anchorIndexInOriginal < stroke.distancesForWeights.size) {
                    val anchorDistance = stroke.distancesForWeights[anchorIndexInOriginal]
                    val distDiff = abs(anchorDistance - closestDrawingDistance)
                    if (distDiff < minDistToAnchor) {
                        minDistToAnchor = distDiff
                        closestAnchorIdxInArray = i
                    }
                }
            }

            if (closestAnchorIdxInArray >= 0 && closestAnchorIdxInArray < stroke.anchorIndices.size) {
                val leftAnchorArrayIdx = if (closestAnchorIdxInArray > 0) closestAnchorIdxInArray - 1 else 0
                val rightAnchorArrayIdx = if (closestAnchorIdxInArray < stroke.anchorIndices.size - 1) {
                    closestAnchorIdxInArray + 1
                } else {
                    stroke.anchorIndices.size - 1
                }

                val leftOriginalIdx = stroke.anchorIndices[leftAnchorArrayIdx].coerceIn(0, stroke.distancesForWeights.size - 1)
                val middleOriginalIdx = stroke.anchorIndices[closestAnchorIdxInArray].coerceIn(0, stroke.distancesForWeights.size - 1)
                val rightOriginalIdx = stroke.anchorIndices[rightAnchorArrayIdx].coerceIn(0, stroke.distancesForWeights.size - 1)

                val leftDist = stroke.distancesForWeights[leftOriginalIdx]
                val middleDist = stroke.distancesForWeights[middleOriginalIdx]
                val rightDist = stroke.distancesForWeights[rightOriginalIdx]

                return stroke.distancesForWeights.mapIndexed { index, dist ->
                    when {
                        dist < leftDist || dist > rightDist -> 0f
                        dist <= middleDist -> {
                            val segmentLength = middleDist - leftDist
                            if (segmentLength == 0f) 1f
                            else {
                                val t = (dist - leftDist) / segmentLength
                                val angle = t * kotlin.math.PI.toFloat() / 2f
                                kotlin.math.sin(angle) * kotlin.math.sin(angle)
                            }
                        }
                        else -> {
                            val segmentLength = rightDist - middleDist
                            if (segmentLength == 0f) 1f
                            else {
                                val t = (dist - middleDist) / segmentLength
                                val angle = (1f - t) * kotlin.math.PI.toFloat() / 2f
                                kotlin.math.sin(angle) * kotlin.math.sin(angle)
                            }
                        }
                    }
                }
            }
        }

        // Fallback to original behavior
        val totalDistanceOfUnsmoothed = stroke.unsmoothedPoints.lastOrNull()?.distance ?: return List(stroke.unsmoothedPoints.size) { 0f }
        val middlePointRelativeDistance = if (pointIndex < stroke.unsmoothedPoints.size) {
            stroke.unsmoothedPoints[pointIndex].distance / totalDistanceOfUnsmoothed
        } else {
            0.5f
        }

        return stroke.unsmoothedPoints.map {
            val relativeDistance = it.distance / totalDistanceOfUnsmoothed
            val mappedDistance = if (relativeDistance <= middlePointRelativeDistance) {
                relativeDistance / middlePointRelativeDistance
            } else {
                1 - ((relativeDistance - middlePointRelativeDistance) / (1 - middlePointRelativeDistance))
            }
            kotlin.math.sin(mappedDistance * kotlin.math.PI / 2).toFloat()
        }
    }

    /**
     * Move a polyline anchor point using weighted transformation.
     * 
     * @param stroke The stroke being edited
     * @param weights Pre-calculated weights for each point
     * @param dx Delta X movement
     * @param dy Delta Y movement
     */
    fun movePolylineAnchorWithWeights(stroke: Stroke, weights: List<Float>, dx: Float, dy: Float) {
        // Apply weighted transformation to unsmoothedPoints
        if (weights.size == stroke.unsmoothedPoints.size) {
            stroke.unsmoothedPoints.forEachIndexed { index, pathPoint ->
                pathPoint.point.offset(dx * weights[index], dy * weights[index])
            }
        }

        // Recalculate distances for unsmoothed points
        val (recalculatedUnsmoothedPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(
            stroke.unsmoothedPoints.map { it.point }
        )
        stroke.unsmoothedPoints.clear()
        stroke.unsmoothedPoints.addAll(recalculatedUnsmoothedPoints)
        stroke.totalDistance = newTotalDistance

        stroke.isModified = true
    }

    /**
     * Regenerates interpolatedPolylinePoints from polylinePoints (vertex-only representation).
     * This creates a piece-wise linear interpolation between vertices.
     */
    fun regenerateInterpolatedPolylinePoints(stroke: Stroke) {
        // Try to regenerate interpolatedPolylinePoints, or skip if conditions aren't met
        // Early exit conditions - if any fail, skip to applySmoothing
        if (stroke.anchorIndices.isEmpty() || stroke.unsmoothedPoints.isEmpty()) return

        val pointCount = stroke.originalPoints.size
        if (pointCount < 2) return

        // Extract vertices from unsmoothedPoints using anchorIndices
        val vertices = stroke.anchorIndices.mapNotNull { index ->
            if (index >= 0 && index < stroke.unsmoothedPoints.size) {
                stroke.unsmoothedPoints[index].point
            } else {
                null
            }
        }

        if (vertices.isEmpty()) return

        // Interpolate along the polyline vertices with vertices placed at their specific indices
        val interpolatedPoints = interpolateAlongPolyLineWithIndices(vertices, stroke.anchorIndices, pointCount)

        // Update interpolatedPolylinePoints
        val (pathPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(interpolatedPoints)
        stroke.interpolatedPolylinePoints.clear()
        stroke.interpolatedPolylinePoints.addAll(pathPoints)
    }
}