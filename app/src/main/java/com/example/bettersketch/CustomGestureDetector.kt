package com.example.bettersketch

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

class CustomGestureDetector(context: Context, private val listener: OnGestureListener) {

    interface OnGestureListener {
        fun onSingleTap(event: MotionEvent): Boolean
        fun onDoubleTap(event: MotionEvent): Boolean
        fun onDragStart(event: MotionEvent): Boolean
        fun onDrag(event: MotionEvent): Boolean
        fun onDragEnd(event: MotionEvent): Boolean
    }

    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private val doubleTapTimeout: Int = ViewConfiguration.getDoubleTapTimeout()

    private var lastTapTime: Long = 0
    private var downTime: Long = 0
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var isDragging: Boolean = false

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastTapTime < doubleTapTimeout) {
                    // This is a double tap
                    lastTapTime = 0 // Reset to avoid triple taps
                    return listener.onDoubleTap(event)
                }

                downX = event.x
                downY = event.y
                downTime = currentTime
                lastTapTime = currentTime
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (dx > touchSlop || dy > touchSlop) {
                    if (!isDragging) {
                        isDragging = true
                        listener.onDragStart(event)
                    }
                    return listener.onDrag(event)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    return listener.onDragEnd(event)
                } else {
                    // This was a single tap
                    return listener.onSingleTap(event)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    return listener.onDragEnd(event)
                }
            }
        }
        return true
    }
}
