
package com.example.bettersketch

import android.graphics.PointF
import kotlin.math.abs

object PolylineUtils {
    /**
     * Add a polyline anchor point at the specified index.
     * 
     * @param stroke The stroke to modify
     * @param index Index in unsmoothedPoints where the anchor should be added
     */
    fun addPolylineAnchorPoint(stroke: Stroke, index: Int) {
        // Initialize polylineIndices if empty (first anchor being added)
        if (stroke.polylineIndices.isEmpty()) {
            // Add first and last points as anchors
            stroke.polylineIndices.add(0)
            stroke.polylineIndices.add(stroke.unsmoothedPoints.size - 1)
        }

        // Find where to insert the new anchor in the sorted polylineIndices list
        var insertPosition = stroke.polylineIndices.size
        for (i in stroke.polylineIndices.indices) {
            if (index < stroke.polylineIndices[i]) {
                insertPosition = i
                break
            } else if (index == stroke.polylineIndices[i]) {
                // Already an anchor at this position, don't add
                return
            }
        }

        // Insert the new anchor
        stroke.polylineIndices.add(insertPosition, index)
        stroke.isModified = true

        // Regenerate the stroke
        stroke.regenerateInterpolatedPolylinePoints()
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
        if (stroke.polylineIndices.isEmpty()) return false

        // Find which polyline index corresponds to the editing point
        val polylineIndexToRemove = stroke.polylineIndices.indexOfFirst { it == pointIndex }
        if (polylineIndexToRemove == -1) return false

        // Don't allow removing if it would leave fewer than 2 vertices
        if (stroke.polylineIndices.size <= 2) return false

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

        // Remove the polyline index
        stroke.polylineIndices.removeAt(polylineIndexToRemove)
        stroke.isModified = true

        // Regenerate the stroke
        stroke.regenerateInterpolatedPolylinePoints()
        stroke.applySmoothing()

        return true
    }

    /**
     * Calculate weight function for a specific polyline anchor point.
     * Returns weights for each point in the stroke based on distance from the anchor.
     */
    fun calculateWeightsForAnchorPoint(stroke: Stroke, pointIndex: Int): List<Float> {
        // Calculate weight function for this specific anchor point
        if (stroke.polylineIndices.isNotEmpty() && stroke.polylineIndices.size >= 2 &&
            stroke.distancesForWeights.isNotEmpty()) {

            // Find which polyline anchor this corresponds to
            val closestDrawingDistance = if (pointIndex < stroke.distancesForWeights.size) {
                stroke.distancesForWeights[pointIndex]
            } else {
                return List(stroke.unsmoothedPoints.size) { 0f }
            }

            var closestPolylineIdxInArray = 0
            var minDistToAnchor = Float.MAX_VALUE

            for (i in stroke.polylineIndices.indices) {
                val anchorIndexInOriginal = stroke.polylineIndices[i]
                if (anchorIndexInOriginal >= 0 && anchorIndexInOriginal < stroke.distancesForWeights.size) {
                    val anchorDistance = stroke.distancesForWeights[anchorIndexInOriginal]
                    val distDiff = abs(anchorDistance - closestDrawingDistance)
                    if (distDiff < minDistToAnchor) {
                        minDistToAnchor = distDiff
                        closestPolylineIdxInArray = i
                    }
                }
            }

            if (closestPolylineIdxInArray >= 0 && closestPolylineIdxInArray < stroke.polylineIndices.size) {
                val leftPolylineArrayIdx = if (closestPolylineIdxInArray > 0) closestPolylineIdxInArray - 1 else 0
                val rightPolylineArrayIdx = if (closestPolylineIdxInArray < stroke.polylineIndices.size - 1) {
                    closestPolylineIdxInArray + 1
                } else {
                    stroke.polylineIndices.size - 1
                }

                val leftOriginalIdx = stroke.polylineIndices[leftPolylineArrayIdx].coerceIn(0, stroke.distancesForWeights.size - 1)
                val middleOriginalIdx = stroke.polylineIndices[closestPolylineIdxInArray].coerceIn(0, stroke.distancesForWeights.size - 1)
                val rightOriginalIdx = stroke.polylineIndices[rightPolylineArrayIdx].coerceIn(0, stroke.distancesForWeights.size - 1)

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
        // Regenerate interpolated polyline points and apply smoothing
        stroke.regenerateInterpolatedPolylinePoints()
        stroke.applySmoothing()
    }
}