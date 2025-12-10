
package com.example.bettersketch

import android.graphics.PointF

object StrokeUtils {
    /**
     * Remove an anchor point from a stroke at the specified index.
     * Handles both Bezier and Polyline modes automatically.
     *
     * @param stroke The stroke to modify
     * @param pointIndex For Bezier mode: index into bezierAnchorPoints. For Polyline mode: index into unsmoothedPoints
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
        return if (isBezierAnchor && stroke.renderAsBezier && stroke.bezierAnchorPoints.isNotEmpty()) {
            BezierUtils.removeBezierAnchorPointAtIndex(stroke, pointIndex)
        } else {
            PolylineUtils.removePolylineAnchorPointAtIndex(stroke, pointIndex, snapshotUnsmoothedPoints)
        }
    }
}