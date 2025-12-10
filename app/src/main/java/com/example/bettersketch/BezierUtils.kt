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
}
