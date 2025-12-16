package com.example.bettersketch

import android.graphics.PointF
import com.example.bettersketch.GeometryUtils.distance
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

object BezierUtils {

    // Switch between editing modes:
    // false = original mode (finger 1 moves anchor, finger 2 moves specific control point)
    // true = alternative mode (scale/rotate control points based on two-finger gesture)
    const val USE_SCALE_ROTATE_CONTROL_EDIT = true //false

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

        val pointCount = stroke.originalPoints.size // Use same count as original (already upsampled)
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
            val p3 = stroke.bezierAnchorPoints[segIndex + 1]
            val p1 = stroke.bezierControlPoints1[segIndex]
            val p2 = stroke.bezierControlPoints2[segIndex + 1]
            val length = if (stroke.noBezierHandles) estimateBezierArcLength(p0, p1 = p0, p2 = p3, p3) else
                                                     estimateBezierArcLength(p0, p1 = p1, p2 = p2, p3)
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

        // Generate points with anchors pinned
        val interpolatedPoints = mutableListOf<PointF>()

        for (segIndex in 0 until numSegments) {
            val p0 = stroke.bezierAnchorPoints[segIndex]
            val p3 = stroke.bezierAnchorPoints[segIndex + 1]
            val p1 = stroke.bezierControlPoints1[segIndex]
            val p2 = stroke.bezierControlPoints2[segIndex + 1]

            val numPointsInSegment = pointsPerSegment[segIndex]

            // Add points for this segment (excluding the end anchor)
            for (i in 0 until numPointsInSegment) {
                val t = i.toFloat() / numPointsInSegment
                val point = if (stroke.noBezierHandles) evaluateCubicBezier(p0, p1 = p0, p2 = p3, p3, t) else
                                                        evaluateCubicBezier(p0, p1 = p1, p2 = p2, p3, t)
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
        if (stroke.bezierAnchorIndices.size != stroke.bezierAnchorPoints.size) return

        // Get the EXACT point where we want to add the anchor
        val targetPoint = stroke.pointsForDrawing[pointIndex].point

        // Find which bezier segment this point belongs to
        var segmentIndex = -1
        for (i in 0 until stroke.bezierAnchorIndices.size - 1) {
            val startIdx = stroke.bezierAnchorIndices[i]
            val endIdx = stroke.bezierAnchorIndices[i + 1]

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
        val startIdx = stroke.bezierAnchorIndices[segmentIndex]
        val endIdx = stroke.bezierAnchorIndices[segmentIndex + 1]
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
        stroke.bezierAnchorIndices.add(segmentIndex + 1, pointIndex)

        // Update control points - add the new ones from De Casteljau split
        stroke.bezierControlPoints1.add(segmentIndex + 1, PointF(p123.x, p123.y))
        stroke.bezierControlPoints2.add(segmentIndex + 1, PointF(p012.x, p012.y))

        // Update the control points of the adjacent segments
        stroke.bezierControlPoints1[segmentIndex] = PointF(p01.x, p01.y)
        stroke.bezierControlPoints2[segmentIndex + 2] = PointF(p23.x, p23.y)

        stroke.isModified = true

        // Regenerate the curve from the modified bezier data
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

    // State for tracking anchor drag - now includes accumulated rotation
    private var dragInitialState: AnchorDragState? = null

    /**
     * Data class to hold the state during an anchor drag.
     * Uses accumulated rotation to avoid atan2 discontinuities.
     */
    data class AnchorDragState(
        val anchorIndex: Int,
        // Initial lengths to neighbors (for scaling)
        val initialToPrevLength: Float?,
        val initialToNextLength: Float?,
        // Initial control point polar coordinates (relative to anchor)
        val control1Angle: Float,
        val control1Length: Float,
        val control2Angle: Float,
        val control2Length: Float,
        // Previous frame's angles to neighbors (for delta computation)
        var prevToPrevAngle: Float?,
        var prevToNextAngle: Float?,
        // Accumulated rotation (continuously updated, no clamping)
        var accumulatedRotation: Float
    )

    /**
     * Begin dragging an anchor - records initial state for proportional control point adjustment.
     */
    fun beginAnchorDrag(stroke: Stroke, anchorIndex: Int) {
        if (anchorIndex < 0 || anchorIndex >= stroke.bezierAnchorPoints.size) {
            dragInitialState = null
            return
        }

        val anchor = stroke.bezierAnchorPoints[anchorIndex]
        val numAnchors = stroke.bezierAnchorPoints.size

        // Calculate initial length and angle to previous neighbor
        var toPrevLength: Float? = null
        var toPrevAngle: Float? = null
        if (anchorIndex > 0) {
            val prevNeighbor = stroke.bezierAnchorPoints[anchorIndex - 1]
            val dx = prevNeighbor.x - anchor.x
            val dy = prevNeighbor.y - anchor.y
            toPrevAngle = atan2(dy, dx)
            toPrevLength = sqrt(dx * dx + dy * dy)
        }

        // Calculate initial length and angle to next neighbor
        var toNextLength: Float? = null
        var toNextAngle: Float? = null
        if (anchorIndex < numAnchors - 1) {
            val nextNeighbor = stroke.bezierAnchorPoints[anchorIndex + 1]
            val dx = nextNeighbor.x - anchor.x
            val dy = nextNeighbor.y - anchor.y
            toNextAngle = atan2(dy, dx)
            toNextLength = sqrt(dx * dx + dy * dy)
        }

        // Control point 1 (outgoing) - relative to anchor
        val control1 = stroke.bezierControlPoints1.getOrNull(anchorIndex) ?: anchor
        val c1dx = control1.x - anchor.x
        val c1dy = control1.y - anchor.y
        val c1Angle = atan2(c1dy, c1dx)
        val c1Length = sqrt(c1dx * c1dx + c1dy * c1dy)

        // Control point 2 (incoming) - relative to anchor
        val control2 = stroke.bezierControlPoints2.getOrNull(anchorIndex) ?: anchor
        val c2dx = control2.x - anchor.x
        val c2dy = control2.y - anchor.y
        val c2Angle = atan2(c2dy, c2dx)
        val c2Length = sqrt(c2dx * c2dx + c2dy * c2dy)

        dragInitialState = AnchorDragState(
            anchorIndex = anchorIndex,
            initialToPrevLength = toPrevLength,
            initialToNextLength = toNextLength,
            control1Angle = c1Angle,
            control1Length = c1Length,
            control2Angle = c2Angle,
            control2Length = c2Length,
            prevToPrevAngle = toPrevAngle,
            prevToNextAngle = toNextAngle,
            accumulatedRotation = 0f
        )
    }

    /**
     * End the anchor drag - clears the state.
     */
    fun endAnchorDrag() {
        dragInitialState = null
    }

    /**
     * Move a bezier anchor point and adjust its control points.
     * Uses frame-by-frame delta rotation accumulation to avoid discontinuities.
     */
    fun moveBezierAnchor(stroke: Stroke, anchorIndex: Int, dx: Float, dy: Float) {
        if (anchorIndex < 0 || anchorIndex >= stroke.bezierAnchorPoints.size) return

        val state = dragInitialState
        val numAnchors = stroke.bezierAnchorPoints.size

        // Move the anchor point itself
        stroke.bezierAnchorPoints[anchorIndex].offset(dx, dy)
        val newAnchor = stroke.bezierAnchorPoints[anchorIndex]

        // If no state recorded, just move control points with anchor (fallback)
        if (state == null || state.anchorIndex != anchorIndex) {
            if (anchorIndex < stroke.bezierControlPoints1.size) {
                stroke.bezierControlPoints1[anchorIndex].offset(dx, dy)
            }
            if (anchorIndex < stroke.bezierControlPoints2.size) {
                stroke.bezierControlPoints2[anchorIndex].offset(dx, dy)
            }
            return
        }

        // Calculate current angles and lengths to neighbors
        var currentToPrevAngle: Float? = null
        var currentToPrevLength: Float? = null
        if (anchorIndex > 0) {
            val prevNeighbor = stroke.bezierAnchorPoints[anchorIndex - 1]
            val dxN = prevNeighbor.x - newAnchor.x
            val dyN = prevNeighbor.y - newAnchor.y
            currentToPrevAngle = atan2(dyN, dxN)
            currentToPrevLength = sqrt(dxN * dxN + dyN * dyN)
        }

        var currentToNextAngle: Float? = null
        var currentToNextLength: Float? = null
        if (anchorIndex < numAnchors - 1) {
            val nextNeighbor = stroke.bezierAnchorPoints[anchorIndex + 1]
            val dxN = nextNeighbor.x - newAnchor.x
            val dyN = nextNeighbor.y - newAnchor.y
            currentToNextAngle = atan2(dyN, dxN)
            currentToNextLength = sqrt(dxN * dxN + dyN * dyN)
        }

        // Compute frame-by-frame delta rotations and accumulate
        var deltaPrev = 0f
        var deltaNext = 0f
        var hasPrev = false
        var hasNext = false

        if (currentToPrevAngle != null && state.prevToPrevAngle != null) {
            deltaPrev = smallestAngleDelta(state.prevToPrevAngle!!, currentToPrevAngle)
            hasPrev = true
        }
        if (currentToNextAngle != null && state.prevToNextAngle != null) {
            deltaNext = smallestAngleDelta(state.prevToNextAngle!!, currentToNextAngle)
            hasNext = true
        }

        // Average the deltas for this frame
        val frameDelta = when {
            hasPrev && hasNext -> (deltaPrev + deltaNext) / 2f
            hasPrev -> deltaPrev
            hasNext -> deltaNext
            else -> 0f
        }

        // Accumulate rotation
        state.accumulatedRotation += frameDelta

        // Update previous angles for next frame
        state.prevToPrevAngle = currentToPrevAngle
        state.prevToNextAngle = currentToNextAngle

        // Calculate scale factors
        val isFirstAnchor = (anchorIndex == 0)
        val isLastAnchor = (anchorIndex == numAnchors - 1)

        var prevScale = 1f
        if (state.initialToPrevLength != null && state.initialToPrevLength > 0.001f && currentToPrevLength != null) {
            prevScale = currentToPrevLength / state.initialToPrevLength
        }

        var nextScale = 1f
        if (state.initialToNextLength != null && state.initialToNextLength > 0.001f && currentToNextLength != null) {
            nextScale = currentToNextLength / state.initialToNextLength
        }

        val c1Scale = if (isLastAnchor) prevScale else nextScale
        val c2Scale = if (isFirstAnchor) nextScale else prevScale

        // Apply transformation to control points using accumulated rotation
        if (anchorIndex < stroke.bezierControlPoints1.size) {
            val newC1Angle = state.control1Angle + state.accumulatedRotation
            val newC1Length = state.control1Length * c1Scale
            stroke.bezierControlPoints1[anchorIndex].set(
                newAnchor.x + cos(newC1Angle) * newC1Length,
                newAnchor.y + sin(newC1Angle) * newC1Length
            )
        }

        if (anchorIndex < stroke.bezierControlPoints2.size) {
            val newC2Angle = state.control2Angle + state.accumulatedRotation
            val newC2Length = state.control2Length * c2Scale
            stroke.bezierControlPoints2[anchorIndex].set(
                newAnchor.x + cos(newC2Angle) * newC2Length,
                newAnchor.y + sin(newC2Angle) * newC2Length
            )
        }
    }

    /**
     * Compute the smallest signed angle delta from angle1 to angle2.
     * Result is in range (-π, π], representing the shortest rotation.
     */
    private fun smallestAngleDelta(from: Float, to: Float): Float {
        var delta = to - from
        while (delta > PI) delta -= (2 * PI).toFloat()
        while (delta <= -PI) delta += (2 * PI).toFloat()
        return delta
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
     * Alternative two-finger bezier editing using scale and rotation.
     * Instead of moving a specific control point, this rotates and scales BOTH control points
     * around the anchor based on the two-finger gesture parameters.
     * The anchor is moved according to the midpoint between the two fingers.
     *
     * @param anchorEdit The anchor point being edited
     * @param anchorDx Delta X for anchor movement
     * @param anchorDy Delta Y for anchor movement
     * @param scale Scale factor from the two-finger gesture
     * @param rotate Rotation angle (in degrees) from the two-finger gesture
     */
    fun moveBezierAnchorWithScaleRotate(
        anchorEdit: DrawingView.AnchorPointToEdit?,
        anchorDx: Float,
        anchorDy: Float,
        scale: Float,
        rotate: Float
    ) {
        if (anchorEdit == null) return
        if (!anchorEdit.isBezierAnchor) return

        val stroke = anchorEdit.stroke
        val anchorIndex = anchorEdit.pointIndex

        // Guard against invalid indices
        if (anchorIndex < 0 || anchorIndex >= stroke.bezierAnchorPoints.size) return
        if (anchorIndex >= stroke.bezierControlPoints1.size) return
        if (anchorIndex >= stroke.bezierControlPoints2.size) return

        val anchorPoint = stroke.bezierAnchorPoints[anchorIndex]
        val control1 = stroke.bezierControlPoints1[anchorIndex]
        val control2 = stroke.bezierControlPoints2[anchorIndex]

        // Step 1: Calculate original vectors from anchor to control points BEFORE anchor movement
        val originalControl1Dx = control1.x - anchorPoint.x
        val originalControl1Dy = control1.y - anchorPoint.y
        val originalControl2Dx = control2.x - anchorPoint.x
        val originalControl2Dy = control2.y - anchorPoint.y

        // Step 2: Move the anchor point
        anchorPoint.offset(anchorDx, anchorDy)

        // Step 3: Apply scale and rotation to both control points
        val rotateRad = Math.toRadians(rotate.toDouble()).toFloat()
        val cosR = kotlin.math.cos(rotateRad)
        val sinR = kotlin.math.sin(rotateRad)

        // Transform control1: scale then rotate
        val scaledControl1Dx = originalControl1Dx * scale
        val scaledControl1Dy = originalControl1Dy * scale
        val newControl1Dx = scaledControl1Dx * cosR - scaledControl1Dy * sinR
        val newControl1Dy = scaledControl1Dx * sinR + scaledControl1Dy * cosR

        // Transform control2: scale then rotate
        val scaledControl2Dx = originalControl2Dx * scale
        val scaledControl2Dy = originalControl2Dy * scale
        val newControl2Dx = scaledControl2Dx * cosR - scaledControl2Dy * sinR
        val newControl2Dy = scaledControl2Dx * sinR + scaledControl2Dy * cosR

        // Step 4: Set new control point positions relative to moved anchor
        control1.set(anchorPoint.x + newControl1Dx, anchorPoint.y + newControl1Dy)
        control2.set(anchorPoint.x + newControl2Dx, anchorPoint.y + newControl2Dy)

        stroke.isModified = true
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
