
package com.example.bettersketch

import android.graphics.PointF

object PolylineUtils {
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
}