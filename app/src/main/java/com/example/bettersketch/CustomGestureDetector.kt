package com.example.bettersketch

import android.content.Context
import android.graphics.PointF
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

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
        fun onTwoFingerDrag(event: MotionEvent, dx: Float, dy: Float, scale: Float, rotate: Float): Boolean
        fun onThreeFingerDrag(event: MotionEvent, dx: Float, dy: Float, scale: Float, rotate: Float): Boolean
    }

    var mainGestureHelper: ButtonAugmentedGestureHelper? = null

    private var lastKnownGestureTag: Any? = null
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private val doubleTapTimeout: Int = ViewConfiguration.getDoubleTapTimeout()
    private val tapTimeout: Int = ViewConfiguration.getTapTimeout()

    private var lastTapTime: Long = 0
    private var downTime: Long = 0
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var lastMoveX: Float = 0f
    private var lastMoveY: Float = 0f
    private var isDragging: Boolean = false
    private var activePointerCount: Int = 0

    // Multi-touch state
    private var aTwoFingerGestureHasOccured = false
    private var aThreeFingerGestureHasOccured = false
    private var lastMultiTouchDistance = 0f
    private var lastMultiTouchAngle = 0f
    private var lastMultiTouchMidpoint = PointF()
    private var lastThreeFingerCentroid = PointF()
    private var lastThreeFingerAvgDist = 0f
    private var lastThreeFingerAngle = 0f


    fun onTouchEvent(event: MotionEvent): Boolean {
        val pointerCount = event.pointerCount
        val action = event.actionMasked

        val currentGestureTag = mainGestureHelper?.activeGestureTag
        val gestureTagChanged = currentGestureTag != lastKnownGestureTag
        lastKnownGestureTag = currentGestureTag

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                activePointerCount = 1
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                lastMoveX = event.x
                lastMoveY = event.y
                isDragging = false
                aTwoFingerGestureHasOccured = false
                aThreeFingerGestureHasOccured = false

                listener.onFirstFingerDown(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointerCount++
                if (activePointerCount == 2) {
                    aTwoFingerGestureHasOccured = true
                    lastMultiTouchDistance = distance(event)
                    lastMultiTouchAngle = angle(event)
                    lastMultiTouchMidpoint = midpoint(event)
                    listener.onSecondFingerDown(event)
                } else if (activePointerCount == 3) {
                    aThreeFingerGestureHasOccured = true
                    val centroid = centroid(event)
                    lastThreeFingerCentroid = centroid
                    lastThreeFingerAvgDist = averageDistanceFromCentroid(event, centroid)
                    lastThreeFingerAngle = primaryAngle(event, centroid)
                    listener.onThirdFingerDown(event)
                }
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                var dx = event.x - lastMoveX
                var dy = event.y - lastMoveY
                lastMoveX = event.x
                lastMoveY = event.y

                if( gestureTagChanged ) { // Prevents the jump if touching the button second
                    dx = 0f
                    dy = 0f
                }

                if (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop || (System.currentTimeMillis() - downTime) > 100 ) {
                    isDragging = true
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
                else if (pointerCount >= 2 && !aThreeFingerGestureHasOccured) {
                    val newDist = distance(event)
                    val newAngle = angle(event)
                    val currentMidpoint = midpoint(event)

                    if( gestureTagChanged ) { // Prevents the jump if touching the button second
                        lastMultiTouchDistance = newDist
                        lastMultiTouchAngle = newAngle
                        lastMultiTouchMidpoint.set(currentMidpoint)
                    }

                    val scale = if (lastMultiTouchDistance > 0) newDist / lastMultiTouchDistance else 1f
                    val rotate = newAngle - lastMultiTouchAngle
                    val midDx = currentMidpoint.x - lastMultiTouchMidpoint.x
                    val midDy = currentMidpoint.y - lastMultiTouchMidpoint.y

                    listener.onTwoFingerDrag(event, midDx, midDy, scale, rotate)

                    lastMultiTouchDistance = newDist
                    lastMultiTouchAngle = newAngle
                    lastMultiTouchMidpoint.set(currentMidpoint)
                } else if (!aTwoFingerGestureHasOccured && isDragging) {
                    listener.onSingleFingerDrag(event, dx, dy)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                activePointerCount--
                listener.onSomeFingerUp(event)
            }
            MotionEvent.ACTION_UP -> {
                activePointerCount = 0
                val currentTime = System.currentTimeMillis()
                val totalDx = abs(event.x - downX)
                val totalDy = abs(event.y - downY)
                val duration = currentTime - downTime

                val isTap = totalDx < touchSlop && totalDy < touchSlop && duration < tapTimeout

                if (isTap && !aTwoFingerGestureHasOccured && !isDragging) {
                    if (currentTime - lastTapTime < doubleTapTimeout) {
                        lastTapTime = 0
                        listener.onDoubleTapEnd(event)
                    } else {
                        lastTapTime = currentTime
                        listener.onSingleTapEnd(event)
                    }
                } else {
                    aTwoFingerGestureHasOccured = false
                    aThreeFingerGestureHasOccured = false
                    isDragging = false
                    listener.onLastRemainingFingerUp(event)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                activePointerCount = 0
                isDragging = false
                lastTapTime = 0
                aTwoFingerGestureHasOccured = false
                aThreeFingerGestureHasOccured = false
                listener.onLastRemainingFingerUp(event)
            }
        }
        return true
    }

    private fun distance(p1: PointF, p2: PointF): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun distance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
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
