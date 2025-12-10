package com.example.bettersketch

import android.content.Context
import android.graphics.PointF
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.example.bettersketch.GeometryUtils.distance
import kotlin.math.abs
import kotlin.math.atan2

class CustomGestureDetector(context: Context, private val listener: OnGestureListener) {

    interface OnGestureListener {
        fun onSingleTapEnd(event: MotionEvent): Boolean
        fun onDoubleTapEnd(event: MotionEvent): Boolean
        fun onFirstFingerDown(event: MotionEvent): Boolean
        fun onSecondFingerDown(event: MotionEvent): Boolean
        fun onThirdFingerDown(event: MotionEvent): Boolean
        fun onSomeFingerUp(event: MotionEvent): Boolean
        fun onLastRemainingFingerUp(event: MotionEvent): Boolean
        fun onSingleFingerDrag(event: MotionEvent, dx: Float, dy: Float): Boolean
        fun onTwoFingerDrag(event: MotionEvent, dx0: Float, dy0: Float, dx1: Float, dy1: Float, scale: Float, rotate: Float): Boolean
        fun onThreeFingerDrag(event: MotionEvent, dx: Float, dy: Float, scale: Float, rotate: Float): Boolean
    }

    var mainGestureHelper: ButtonAugmentedGestureHelper? = null

    private var previousEvent: MotionEvent? = null   // Stores the previous event for delta calculations
    private var lastKnownGestureTag: Any? = null
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private val doubleTapTimeout: Int = ViewConfiguration.getDoubleTapTimeout()
    private val tapTimeout: Int = ViewConfiguration.getTapTimeout()

    private var lastTapTime: Long = 0
    private var firstFingerDownTime: Long = 0
    private var firstFingerDownX: Float = 0f
    private var firstFingerDownY: Float = 0f
    private var lastMoveX: Float = 0f
    private var lastMoveY: Float = 0f
    private var isSingleFingerDragging: Boolean = false
    private var activePointerCount: Int = 0

    // Multi-touch state
    private var lastMultiTouchDistance = 0f
    private var lastMultiTouchAngle = 0f
    private var lastMultiTouchMidpoint = PointF()
    private var lastThreeFingerCentroid = PointF()
    private var lastThreeFingerAvgDist = 0f
    private var lastThreeFingerAngle = 0f

