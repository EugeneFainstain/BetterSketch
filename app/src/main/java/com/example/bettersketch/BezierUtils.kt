package com.example.bettersketch

import android.graphics.PointF
import com.example.bettersketch.GeometryUtils.distance

object BezierUtils {
    /**
     * Remove a Bezier anchor point at the specified index.
     *
     * @param stroke The stroke to modify
     * @param anchorIndex Index into bezierAnchorPoints
     * @return True if the anchor was removed, false if removal was not allowed
     */
    fun removeBezierAnchorPointAtIndex(stroke: Stroke, anchorIndex: Int): Boolean {
        // Don't allow removing if it would leave fewer than 2 anchors
        if (stroke.bezierAnchorPoints.size <= 2) return false

        // Remove the anchor and refit the adjacent control points
        if (anchorIndex >= 0 && anchorIndex < stroke.bezierAnchorPoints.size) {
            removeBezierAnchorWithRefit(stroke, anchorIndex)
            stroke.isModified = true

            // Regenerate the curve from the modified bezier data
            stroke.regenerateBezierCurve()
            stroke.applySmoothing()
            return true
        }

        return false
    }

    fun addBezierAnchorPoint(stroke: Stroke, pointIndex: Int) {
        // pointIndex is an index into pointsForDrawing (the smoothed/regenerated curve)
        // We need to find which bezier segment this point falls on

        if (stroke.bezierAnchorPoints.size < 2 || pointIndex >= stroke.pointsForDrawing.size) return

        // Calculate the distance along the curve at pointIndex
        val targetDistance = stroke.pointsForDrawing[pointIndex].distance
        val totalDistance = stroke.totalDistance

        if (totalDistance <= 0f) return

        // Find which segment this distance falls into
        val numSegments = stroke.bezierAnchorPoints.size - 1
        val segmentLengths = mutableListOf<Float>()
        var totalSegmentLength = 0f

        // Estimate arc length for each segment
        for (i in 0 until numSegments) {
            val p0 = stroke.bezierAnchorPoints[i]
            val p1 = stroke.bezierControlPoints1[i]
            val p2 = stroke.bezierControlPoints2[i + 1]
            val p3 = stroke.bezierAnchorPoints[i + 1]

            // Estimate arc length by sampling
            val samples = 20
            var length = 0f
            var prevPoint = p0

            for (j in 1..samples) {
                val t = j.toFloat() / samples
                val point = GeometryUtils.evaluateCubicBezier(p0, p1, p2, p3, t)
                length += distance(point, prevPoint)
                prevPoint = point
            }

            segmentLengths.add(length)
            totalSegmentLength += length
        }

        if (totalSegmentLength <= 0f) return

        // Find which segment contains the target distance
        val targetRatio = targetDistance / totalDistance
        var accumulatedLength = 0f
        var segmentIndex = -1
        var segmentStartRatio = 0f

        for (i in segmentLengths.indices) {
            val segmentRatio = segmentLengths[i] / totalSegmentLength
            val segmentEndRatio = accumulatedLength / totalSegmentLength + segmentRatio

            if (targetRatio >= accumulatedLength / totalSegmentLength && targetRatio <= segmentEndRatio) {
                segmentIndex = i
                segmentStartRatio = accumulatedLength / totalSegmentLength
                break
            }
            accumulatedLength += segmentLengths[i]
        }

        // If we couldn't find a segment, use the closest one
        if (segmentIndex == -1) {
            segmentIndex = ((targetRatio * numSegments).toInt()).coerceIn(0, numSegments - 1)
            segmentStartRatio = segmentLengths.take(segmentIndex).sum() / totalSegmentLength
        }

        // Get the bezier segment to split
        val p0 = stroke.bezierAnchorPoints[segmentIndex]
        val p1 = stroke.bezierControlPoints1[segmentIndex]
        val p2 = stroke.bezierControlPoints2[segmentIndex + 1]
        val p3 = stroke.bezierAnchorPoints[segmentIndex + 1]

        // Calculate t parameter within the segment
        val segmentRatio = segmentLengths[segmentIndex] / totalSegmentLength
        val t = if (segmentRatio > 0f) {
            ((targetRatio - segmentStartRatio) / segmentRatio).coerceIn(0f, 1f)
        } else {
            0.5f
        }

        // Split the bezier curve at parameter t using De Casteljau's algorithm
        val p01 = GeometryUtils.lerp(p0, p1, t)
        val p12 = GeometryUtils.lerp(p1, p2, t)
        val p23 = GeometryUtils.lerp(p2, p3, t)

        val p012 = GeometryUtils.lerp(p01, p12, t)
        val p123 = GeometryUtils.lerp(p12, p23, t)

        val newAnchor = GeometryUtils.lerp(p012, p123, t)

        // Insert the new anchor at segmentIndex + 1
        stroke.bezierAnchorPoints.add(segmentIndex + 1, PointF(newAnchor.x, newAnchor.y))

        // For bezierAnchorIndices, estimate where this would be in the original points
        val newIndex = (pointIndex * stroke.originalPoints.size / stroke.pointsForDrawing.size.toFloat()).toInt()
        stroke.bezierAnchorIndices.add(segmentIndex + 1, newIndex)

        // Update control points - add the new ones
        stroke.bezierControlPoints1.add(segmentIndex + 1, PointF(p123.x, p123.y))
        stroke.bezierControlPoints2.add(segmentIndex + 1, PointF(p012.x, p012.y))

        // Update the control points of the adjacent segments
        stroke.bezierControlPoints1[segmentIndex] = PointF(p01.x, p01.y)
        stroke.bezierControlPoints2[segmentIndex + 2] = PointF(p23.x, p23.y)

        stroke.isModified = true

        // Regenerate the curve from the modified bezier data
        stroke.regenerateBezierCurve()
        stroke.applySmoothing()
    }

