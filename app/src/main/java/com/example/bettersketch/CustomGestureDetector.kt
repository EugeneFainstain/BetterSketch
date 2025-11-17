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
        fun onSingleTap(event: MotionEvent): Boolean
        fun onDoubleTap(event: MotionEvent): Boolean
        fun onFirstFingerDown(event: MotionEvent): Boolean
        fun onSecondFingerDown(event: MotionEvent): Boolean
        fun onSecondFingerUp(event: MotionEvent): Boolean
        fun onSomeFingerUp(event: MotionEvent): Boolean
        fun onLastFingerUp(event: MotionEvent): Boolean
        fun onSingleFingerDrag(event: MotionEvent, dx: Float, dy: Float): Boolean
        fun onTwoFingerDrag(event: MotionEvent, dx: Float, dy: Float, scale: Float, rotate: Float): Boolean
        fun onTapAndAHalf(event: MotionEvent): Boolean
    }

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

    // For Tap and a Half
    private var firstTapDownTime: Long = 0
    private var firstTapUpTime: Long = 0
    private var firstTapX: Float = 0f
    private var firstTapY: Float = 0f
    private var isPotentialTapAndAHalf: Boolean = false

    // Multi-touch state
    private var isMultiTouchActive: Boolean = false
    private var lastMultiTouchDistance = 0f
    private var lastMultiTouchAngle = 0f
    private var lastMultiTouchMidpoint = PointF()


    fun onTouchEvent(event: MotionEvent): Boolean {
        val pointerCount = event.pointerCount
        val action = event.actionMasked

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                activePointerCount = 1
                downX = event.x
                downY = event.y
                downTime = System.currentTimeMillis()
                lastMoveX = event.x
                lastMoveY = event.y
                isDragging = false
                isMultiTouchActive = false // Reset multi-touch state

                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTapTime < doubleTapTimeout) {
                    // Potential double tap or tap and a half
                    if (isPotentialTapAndAHalf && currentTime - firstTapUpTime < doubleTapTimeout) {
                        // This is the third touch of a tap-and-a-half
                        // We'll confirm it on ACTION_UP
                    } else {
                        // Potential double tap
                    }
                } else {
                    // Not a double tap or tap-and-a-half yet
                    isPotentialTapAndAHalf = false
                }

                listener.onFirstFingerDown(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                activePointerCount++
                isMultiTouchActive = true // Multi-touch is now active
                if (activePointerCount == 2) {
                    lastMultiTouchDistance = distance(event)
                    lastMultiTouchAngle = angle(event)
                    lastMultiTouchMidpoint = midpoint(event)
                    listener.onSecondFingerDown(event)
                }
                // Reset drag state for multi-touch
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastMoveX
                val dy = event.y - lastMoveY
                lastMoveX = event.x
                lastMoveY = event.y

                val totalDx = abs(event.x - downX)
                val totalDy = abs(event.y - downY)

                if (totalDx > touchSlop || totalDy > touchSlop) {
                    isDragging = true
                }

                if (isMultiTouchActive && pointerCount >= 2) {
                    val newDist = distance(event)
                    val newAngle = angle(event)
                    val currentMidpoint = midpoint(event)

                    val scale = if (lastMultiTouchDistance > 0) newDist / lastMultiTouchDistance else 1f
                    val rotate = newAngle - lastMultiTouchAngle
                    val midDx = currentMidpoint.x - lastMultiTouchMidpoint.x
                    val midDy = currentMidpoint.y - lastMultiTouchMidpoint.y

                    listener.onTwoFingerDrag(event, midDx, midDy, scale, rotate)

                    lastMultiTouchDistance = newDist
                    lastMultiTouchAngle = newAngle
                    lastMultiTouchMidpoint.set(currentMidpoint)
                } else if (!isMultiTouchActive && isDragging) {
                    listener.onSingleFingerDrag(event, dx, dy)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (activePointerCount == 2) {
                    listener.onSecondFingerUp(event)
                }
                activePointerCount--
                listener.onSomeFingerUp(event)
                if (activePointerCount < 2) { // If less than two fingers remain, multi-touch might be ending
                    isMultiTouchActive = false
                }
            }
            MotionEvent.ACTION_UP -> {
                activePointerCount = 0
                val currentTime = System.currentTimeMillis()
                val totalDx = abs(event.x - downX)
                val totalDy = abs(event.y - downY)
                val duration = currentTime - downTime

                if (isMultiTouchActive) { // Multi-touch just ended
                    isMultiTouchActive = false
                    listener.onLastFingerUp(event)
                } else if (isDragging) {
                    isDragging = false
                    listener.onLastFingerUp(event)
                } else if (totalDx < touchSlop && totalDy < touchSlop && duration < tapTimeout) {
                    // This was a tap
                    if (currentTime - lastTapTime < doubleTapTimeout) {
                        // This is the second tap of a double tap
                        if (isPotentialTapAndAHalf && currentTime - firstTapUpTime < doubleTapTimeout) {
                            // Confirmed tap-and-a-half
                            isPotentialTapAndAHalf = false
                            lastTapTime = 0 // Reset
                            listener.onTapAndAHalf(event)
                        } else {
                            // Confirmed double tap
                            lastTapTime = 0 // Reset
                            listener.onDoubleTap(event)
                        }
                    } else {
                        // This is a single tap
                        firstTapDownTime = downTime
                        firstTapUpTime = currentTime
                        firstTapX = event.x
                        firstTapY = event.y
                        isPotentialTapAndAHalf = true // Set for next potential tap-and-a-half
                        lastTapTime = currentTime // For double tap detection
                        listener.onSingleTap(event)
                    }
                } else {
                    // Not a tap, not a drag - just a release after some movement
                    listener.onLastFingerUp(event)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                activePointerCount = 0
                isDragging = false
                isPotentialTapAndAHalf = false
                lastTapTime = 0
                isMultiTouchActive = false
                listener.onLastFingerUp(event)
            }
        }
        return true
    }

    // Helper functions for multi-touch calculations
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
}
