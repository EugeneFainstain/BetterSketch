package com.example.bettersketch

import android.graphics.PointF
import com.example.bettersketch.GeometryUtils.distance

object BezierUtils {

    /**
     * Evaluate a cubic Bezier curve at parameter t.
     * @param p0 Start anchor point
     * @param p1 First control point (outgoing from p0)
     * @param p2 Second control point (incoming to p3)
     * @param p3 End anchor point
     * @param t Parameter (0.0 = p0, 1.0 = p3)
     * @return Point on the curve at parameter t
     */
    fun evaluateCubicBezier(p0: PointF, p1: PointF, p2: PointF, p3: PointF, t: Float): PointF {
        val t2 = t * t
        val t3 = t2 * t
        val mt = 1.0f - t
        val mt2 = mt * mt
        val mt3 = mt2 * mt

        return PointF(
            p0.x * mt3 + 3 * p1.x * mt2 * t + 3 * p2.x * mt * t2 + p3.x * t3,
            p0.y * mt3 + 3 * p1.y * mt2 * t + 3 * p2.y * mt * t2 + p3.y * t3
        )
    }

    /**
     * Regenerate pointsForDrawing from bezier curve data
     */
    fun regenerateBezierCurve(stroke: Stroke) {
        if (!stroke.hasBezierData() || !stroke.renderAsBezier) return

        val pointCount = stroke.originalPoints.size * 4 // Quadruple the number of points to make Bezier look smoother
        if (pointCount < 2) return

        // Interpolate points along the bezier curve
        val interpolatedPoints = interpolateAlongBezierCurve(stroke, pointCount)

        // Update unsmoothed points with bezier-interpolated points
        val (pathPoints, newTotalDistance) = Stroke.calculatePathPointsWithDistances(interpolatedPoints)
        stroke.unsmoothedPoints.clear()
        stroke.unsmoothedPoints.addAll(pathPoints)
        stroke.totalDistance = newTotalDistance
    }

    /**
     * Interpolate points along the bezier curve, with anchors pinned at specific indices
     */
    private fun interpolateAlongBezierCurve(stroke: Stroke, targetPointCount: Int): List<PointF> {
        if (stroke.bezierAnchorPoints.size < 2) return emptyList()

        val numSegments = stroke.bezierAnchorPoints.size - 1
        if (numSegments < 1 ||
            stroke.bezierControlPoints1.size != stroke.bezierAnchorPoints.size ||
            stroke.bezierControlPoints2.size != stroke.bezierAnchorPoints.size) {
            return emptyList()
        }

        // First, estimate the arc length of each segment
        val segmentLengths = mutableListOf<Float>()
        var totalLength = 0f

        for (segIndex in 0 until numSegments) {
            val p0 = stroke.bezierAnchorPoints[segIndex]
            val p1 = stroke.bezierControlPoints1[segIndex]
            val p2 = stroke.bezierControlPoints2[segIndex + 1]
            val p3 = stroke.bezierAnchorPoints[segIndex + 1]

            val length = estimateBezierArcLength(p0, p1, p2, p3)
            segmentLengths.add(length)
            totalLength += length
        }

        if (totalLength <= 0f) return listOf(stroke.bezierAnchorPoints.first())

        // Allocate points to each segment proportionally to its arc length
        val pointsPerSegment = IntArray(numSegments)

        for (segIndex in 0 until numSegments) {
            val ratio = segmentLengths[segIndex] / totalLength
            val idealPointCount = (targetPointCount - 1) * ratio
            pointsPerSegment[segIndex] = idealPointCount.toInt().coerceAtLeast(1)
        }

        // Update bezierAnchorPointsForDrawingIndices - track where each anchor appears in pointsForDrawing
        stroke.bezierAnchorPointsForDrawingIndices.clear()
        var cumulativePoints = 0
        for (i in stroke.bezierAnchorPoints.indices) {
            stroke.bezierAnchorPointsForDrawingIndices.add(cumulativePoints)
            if (i < numSegments) {
                cumulativePoints += pointsPerSegment[i]
            }
        }

        // DON'T update bezierAnchorIndices here - it should stay as the original indices
        // from postProcessAfterDrawing which reference the upsampled unsmoothedPoints

        // Generate points with anchors pinned
        val interpolatedPoints = mutableListOf<PointF>()

        for (segIndex in 0 until numSegments) {
            val p0 = stroke.bezierAnchorPoints[segIndex]
            val p1 = stroke.bezierControlPoints1[segIndex]
            val p2 = stroke.bezierControlPoints2[segIndex + 1]
            val p3 = stroke.bezierAnchorPoints[segIndex + 1]

            val numPointsInSegment = pointsPerSegment[segIndex]

            // Add points for this segment (excluding the end anchor)
            for (i in 0 until numPointsInSegment) {
                val t = i.toFloat() / numPointsInSegment
                val point = evaluateCubicBezier(p0, p1, p2, p3, t)
                interpolatedPoints.add(point)
            }
        }

        // Always add the last anchor explicitly to ensure it's pinned
        interpolatedPoints.add(PointF(stroke.bezierAnchorPoints.last().x, stroke.bezierAnchorPoints.last().y))

        return interpolatedPoints
    }