    /**
     * Remove a Bezier anchor and refit the adjacent control points.
     * For anchors that were added via De Casteljau splitting, this will give perfect reconstruction.
     * For arbitrary anchors, it uses a simple averaging heuristic.
     */
    fun removeBezierAnchorWithRefit(stroke: Stroke, anchorIndex: Int) {
        // Edge cases: can't remove first or last anchor point
        if (anchorIndex == 0 || anchorIndex >= stroke.bezierAnchorPoints.size - 1) {
            // Just remove the data structures without refitting
            stroke.bezierAnchorPoints.removeAt(anchorIndex)
            if (anchorIndex < stroke.bezierControlPoints1.size) {
                stroke.bezierControlPoints1.removeAt(anchorIndex)
            }
            if (anchorIndex < stroke.bezierControlPoints2.size) {
                stroke.bezierControlPoints2.removeAt(anchorIndex)
            }
            if (anchorIndex < stroke.bezierAnchorIndices.size) {
                stroke.bezierAnchorIndices.removeAt(anchorIndex)
            }
            return
        }

        // Get the two segments we're merging
        val p0 = stroke.bezierAnchorPoints[anchorIndex - 1]
        val c1Left = stroke.bezierControlPoints1[anchorIndex - 1]
        val pMid = stroke.bezierAnchorPoints[anchorIndex]
        val c2Right = stroke.bezierControlPoints2[anchorIndex + 1]
        val p3 = stroke.bezierAnchorPoints[anchorIndex + 1]

        // Try to estimate the parameter 't' at which this point was split
        // Use the ratio of distances as an approximation
        val distLeft = distance(p0, pMid)
        val distRight = distance(pMid, p3)
        val totalDist = distLeft + distRight
        val tEstimate = if (totalDist > 0f) distLeft / totalDist else 0.5f

        val t = tEstimate.coerceIn(0.1f, 0.9f) // Avoid division by zero at extremes
        val mt = 1.0f - t

        // Reverse the De Casteljau split operation
        val p1Recovered = if (t > 0.01f) {
            PointF(
                (c1Left.x - mt * p0.x) / t,
                (c1Left.y - mt * p0.y) / t
            )
        } else {
            PointF(c1Left.x, c1Left.y)
        }

        val p2Recovered = if (mt > 0.01f) {
            PointF(
                (c2Right.x - t * p3.x) / mt,
                (c2Right.y - t * p3.y) / mt
            )
        } else {
            PointF(c2Right.x, c2Right.y)
        }

        // IMPORTANT: Update control points BEFORE removing anything
        // After removal, indices will shift!
        stroke.bezierControlPoints1[anchorIndex - 1] = p1Recovered
        stroke.bezierControlPoints2[anchorIndex + 1] = p2Recovered

        // NOW remove the anchor and its associated control points
        stroke.bezierAnchorPoints.removeAt(anchorIndex)
        stroke.bezierAnchorIndices.removeAt(anchorIndex)
        stroke.bezierControlPoints1.removeAt(anchorIndex)
        stroke.bezierControlPoints2.removeAt(anchorIndex)
    }

    /**
     * Move a bezier anchor point and its associated control points.
     * 
     * @param stroke The stroke being edited
     * @param anchorIndex Index into bezierAnchorPoints
     * @param dx Delta X movement
     * @param dy Delta Y movement
     */
    fun moveBezierAnchor(stroke: Stroke, anchorIndex: Int, dx: Float, dy: Float) {
        if (anchorIndex >= 0 && anchorIndex < stroke.bezierAnchorPoints.size) {
            // Move the anchor point itself
            stroke.bezierAnchorPoints[anchorIndex].offset(dx, dy)

            // Move both control points associated with this anchor
            if (anchorIndex < stroke.bezierControlPoints1.size) {
                stroke.bezierControlPoints1[anchorIndex].offset(dx, dy)
            }
            if (anchorIndex < stroke.bezierControlPoints2.size) {
                stroke.bezierControlPoints2[anchorIndex].offset(dx, dy)
            }

            stroke.isModified = true
            // Regenerate the curve from the modified bezier data
            stroke.regenerateBezierCurve()
            stroke.applySmoothing()
        }
    }

