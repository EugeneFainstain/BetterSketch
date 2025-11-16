package com.example.bettersketch

import android.view.MotionEvent

class CustomGestureDetector(private val listener: OnGestureListener) {

    interface OnGestureListener {
        fun onSingleTap(event: MotionEvent): Boolean
        fun onDoubleTap(event: MotionEvent): Boolean
        fun onDrag(event: MotionEvent): Boolean
        fun onDragEnd(event: MotionEvent): Boolean
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        // TODO: Implement custom gesture detection logic here
        return false
    }
}
