
package com.example.bettersketch

import android.graphics.Matrix
import android.graphics.PointF
import com.example.bettersketch.GeometryUtils.distance

object StrokeUtils {
    /**
     * Data class to hold the result of finding closest point across multiple strokes
     */
    data class ClosestPointResult(val stroke: Stroke, val pointIndex: Int, val distance: Float)

    /**
     * Remove an anchor point from a stroke at the specified index.
     * Handles both Bezier and Polyline modes automatically.
     * 
     * @param stroke The stroke to modify
     * @param pointIndex For Bezier mode: index into unsmoothedPoints. For Polyline mode: also index into unsmoothedPoints
     * @param snapshotUnsmoothedPoints Snapshot of unsmoothedPoints for undo (used in polyline mode)
     * @param isBezierAnchor True if this is a Bezier anchor, false for polyline anchor
     * @return True if the anchor was removed, false if removal was not allowed
     */
    fun removeAnchorPointAtIndex(
        stroke: Stroke,
        pointIndex: Int,
        snapshotUnsmoothedPoints: MutableList<PathPoint>? = null,
        isBezierAnchor: Boolean = false
    ): Boolean {
        return if (isBezierAnchor && stroke.renderAsBezier && stroke.bezierAnchorIndices.isNotEmpty()) {
            BezierUtils.removeBezierAnchorPointAtIndex(stroke, pointIndex)
        } else {
            PolylineUtils.removePolylineAnchorPointAtIndex(stroke, pointIndex, snapshotUnsmoothedPoints)
        }
    }

    /**
     * Find the closest point on a stroke's curve to a given tap point.
     * 
     * @param stroke The stroke to search
     * @param tapPoint The point to find the closest point to
     * @return The index of the closest point in pointsForDrawing, or -1 if not found
     */
    fun findClosestPointOnCurve(stroke: Stroke, tapPoint: PointF): Int {
        if (stroke.isGroup) return -1

        var closestDist = Float.MAX_VALUE
        var closestPointIndex = -1

        stroke.pointsForDrawing.forEachIndexed { index, pathPoint ->
            val d = distance(pathPoint.point, tapPoint)
            if (d < closestDist) {
                closestDist = d
                closestPointIndex = index
            }
        }

        return closestPointIndex
    }

    /**
     * Find the closest point across all highlighted strokes.
     * 
     * @param tapPoint The point to find the closest point to
     * @param highlightedStrokes List of highlighted strokes to search
     * @return ClosestPointResult containing the stroke, point index, and distance, or null if none found
     */
    fun findClosestPointAcrossHighlightedStrokes(
        tapPoint: PointF,
        highlightedStrokes: List<Stroke>
    ): ClosestPointResult? {
        var bestResult: ClosestPointResult? = null
        var minDistance = Float.MAX_VALUE

        highlightedStrokes.forEach { stroke ->
            stroke.forEachStroke { s ->
                if (!s.isGroup) {
                    val pointIndex = findClosestPointOnCurve(s, tapPoint)
                    if (pointIndex != -1 && pointIndex < s.pointsForDrawing.size) {
                        val point = s.pointsForDrawing[pointIndex].point
                        val dist = distance(point, tapPoint)
                        if (dist < minDistance) {
                            minDistance = dist
                            bestResult = ClosestPointResult(s, pointIndex, dist)
                        }
                    }
                }
            }
        }

        return bestResult
    }