    /**
     * Estimate the arc length of a cubic Bezier curve
     */
    fun estimateBezierArcLength(p0: PointF, p1: PointF, p2: PointF, p3: PointF): Float {
        // Use adaptive sampling to estimate arc length
        val samples = 20
        var length = 0f
        var prevPoint = p0

        for (i in 1..samples) {
            val t = i.toFloat() / samples
            val point = evaluateCubicBezier(p0, p1, p2, p3, t)
            val dx = point.x - prevPoint.x
            val dy = point.y - prevPoint.y
            length += kotlin.math.sqrt(dx * dx + dy * dy)
            prevPoint = point
        }

        return length
    }

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
            regenerateBezierCurve(stroke)
            stroke.applySmoothing()
            return true
        }

        return false
    }

    fun addBezierAnchorPoint(stroke: Stroke, pointIndex: Int) {
        // pointIndex is an index into pointsForDrawing (the smoothed/regenerated curve)

        if (stroke.bezierAnchorPoints.size < 2 || pointIndex >= stroke.pointsForDrawing.size) return
        if (stroke.bezierAnchorPointsForDrawingIndices.size != stroke.bezierAnchorPoints.size) return

        // Get the EXACT point where we want to add the anchor
        val targetPoint = stroke.pointsForDrawing[pointIndex].point

        // Find which bezier segment this point belongs to
        var segmentIndex = -1
        for (i in 0 until stroke.bezierAnchorPointsForDrawingIndices.size - 1) {
            val startIdx = stroke.bezierAnchorPointsForDrawingIndices[i]
            val endIdx = stroke.bezierAnchorPointsForDrawingIndices[i + 1]

            if (pointIndex >= startIdx && pointIndex <= endIdx) {
                segmentIndex = i
                break
            }
        }

        // If not found (shouldn't happen), default to middle segment
        if (segmentIndex == -1) {
            segmentIndex = stroke.bezierAnchorPoints.size / 2
        }

        // Get the bezier segment to split
        val p0 = stroke.bezierAnchorPoints[segmentIndex]
        val p1 = stroke.bezierControlPoints1[segmentIndex]
        val p2 = stroke.bezierControlPoints2[segmentIndex + 1]
        val p3 = stroke.bezierAnchorPoints[segmentIndex + 1]

        // Calculate t parameter within the segment based on position
        val startIdx = stroke.bezierAnchorPointsForDrawingIndices[segmentIndex]
        val endIdx = stroke.bezierAnchorPointsForDrawingIndices[segmentIndex + 1]
        val segmentLength = endIdx - startIdx
        val t = if (segmentLength > 0) {
            ((pointIndex - startIdx).toFloat() / segmentLength).coerceIn(0f, 1f)
        } else {
            0.5f
        }

        // Split the bezier curve at parameter t using De Casteljau's algorithm
        val p01 = GeometryUtils.lerp(p0, p1, t)
        val p12 = GeometryUtils.lerp(p1, p2, t)
        val p23 = GeometryUtils.lerp(p2, p3, t)

        val p012 = GeometryUtils.lerp(p01, p12, t)
        val p123 = GeometryUtils.lerp(p12, p23, t)

        // Use the exact target point instead of the calculated split point
        // This ensures the anchor appears exactly where the user placed it
        val newAnchor = PointF(targetPoint.x, targetPoint.y)

        // Insert the new anchor at segmentIndex + 1
        stroke.bezierAnchorPoints.add(segmentIndex + 1, newAnchor)

        // Insert the pointsForDrawing index (will be updated on next regeneration)
        stroke.bezierAnchorPointsForDrawingIndices.add(segmentIndex + 1, pointIndex)

        // Update legacy bezierAnchorIndices for compatibility
        stroke.bezierAnchorIndices.add(segmentIndex + 1, pointIndex)

        // Update control points - add the new ones from De Casteljau split
        stroke.bezierControlPoints1.add(segmentIndex + 1, PointF(p123.x, p123.y))
        stroke.bezierControlPoints2.add(segmentIndex + 1, PointF(p012.x, p012.y))

        // Update the control points of the adjacent segments
        stroke.bezierControlPoints1[segmentIndex] = PointF(p01.x, p01.y)
        stroke.bezierControlPoints2[segmentIndex + 2] = PointF(p23.x, p23.y)

        stroke.isModified = true

        // Regenerate the curve from the modified bezier data
        // This will update bezierAnchorPointsForDrawingIndices with correct values
        regenerateBezierCurve(stroke)
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

        }
    }

    /**
     * Move a bezier anchor and one control point while maintaining collinearity with the opposite control.
     * Used for two-finger bezier control point editing.
     *
     * @param controlEdit The control point being edited (nullable)
     * @param anchorEdit The anchor point being edited (nullable)
     * @param anchorDx Delta X for anchor movement
     * @param anchorDy Delta Y for anchor movement
     * @param controlDx Delta X for control point movement
     * @param controlDy Delta Y for control point movement
     */
    fun moveBezierAnchorAndControlPoint(
        controlEdit: DrawingView.ControlPointToEdit?,
        anchorEdit: DrawingView.AnchorPointToEdit?,
        anchorDx: Float,
        anchorDy: Float,
        controlDx: Float,
        controlDy: Float
    ) {
        // Validate inputs
        if (controlEdit == null || anchorEdit == null) return

        val stroke = controlEdit.stroke
        val anchorIndex = anchorEdit.pointIndex
        val controlIndex = controlEdit.controlIndex
        val isControl1 = (controlEdit.arrayIdx == 1)

        // Guard against invalid indices
        if (anchorIndex < 0 || anchorIndex >= stroke.bezierAnchorPoints.size ||
            controlIndex < 0 || controlIndex >= stroke.bezierControlPoints1.size) {
            return
        }

        val anchorPoint = stroke.bezierAnchorPoints[anchorIndex]

        // Get references to the control points
        val primaryControl  = if (isControl1) stroke.bezierControlPoints1[controlIndex] else stroke.bezierControlPoints2[controlIndex]
        val oppositeControl = if (isControl1) stroke.bezierControlPoints2[controlIndex] else stroke.bezierControlPoints1[controlIndex]

        // Step 1: Calculate original angles and distances BEFORE any movement
        val originalPrimaryDx = primaryControl.x - anchorPoint.x
        val originalPrimaryDy = primaryControl.y - anchorPoint.y
        val originalPrimaryAngle = kotlin.math.atan2(originalPrimaryDy, originalPrimaryDx)
        val originalPrimaryDistance = kotlin.math.sqrt(originalPrimaryDx * originalPrimaryDx + originalPrimaryDy * originalPrimaryDy)

        val originalOppositeDx = oppositeControl.x - anchorPoint.x
        val originalOppositeDy = oppositeControl.y - anchorPoint.y
        val originalOppositeAngle = kotlin.math.atan2(originalOppositeDy, originalOppositeDx)
        val originalOppositeDistance = kotlin.math.sqrt(originalOppositeDx * originalOppositeDx + originalOppositeDy * originalOppositeDy)

        // Step 2: Move the anchor point
        anchorPoint.offset(anchorDx, anchorDy)

        // Step 3: Move the primary control point (the one being dragged)
        val okToMoveAnchors = originalPrimaryDistance > 0.001f // Not moving a deprecated control point
        if( okToMoveAnchors )
            primaryControl.offset(controlDx, controlDy)
        else
            primaryControl.set(anchorPoint.x + originalPrimaryDx, anchorPoint.y + originalPrimaryDy) // Move the control point synchronously with the anchor

        // Step 4: Calculate new angle and distance for primary control point
        val newPrimaryDx = primaryControl.x - anchorPoint.x
        val newPrimaryDy = primaryControl.y - anchorPoint.y
        val newPrimaryAngle = kotlin.math.atan2(newPrimaryDy, newPrimaryDx)
        val newPrimaryDistance = kotlin.math.sqrt(newPrimaryDx * newPrimaryDx + newPrimaryDy * newPrimaryDy)

        // Step 5: Update the opposite control point by rotating and scaling proportionally
        // Calculate the angle change
        val angleDelta = newPrimaryAngle - originalPrimaryAngle

        // Calculate the new angle for the opposite control (rotate by the same amount)
        val newOppositeAngle = originalOppositeAngle + angleDelta

        // Calculate the new distance for the opposite control (scale proportionally)
        val lengthRatio = newPrimaryDistance / kotlin.math.max(0.001f, originalPrimaryDistance)
        val newOppositeDistance = originalOppositeDistance * lengthRatio

        // Set the opposite control point position using the new angle and distance
        val newOppositeDx = kotlin.math.cos(newOppositeAngle) * newOppositeDistance
        val newOppositeDy = kotlin.math.sin(newOppositeAngle) * newOppositeDistance

        // Step 6: Move the opposing control point
        if( okToMoveAnchors )
            oppositeControl.set(anchorPoint.x + newOppositeDx, anchorPoint.y + newOppositeDy)
        else
            oppositeControl.set(anchorPoint.x + originalOppositeDx, anchorPoint.y + originalOppositeDy) // Move the control point synchronously with the anchor

        stroke.isModified = true
        // Regenerate the curve from the modified bezier data
        regenerateBezierCurve(stroke)
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