    fun onTouchEvent(event: MotionEvent): Boolean {
        val pointerCount = event.pointerCount
        val action = event.actionMasked

        if (previousEvent == null) // Needed only for the first event
            previousEvent = MotionEvent.obtain(event) // create a copy

        val currentGestureTag = mainGestureHelper?.activeGestureTag
        val gestureTagChanged = currentGestureTag != lastKnownGestureTag
        lastKnownGestureTag = currentGestureTag

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                activePointerCount = 1
                firstFingerDownX = event.x
                firstFingerDownY = event.y
                firstFingerDownTime = System.currentTimeMillis()
                lastMoveX = event.x
                lastMoveY = event.y
                isSingleFingerDragging = false

                listener.onFirstFingerDown(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointerCount++
                if (activePointerCount == 2) {
                    lastMultiTouchDistance = distance(event)
                    lastMultiTouchAngle = angle(event)
                    lastMultiTouchMidpoint = midpoint(event)
                    listener.onSecondFingerDown(event)
                } else if (activePointerCount == 3) {
                    val centroid = centroid(event)
                    lastThreeFingerCentroid = centroid
                    lastThreeFingerAvgDist = averageDistanceFromCentroid(event, centroid)
                    lastThreeFingerAngle = primaryAngle(event, centroid)
                    listener.onThirdFingerDown(event)
                }
                isSingleFingerDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                var dx = event.x - lastMoveX
                var dy = event.y - lastMoveY
                lastMoveX = event.x
                lastMoveY = event.y

                if (gestureTagChanged) { // Prevents the jump if touching the button second
                    dx = 0f
                    dy = 0f
                }

                // If only 1 finger is moving, and it moved far since the landing,
                // or timeout expired since the landing ==> this is a single-finger drag
                if (activePointerCount == 1)
                    if (abs(event.x - firstFingerDownX) > touchSlop ||
                        abs(event.y - firstFingerDownY) > touchSlop ||
                        (System.currentTimeMillis() - firstFingerDownTime) > 100
                    ) {
                        isSingleFingerDragging = true
                    }

                if (pointerCount >= 3) {
                    val currentCentroid = centroid(event)
                    val cdx = currentCentroid.x - lastThreeFingerCentroid.x
                    val cdy = currentCentroid.y - lastThreeFingerCentroid.y

                    val newAvgDist = averageDistanceFromCentroid(event, currentCentroid)
                    val newAngle = primaryAngle(event, currentCentroid)
                    val scale = if (lastThreeFingerAvgDist > 0) newAvgDist / lastThreeFingerAvgDist else 1f
                    val rotate = newAngle - lastThreeFingerAngle

                    listener.onThreeFingerDrag(event, cdx, cdy, scale, rotate)

                    lastThreeFingerCentroid.set(currentCentroid)
                    lastThreeFingerAvgDist = newAvgDist
                    lastThreeFingerAngle = newAngle
                }
                else if (pointerCount == 2) {
                    val newDist = distance(event)
                    val newAngle = angle(event)
                    val currentMidpoint = midpoint(event)

                    if (gestureTagChanged) { // Prevents the jump if touching the button second
                        lastMultiTouchDistance = newDist
                        lastMultiTouchAngle = newAngle
                        lastMultiTouchMidpoint.set(currentMidpoint)
                    }

                    val scale = if (lastMultiTouchDistance > 0) newDist / lastMultiTouchDistance else 1f
                    val rotate = newAngle - lastMultiTouchAngle

                    // Calculate per-finger deltas using stored event
                    val dx0 = event.getX(0) - previousEvent!!.getX(0)
                    val dy0 = event.getY(0) - previousEvent!!.getY(0)
                    val dx1 = event.getX(1) - previousEvent!!.getX(1)
                    val dy1 = event.getY(1) - previousEvent!!.getY(1)

                    listener.onTwoFingerDrag(event, dx0, dy0, dx1, dy1, scale, rotate)

                    lastMultiTouchDistance = newDist
                    lastMultiTouchAngle = newAngle
                    lastMultiTouchMidpoint.set(currentMidpoint)

                } else if (pointerCount == 1) {
                    if (isSingleFingerDragging) {
                        listener.onSingleFingerDrag(event, dx, dy)
                    }
                } else {
                    // Shouldn't happen
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                activePointerCount--
                listener.onSomeFingerUp(event)

                // Seamless transition: when going from 3 to 2 fingers, reinitialize 2-finger state
                if (activePointerCount == 2 && event.pointerCount == 3) {
                    // Need to reinitialize 2-finger tracking with the remaining 2 fingers
                    // The event still has 3 pointers, but we need to find which 2 remain
                    val upPointerIndex = event.actionIndex

                    // Get indices of the two remaining fingers (not the one that's lifting)
                    val remainingIndices = mutableListOf<Int>()
                    for (i in 0 until event.pointerCount) {
                        if (i != upPointerIndex) {
                            remainingIndices.add(i)
                        }
                    }

                    if (remainingIndices.size == 2) {
                        val idx0 = remainingIndices[0]
                        val idx1 = remainingIndices[1]

                        val p0 = PointF(event.getX(idx0), event.getY(idx0))
                        val p1 = PointF(event.getX(idx1), event.getY(idx1))
                        lastMultiTouchDistance = distance(p0, p1)
                        lastMultiTouchAngle = Math.toDegrees(atan2((p0.y - p1.y).toDouble(), (p0.x - p1.x).toDouble()).toDouble()).toFloat()
                        lastMultiTouchMidpoint.x = (event.getX(idx0) + event.getX(idx1)) / 2f
                        lastMultiTouchMidpoint.y = (event.getY(idx0) + event.getY(idx1)) / 2f
                    }
                }

                // Seamless transition: when going from 2 to 1 finger, reinitialize 1-finger state
                if (activePointerCount == 1 && event.pointerCount == 2) {
                    // The event still has 2 pointers, but we need to find which one remains
                    val upPointerIndex = event.actionIndex
                    val remainingIndex = if (upPointerIndex == 0) 1 else 0

                    lastMoveX = event.getX(remainingIndex)
                    lastMoveY = event.getY(remainingIndex)
                }
            }
            MotionEvent.ACTION_UP -> {
                activePointerCount = 0
                val currentTime = System.currentTimeMillis()
                val totalDx = abs(event.x - firstFingerDownX)
                val totalDy = abs(event.y - firstFingerDownY)
                val duration = currentTime - firstFingerDownTime

                val isTap = totalDx < touchSlop && totalDy < touchSlop && duration < tapTimeout

                if (isTap && !isSingleFingerDragging) {
                    if (currentTime - lastTapTime < doubleTapTimeout) {
                        lastTapTime = 0
                        listener.onDoubleTapEnd(event)
                    } else {
                        lastTapTime = currentTime
                        listener.onSingleTapEnd(event)
                    }
                } else {
                    isSingleFingerDragging = false
                    listener.onLastRemainingFingerUp(event)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                activePointerCount = 0
                isSingleFingerDragging = false
                lastTapTime = 0
                listener.onLastRemainingFingerUp(event)
            }
        }

       // Update stored event
        previousEvent?.recycle()
        previousEvent = MotionEvent.obtain(event)

        return true
    }

    private fun distance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val p0 = PointF(event.getX(0), event.getY(0))
        val p1 = PointF(event.getX(1), event.getY(1))
        return distance(p0, p1)
    }

    private fun angle(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()).toDouble()).toFloat()
    }

    private fun midpoint(event: MotionEvent): PointF {
        val x = (event.getX(0) + event.getX(1)) / 2f
        val y = (event.getY(0) + event.getY(1)) / 2f
        return PointF(x,y)
    }

    private fun centroid(event: MotionEvent): PointF {
        if (event.pointerCount < 3) return midpoint(event)
        val x = (event.getX(0) + event.getX(1) + event.getX(2)) / 3f
        val y = (event.getY(0) + event.getY(1) + event.getY(2)) / 3f
        return PointF(x, y)
    }

    private fun averageDistanceFromCentroid(event: MotionEvent, centroid: PointF): Float {
        if (event.pointerCount < 3) return 0f
        val d0 = distance(PointF(event.getX(0), event.getY(0)), centroid)
        val d1 = distance(PointF(event.getX(1), event.getY(1)), centroid)
        val d2 = distance(PointF(event.getX(2), event.getY(2)), centroid)
        return (d0 + d1 + d2) / 3f
    }

    private fun primaryAngle(event: MotionEvent, centroid: PointF): Float {
        if (event.pointerCount < 1) return 0f
        val dx = event.getX(0) - centroid.x
        val dy = event.getY(0) - centroid.y
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()).toDouble()).toFloat()
    }
}