    /**
     * Move a bezier anchor and one control point while maintaining collinearity with the opposite control.
     * Used for two-finger bezier control point editing.
     * 
     * @param stroke The stroke being edited
     * @param anchorIndex Index of the anchor being moved
     * @param controlIndex Index of the control point being dragged
     * @param isControl1 True if dragging controlPoints1, false if controlPoints2
     * @param anchorDx Delta X for anchor movement
     * @param anchorDy Delta Y for anchor movement
     * @param controlDx Delta X for control point movement
     * @param controlDy Delta Y for control point movement
     */
    fun moveBezierAnchorAndControlPoint(
        stroke: Stroke,
        anchorIndex: Int,
        controlIndex: Int,
        isControl1: Boolean,
        anchorDx: Float,
        anchorDy: Float,
        controlDx: Float,
        controlDy: Float
    ) {
        val anchorPoint = stroke.bezierAnchorPoints[anchorIndex]

        // Get references to the control points
        val primaryControl = if (isControl1) {
            stroke.bezierControlPoints1[controlIndex]
        } else {
            stroke.bezierControlPoints2[controlIndex]
        }

        val oppositeControl = if (isControl1) {
            stroke.bezierControlPoints2.getOrNull(controlIndex)
        } else {
            stroke.bezierControlPoints1.getOrNull(controlIndex)
        }

        // Store original distances from anchor before any movement
        val originalPrimaryDistance = GeometryUtils.distance(anchorPoint, primaryControl)
        val originalOppositeDistance = oppositeControl?.let { GeometryUtils.distance(anchorPoint, it) } ?: 0f

        // Move the anchor point
        anchorPoint.offset(anchorDx, anchorDy)

        // Move the primary control point (the one being dragged)
        primaryControl.offset(controlDx, controlDy)

        // Calculate the new distance and direction from anchor to primary control
        val newPrimaryDistance = GeometryUtils.distance(anchorPoint, primaryControl)
        val primaryDirX = primaryControl.x - anchorPoint.x
        val primaryDirY = primaryControl.y - anchorPoint.y

        // Update the opposite control point to maintain collinearity
        if (oppositeControl != null && newPrimaryDistance > 0f) {
            // Calculate how much the primary lever length changed
            val leverLengthChange = newPrimaryDistance - originalPrimaryDistance

            // The opposite lever should change by the same amount
            val newOppositeDistance = originalOppositeDistance + leverLengthChange

            if (newOppositeDistance > 0f) {
                // Normalize the primary direction and scale by new opposite distance
                val oppositeDirX = -(primaryDirX / newPrimaryDistance) * newOppositeDistance
                val oppositeDirY = -(primaryDirY / newPrimaryDistance) * newOppositeDistance

                // Set the opposite control point position relative to the (now moved) anchor
                oppositeControl.set(anchorPoint.x + oppositeDirX, anchorPoint.y + oppositeDirY)
            }
        }

        stroke.isModified = true
        // Regenerate the curve from the modified bezier data
        stroke.regenerateBezierCurve()
        stroke.applySmoothing()
    }

    /**
     * Find the closest control point (for the given anchor) to a tap point.
     * Returns a ControlPointToEdit describing which control point was selected, or null if none found.
     *
     * @param primaryStroke The stroke being edited
     * @param editingAnchorIndex Index of the anchor being edited
     * @param tapPoint The point to search from
     * @return ControlPointToEdit with stroke, control index, and array index (1 or 2), or null if not found
     */
    fun findClosestControlPoint(
        primaryStroke: Stroke,
        editingAnchorIndex: Int,
        tapPoint: PointF
    ): DrawingView.ControlPointToEdit? {
        if (!primaryStroke.renderAsBezier || primaryStroke.bezierControlPoints1.isEmpty() || primaryStroke.bezierControlPoints2.isEmpty())
            return null

        // Find the closest control point to the tap point
        var closestDist = Float.MAX_VALUE
        var closestIndex = -1
        var closestArrayIdx = 1

        // Check outgoing control point (controlPoints1)
        if (editingAnchorIndex < primaryStroke.bezierControlPoints1.size) {
            val control1 = primaryStroke.bezierControlPoints1[editingAnchorIndex]
            val d = distance(control1, tapPoint)
            if (d < closestDist) {
                closestDist = d
                closestIndex = editingAnchorIndex
                closestArrayIdx = 1
            }
        }

        // Check incoming control point (controlPoints2)
        if (editingAnchorIndex < primaryStroke.bezierControlPoints2.size) {
            val control2 = primaryStroke.bezierControlPoints2[editingAnchorIndex]
            val d = distance(control2, tapPoint)
            if (d < closestDist) {
                closestDist = d
                closestIndex = editingAnchorIndex
                closestArrayIdx = 2
            }
        }

        return if (closestIndex != -1) {
            DrawingView.ControlPointToEdit(primaryStroke, closestIndex, closestArrayIdx)
        } else {
            null
        }
    }
}
