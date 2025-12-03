package com.example.bettersketch

import android.graphics.PointF
import android.view.MotionEvent
import android.view.View

/**
 * Helper that allows a button press to augment touch gestures on a target view.
 * When the button is held down and the target view is touched, synthetic multi-touch
 * events are created with the button acting as an invisible first pointer.
 */
class ButtonAugmentedGestureHelper(private val targetView: View) {

    private data class Registration(
        val button: View,
        val gestureTag: Any
    )

    private val registrations = mutableListOf<Registration>()
    private var activeRegistration: Registration? = null
    private var buttonFingerX: Float = 0f
    private var buttonFingerY: Float = 0f
    private var gestureDownTime: Long = 0L

    /**
     * Registers a button to trigger augmented gestures.
     *
     * @param button The button that acts as the first (invisible) pointer
     * @param gestureTag A tag object to identify which button started the gesture
     *                   (check this in your target view's touch handler)
     */
    fun registerButton(button: View, gestureTag: Any) {
        val registration = Registration(button, gestureTag)
        registrations.add(registration)

        button.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activeRegistration = registration

                    // Store button coordinates in target view's coordinate space
                    val targetLocation = IntArray(2)
                    targetView.getLocationOnScreen(targetLocation)
                    buttonFingerX = event.rawX - targetLocation[0]
                    buttonFingerY = event.rawY - targetLocation[1]

                    view.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (activeRegistration == registration) {
                        // Update button finger position
                        val targetLocation = IntArray(2)
                        targetView.getLocationOnScreen(targetLocation)
                        buttonFingerX = event.rawX - targetLocation[0]
                        buttonFingerY = event.rawY - targetLocation[1]
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (activeRegistration == registration) {
                        activeRegistration = null
                        view.isPressed = false
                        true
                    } else {
                        view.isPressed = false
                        false
                    }
                }
                else -> false
            }
        }
    }

    /**
     * Returns the gesture tag for the currently active button, or null if no button is active.
     * Call this from your target view's touch listener to determine which button (if any)
     * initiated the current gesture.
     */
    val activeGestureTag: Any?
        get() = activeRegistration?.gestureTag

    /**
     * Call this from the target view's setOnTouchListener.
     * Returns true if the event was intercepted and handled (caller should consume it),
     * false if the event should be handled normally.
     */
    fun onTargetViewTouch(event: MotionEvent): Boolean {
        if (activeRegistration == null) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownTime = event.downTime

                // Create synthetic ACTION_DOWN for button finger (pointer 0)
                val syntheticDown = createSyntheticEvent(
                    downTime = gestureDownTime,
                    eventTime = event.eventTime - 10,
                    action = MotionEvent.ACTION_DOWN,
                    pointerCount = 1,
                    pointerIndex = 0,
                    x0 = buttonFingerX, y0 = buttonFingerY,
                    x1 = event.x, y1 = event.y,
                    event = event
                )

                // Create synthetic ACTION_POINTER_DOWN for view finger (pointer 1)
                val syntheticPointerDown = createSyntheticEvent(
                    downTime = gestureDownTime,
                    eventTime = event.eventTime,
                    action = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    pointerCount = 2,
                    pointerIndex = 1,
                    x0 = buttonFingerX, y0 = buttonFingerY,
                    x1 = event.x, y1 = event.y,
                    event = event
                )

                targetView.onTouchEvent(syntheticDown)
                targetView.onTouchEvent(syntheticPointerDown)

                syntheticDown.recycle()
                syntheticPointerDown.recycle()

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Forward as two-finger move
                val syntheticMove = createSyntheticEvent(
                    downTime = gestureDownTime,
                    eventTime = event.eventTime,
                    action = MotionEvent.ACTION_MOVE,
                    pointerCount = 2,
                    pointerIndex = -1,
                    x0 = buttonFingerX, y0 = buttonFingerY,
                    x1 = event.x, y1 = event.y,
                    event = event
                )

                targetView.onTouchEvent(syntheticMove)
                syntheticMove.recycle()
                return true
            }

            MotionEvent.ACTION_UP -> {
                // View finger lifted, send pointer up for pointer 1
                val syntheticPointerUp = createSyntheticEvent(
                    downTime = gestureDownTime,
                    eventTime = event.eventTime,
                    action = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    pointerCount = 2,
                    pointerIndex = 1,
                    x0 = buttonFingerX, y0 = buttonFingerY,
                    x1 = event.x, y1 = event.y,
                    event = event
                )

                targetView.onTouchEvent(syntheticPointerUp)
                syntheticPointerUp.recycle()
                return true
            }

            else -> return false
        }
    }

    private fun createSyntheticEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        pointerCount: Int,
        pointerIndex: Int,
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        event: MotionEvent
    ): MotionEvent {
        val pointerProperties = Array(pointerCount) { MotionEvent.PointerProperties() }
        val pointerCoords = Array(pointerCount) { MotionEvent.PointerCoords() }

        // Pointer 0: button finger
        pointerProperties[0].id = 0
        pointerProperties[0].toolType = MotionEvent.TOOL_TYPE_FINGER
        pointerCoords[0].x = x0
        pointerCoords[0].y = y0
        pointerCoords[0].pressure = 1f
        pointerCoords[0].size = 1f

        if (pointerCount > 1) {
            // Pointer 1: view finger
            pointerProperties[1].id = 1
            pointerProperties[1].toolType = MotionEvent.TOOL_TYPE_FINGER
            pointerCoords[1].x = x1
            pointerCoords[1].y = y1
            pointerCoords[1].pressure = event.pressure
            pointerCoords[1].size = event.size
        }

        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            pointerCount,
            pointerProperties,
            pointerCoords,
            event.metaState,
            event.buttonState,
            event.xPrecision,
            event.yPrecision,
            event.deviceId,
            event.edgeFlags,
            event.source,
            event.flags
        )
    }
}