    /**
     * Undo modifications for all highlighted strokes, restoring them to their original state.
     * 
     * @param highlightedStrokes List of strokes to restore
     */
    fun undoModificationsForHighlightedStrokes(highlightedStrokes: List<Stroke>) {
        highlightedStrokes.forEach { stroke ->
            stroke.forEachStroke {
                it.unsmoothedPoints.clear()
                it.unsmoothedPoints.addAll(it.originalPoints.map { p -> 
                    PathPoint(PointF(p.point.x, p.point.y), p.distance) 
                })
                val (recalculatedUnsmoothedPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(
                    it.unsmoothedPoints.map { p -> p.point }
                )
                it.unsmoothedPoints.clear()
                it.unsmoothedPoints.addAll(recalculatedUnsmoothedPoints)
                it.totalDistance = newTotalDistance
                it.applySmoothing()
                it.isModified = false
            }
        }
    }

    /**
     * Transform a stroke by applying a matrix transformation to all its points.
     * 
     * @param stroke The stroke to transform
     * @param matrix The transformation matrix to apply
     */
    fun transformStroke(stroke: Stroke, matrix: Matrix) {
        val scale = GeometryUtils.getScaleFromMatrix(matrix)
        stroke.forEachStroke { s ->
            s.isModified = true

            s.unsmoothedPoints.forEach { pathPoint ->
                val point = floatArrayOf(pathPoint.point.x, pathPoint.point.y)
                matrix.mapPoints(point)
                pathPoint.point.set(point[0], point[1])
            }
            val (recalculatedUnsmoothedPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(
                s.unsmoothedPoints.map { it.point }
            )
            s.unsmoothedPoints.clear()
            s.unsmoothedPoints.addAll(recalculatedUnsmoothedPoints)
            s.totalDistance = newTotalDistance

            s.interpolatedPolylinePoints.forEach { pathPoint ->
                val point = floatArrayOf(pathPoint.point.x, pathPoint.point.y)
                matrix.mapPoints(point)
                pathPoint.point.set(point[0], point[1])
            }
            val (recalculatedInterpolatedPolylinePoints, _) = Stroke.calculatePathPointsWithDistances(
                s.interpolatedPolylinePoints.map { it.point }
            )
            s.interpolatedPolylinePoints.clear()
            s.interpolatedPolylinePoints.addAll(recalculatedInterpolatedPolylinePoints)

            // Transform bezier control points (anchors are in unsmoothedPoints, already transformed above)
            s.bezierControlPoints1.forEach { point ->
                val p = floatArrayOf(point.x, point.y)
                matrix.mapPoints(p)
                point.set(p[0], p[1])
            }

            s.bezierControlPoints2.forEach { point ->
                val p = floatArrayOf(point.x, point.y)
                matrix.mapPoints(p)
                point.set(p[0], p[1])
            }

            s.applySmoothing()
        }
    }

    /**
     * Revert a stroke to its original state, clearing all analytical shape and bezier data.
     * 
     * @param stroke The stroke to revert
     */
    fun revertStrokeToOriginal(stroke: Stroke) {
        // Reset analytical shape properties
        stroke.analyticalShapeType = AnalyticalShapeType.NONE
        stroke.renderAsPolyline = false
        stroke.renderAsBezier = false
        stroke.needsToRegenerate = false
        stroke.polylineIndices.clear()
        stroke.shapeParameterPoints.clear()

        // Clear bezier data
        stroke.bezierControlPoints1.clear()
        stroke.bezierControlPoints2.clear()
        stroke.bezierAnchorIndices.clear()

        // Restore from originalPoints
        stroke.unsmoothedPoints.clear()
        stroke.unsmoothedPoints.addAll(
            stroke.originalPoints.map { PathPoint(PointF(it.point.x, it.point.y), it.distance) }
        )

        // Recalculate distances
        val (recalculatedPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(
            stroke.unsmoothedPoints.map { it.point }
        )
        stroke.unsmoothedPoints.clear()
        stroke.unsmoothedPoints.addAll(recalculatedPoints)
        stroke.totalDistance = newTotalDistance

        // Reapply smoothing
        stroke.applySmoothing()
    }

    /**
     * Duplicate a stroke with a vertical offset.
     * 
     * @param originalStroke The stroke to duplicate
     * @param globalTransform The global transformation matrix for screen-space conversion
     * @return The duplicated stroke, or null if originalStroke is null
     */
    fun duplicateStroke(originalStroke: Stroke, globalTransform: Matrix): Stroke {
        val duplicatedStroke = originalStroke.newFrom() // Create a copy of the original stroke
        transformStroke(duplicatedStroke, globalTransform) // Bring it into screen-space

        val bounds = duplicatedStroke.getBounds() // Calculate the bounds, in screen-space
        val offsetY = -kotlin.math.max(bounds.width(), bounds.height()) / 2f
        val matrix = Matrix().apply { postTranslate(0f, offsetY) }
        transformStroke(duplicatedStroke, matrix) // Offset in screen-space

        val inverseGlobalTransform = Matrix()
        globalTransform.invert(inverseGlobalTransform)
        transformStroke(duplicatedStroke, inverseGlobalTransform) // Bring it back into world-space

        // Explicitly deleting "undo" history:
        duplicatedStroke.originalPoints.clear()
        duplicatedStroke.originalPoints.addAll(
            duplicatedStroke.unsmoothedPoints.map { 
                PathPoint(PointF(it.point.x, it.point.y), it.distance) 
            }
        )
        duplicatedStroke.isModified = false

        return duplicatedStroke
    }

    /**
     * Find the closest anchor point (either Bezier or Polyline) across all strokes.
     * 
     * @param tapPoint The point to search from
     * @param highlightedStrokes List of strokes to search
     * @return Pair of (Stroke, anchor index) or null if none found
     */
    fun findClosestAnchorPointAcrossAllStrokes(
        tapPoint: PointF,
        highlightedStrokes: List<Stroke>
    ): Pair<Stroke, Int>? {
        var closestStroke: Stroke? = null
        var closestPointIndex = -1
        var closestDist = Float.MAX_VALUE

        if (highlightedStrokes.isEmpty()) return null

        highlightedStrokes.forEach { stroke ->
            stroke.forEachStroke { s ->
                if (!s.isGroup) {
                    // Check if in bezier mode
                    if (s.renderAsBezier && s.bezierAnchorIndices.isNotEmpty()) {
                        // Search through bezier anchor points via indices
                        s.bezierAnchorIndices.forEachIndexed { anchorIndex, pointIndex ->
                            val anchorPoint = s.unsmoothedPoints.getOrNull(pointIndex)?.point ?: return@forEachIndexed
                            val d = distance(anchorPoint, tapPoint)
                            if (d < closestDist) {
                                closestDist = d
                                closestPointIndex = anchorIndex  // This is the index in bezierAnchorIndices
                                closestStroke = s
                            }
                        }
                    } else if (s.polylineIndices.isNotEmpty()) {
                        // Search through polyline anchor points (indices into unsmoothedPoints)
                        s.polylineIndices.forEach { anchorIndex ->
                            if (anchorIndex >= 0 && anchorIndex < s.unsmoothedPoints.size) {
                                val anchorPoint = s.unsmoothedPoints[anchorIndex].point
                                val d = distance(anchorPoint, tapPoint)
                                if (d < closestDist) {
                                    closestDist = d
                                    closestPointIndex = anchorIndex  // This is an index in unsmoothedPoints
                                    closestStroke = s
                                }
                            }
                        }
                    }
                }
            }
        }

        return if (closestStroke != null && closestPointIndex != -1) {
            Pair(closestStroke!!, closestPointIndex)
        } else null
    }

    /**
     * Select a stroke at the given tap point.
     * Returns the index of the selected stroke, or -1 if none found.
     *
     * @param tapPointScreen Tap point in screen coordinates
     * @param tapPointWorld Tap point in world coordinates
     * @param strokes List of all strokes
     * @param screenLongDimension The longer dimension of the screen (width or height)
     * @param toScreenCoordinates Function to convert world coordinates to screen coordinates
     * @return Index of the selected stroke, or -1 if none found
     */
    fun selectStrokeAt(
        tapPointScreen: PointF,
        tapPointWorld: PointF,
        strokes: List<Stroke>,
        screenLongDimension: Int,
        toScreenCoordinates: (Float, Float) -> PointF
    ): Int {
        var minDistance = Float.MAX_VALUE
        var closestStrokeIndex = -1
        var closestPointWorld: PointF? = null

        strokes.forEachIndexed { index, stroke ->
            // Ensure stroke is up-to-date before accessing its points
            if (stroke.needsToRegenerate) {
                stroke.regenerateUnsmoothedPointsFromAnalytical()
                stroke.needsToRegenerate = false
            }

            stroke.forEachStroke { s ->
                for (pathPoint in s.pointsForDrawing) {
                    val d = distance(pathPoint.point, tapPointWorld)
                    if (d < minDistance) {
                        minDistance = d
                        closestStrokeIndex = index
                        closestPointWorld = pathPoint.point
                    }
                }
            }
        }

        if (closestStrokeIndex != -1 && closestPointWorld != null) {
            val closestPointScreen = toScreenCoordinates(closestPointWorld!!.x, closestPointWorld!!.y)
            val screenDistance = distance(closestPointScreen, tapPointScreen)

            if (screenDistance > screenLongDimension / 16f) {
                return -1
            }

            return closestStrokeIndex
        }
        return -1
    }
}