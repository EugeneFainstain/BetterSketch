package com.example.bettersketch

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), DrawingViewListener, ShapeDetectionListener {

    private lateinit var drawingView: DrawingView
    private lateinit var widthSlider: WidthSlider
    private lateinit var colorSlider: ColorSlider
    private lateinit var smoothingSlider: SmoothingSlider
    private lateinit var btnUndoStrokeEdit: ImageButton
    private lateinit var btnDuplicateStroke: Button
    private lateinit var btnGroupStrokes: Button
    private lateinit var btnUnGroupStrokes: Button
    private lateinit var btnShape: Button
    private lateinit var btnPolyline: Button
    private lateinit var btnDel: ImageButton
    private lateinit var btnMoveStroke: ImageButton
    
    private var moveStrokeGestureInProgress = false
    private var moveStrokeButtonFingerX = 0f
    private var moveStrokeButtonFingerY = 0f
    private var moveStrokeGestureFingersDownTime = 0L

    private val colors = intArrayOf(
        Color.BLACK,
        Color.parseColor("#FF0000"), // Red
        Color.parseColor("#FF8000"), // Orange
        Color.parseColor("#FAFA00"), // Yellow
        Color.parseColor("#80FF00"), // Chartreuse
        Color.parseColor("#00FF00"), // Green
        Color.parseColor("#00FF80"), // Spring Green
        Color.parseColor("#00FFFF"), // Cyan
        Color.parseColor("#0080FF"), // Azure
        Color.parseColor("#0000FF"), // Blue
        Color.parseColor("#8000FF"), // Violet
        Color.parseColor("#FF00FF"), // Magenta
        Color.parseColor("#FF0080"),  // Rose
        Color.WHITE
    )

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawingView = findViewById(R.id.drawingView)
        drawingView.listener = this
        drawingView.shapeDetectionListener = this

        widthSlider = findViewById(R.id.widthSlider)
        colorSlider = findViewById(R.id.colorSlider)
        smoothingSlider = findViewById(R.id.smoothingSlider)
        btnUndoStrokeEdit = findViewById(R.id.btnUndoStrokeEdit)
        btnDuplicateStroke = findViewById(R.id.btnDuplicateStroke)
        btnGroupStrokes = findViewById(R.id.btnGroupStrokes)
        btnUnGroupStrokes = findViewById(R.id.btnUnGroupStrokes)
        btnShape = findViewById(R.id.btnShape)
        btnPolyline = findViewById(R.id.btnPolyline)
        btnDel = findViewById(R.id.btnDel)
        btnMoveStroke = findViewById(R.id.btnMoveStroke)

        setupSliderListeners()
        
        // Set up touch listener on btnMoveStroke
        btnMoveStroke.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // First finger touched the move button - just track it
                    moveStrokeGestureInProgress = true
                    
                    // Store button coordinates in DrawingView's coordinate space
                    val drawingViewLocation = IntArray(2)
                    drawingView.getLocationOnScreen(drawingViewLocation)
                    moveStrokeButtonFingerX = event.rawX - drawingViewLocation[0]
                    moveStrokeButtonFingerY = event.rawY - drawingViewLocation[1]

                    // Provide visual feedback
                    view.isPressed = true
                    
                    true // Consume the event
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (moveStrokeGestureInProgress) {
                        // Update button finger position
                        val drawingViewLocation = IntArray(2)
                        drawingView.getLocationOnScreen(drawingViewLocation)
                        moveStrokeButtonFingerX = event.rawX - drawingViewLocation[0]
                        moveStrokeButtonFingerY = event.rawY - drawingViewLocation[1]

                        true // Consume the event
                    } else {
                        false
                    }
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    if (moveStrokeGestureInProgress) {
                        // Reset flags
                        moveStrokeGestureInProgress = false
                        view.isPressed = false
                        
                        true // Consume the event
                    } else {
                        view.isPressed = false
                        false
                    }
                }
                else -> false
            }
        }

        // Set up touch listener on DrawingView to handle button finger integration
        drawingView.setOnTouchListener { v, event ->
            if (moveStrokeGestureInProgress) {
                // Button finger is down, intercept and augment the event
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        // Inject button finger as pointer 0, view finger as pointer 1
                        val pointerProperties = Array(2) { MotionEvent.PointerProperties() }
                        val pointerCoords = Array(2) { MotionEvent.PointerCoords() }

                        // Remember the downTime - it will be needed in move and up events
                        moveStrokeGestureFingersDownTime = event.downTime

                        // Pointer 0: The button finger (injected)
                        pointerProperties[0].id = 0
                        pointerProperties[0].toolType = MotionEvent.TOOL_TYPE_FINGER
                        pointerCoords[0].x = moveStrokeButtonFingerX
                        pointerCoords[0].y = moveStrokeButtonFingerY
                        pointerCoords[0].pressure = 1f
                        pointerCoords[0].size = 1f
                        
                        // Pointer 1: The new finger in DrawingView
                        pointerProperties[1].id = 1
                        pointerProperties[1].toolType = MotionEvent.TOOL_TYPE_FINGER
                        pointerCoords[1].x = event.x
                        pointerCoords[1].y = event.y
                        pointerCoords[1].pressure = event.pressure
                        pointerCoords[1].size = event.size
                        
                        // Create a synthetic ACTION_DOWN for pointer 0
                        val syntheticDown = MotionEvent.obtain(
                            moveStrokeGestureFingersDownTime,
                            event.eventTime - 10, // Slightly before the real event
                            MotionEvent.ACTION_DOWN,
                            1,
                            arrayOf(pointerProperties[0]),
                            arrayOf(pointerCoords[0]),
                            event.metaState,
                            event.buttonState,
                            event.xPrecision,
                            event.yPrecision,
                            event.deviceId,
                            event.edgeFlags,
                            event.source,
                            event.flags
                        )
                        
                        // Create a synthetic ACTION_POINTER_DOWN for pointer 1
                        val syntheticPointerDown = MotionEvent.obtain(
                            moveStrokeGestureFingersDownTime,
                            event.eventTime,
                            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                            2,
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
                        
                        // Call onTouchEvent directly to avoid recursion
                        drawingView.onTouchEvent(syntheticDown) // Simulate putting down the first finger
                        drawingView.onTouchEvent(syntheticPointerDown) // Simulate putting down the second finger
                        
                        syntheticDown.recycle()
                        syntheticPointerDown.recycle()
                        
                        true // Consume the original ACTION_DOWN
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // Forward as two-finger move
                        val currentButtonX = moveStrokeButtonFingerX
                        val currentButtonY = moveStrokeButtonFingerY
                        
                        val pointerProperties = Array(2) { MotionEvent.PointerProperties() }
                        val pointerCoords = Array(2) { MotionEvent.PointerCoords() }
                        
                        pointerProperties[0].id = 0
                        pointerProperties[0].toolType = MotionEvent.TOOL_TYPE_FINGER
                        pointerCoords[0].x = currentButtonX
                        pointerCoords[0].y = currentButtonY
                        pointerCoords[0].pressure = 1f
                        pointerCoords[0].size = 1f
                        
                        pointerProperties[1].id = 1
                        pointerProperties[1].toolType = MotionEvent.TOOL_TYPE_FINGER
                        pointerCoords[1].x = event.x
                        pointerCoords[1].y = event.y
                        pointerCoords[1].pressure = event.pressure
                        pointerCoords[1].size = event.size
                        
                        val syntheticMove = MotionEvent.obtain(
                            moveStrokeGestureFingersDownTime,
                            event.eventTime,
                            MotionEvent.ACTION_MOVE,
                            2,
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
                        
                        // Call onTouchEvent directly to avoid recursion
                        drawingView.onTouchEvent(syntheticMove)
                        syntheticMove.recycle()
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        // View finger lifted, send pointer up for pointer 1
                        val pointerProperties = Array(2) { MotionEvent.PointerProperties() }
                        val pointerCoords = Array(2) { MotionEvent.PointerCoords() }
                        
                        pointerProperties[0].id = 0
                        pointerProperties[0].toolType = MotionEvent.TOOL_TYPE_FINGER
                        pointerCoords[0].x = moveStrokeButtonFingerX
                        pointerCoords[0].y = moveStrokeButtonFingerY
                        pointerCoords[0].pressure = 1f
                        pointerCoords[0].size = 1f
                        
                        pointerProperties[1].id = 1
                        pointerProperties[1].toolType = MotionEvent.TOOL_TYPE_FINGER
                        pointerCoords[1].x = event.x
                        pointerCoords[1].y = event.y
                        pointerCoords[1].pressure = event.pressure
                        pointerCoords[1].size = event.size
                        
                        val syntheticPointerUp = MotionEvent.obtain(
                            moveStrokeGestureFingersDownTime,
                            event.eventTime,
                            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                            2,
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
                        
                        // Call onTouchEvent directly to avoid recursion
                        drawingView.onTouchEvent(syntheticPointerUp)
                        syntheticPointerUp.recycle()
                        true
                    }
                    else -> false
                }
            } else {
                false // Let DrawingView handle normally
            }
        }

        btnDel.setOnClickListener {
            drawingView.deleteStrokes()
        }

        btnUndoStrokeEdit.setOnClickListener {
            drawingView.undoStrokeModifications()
        }

        btnDuplicateStroke.setOnClickListener {
            drawingView.duplicateCurrentStroke()
        }

        btnGroupStrokes.setOnClickListener {
            drawingView.groupSelectedStrokes()
        }


        btnUnGroupStrokes.setOnClickListener {
            drawingView.ungroupSelectedStrokes()
        }

        btnPolyline.setOnClickListener {
            drawingView.toggleCurrentStrokePolyline()
        }

        drawingView.post { updateUi() }
    }

    private fun setupSliderListeners() {
        widthSlider.listener = object : MySlider.OnSliderValueChangedListener {
            override fun onValueChanged(value: Float) {
                val strokeWidth = 2f + value * 30f // 2...32
                drawingView.setStrokeWidth(strokeWidth, applyToSelected = drawingView.isStrokeSelected)
            }

            override fun onValueEdit(value: Float) {
                val strokeWidth = 2f + value * 30f // 2...32
                drawingView.setStrokeWidth(strokeWidth, applyToSelected = true)
            }

            override fun onValueEditEnd() {}
        }

        colorSlider.colors = colors
        colorSlider.listener = object : MySlider.OnSliderValueChangedListener {
            override fun onValueChanged(value: Float) {
                val colorIndex = (value * (colors.size - 1)).roundToInt()
                val color = colors[colorIndex]
                drawingView.setColor(color, applyToSelected = drawingView.isStrokeSelected)
                widthSlider.color = color
            }

            override fun onValueEdit(value: Float) {
                val colorIndex = (value * (colors.size - 1)).roundToInt()
                val color = colors[colorIndex]
                drawingView.setColor(color, applyToSelected = true)
                widthSlider.color = color
            }

            override fun onValueEditEnd() {}
        }

        smoothingSlider.listener = object : MySlider.OnSliderValueChangedListener {
            override fun onValueChanged(value: Float) {
                drawingView.setStrokeSmoothness((value * 100).roundToInt())
            }



            override fun onValueEdit(value: Float) {
                drawingView.setStrokeSmoothness((value * 100).roundToInt())
            }

            override fun onValueEditEnd() {}
        }
    }

    override fun onStateChanged() {
        updateUi()
    }

    override fun onShapeDetected(shapeFitResult: ShapeFitResult, polylineFit: PolyLineFitter.FitResult?) {
        val (percentage, shapeName, fittedStroke, error) = when (shapeFitResult) {
            is ShapeFitResult.Square -> {
                val p = (1.0f - shapeFitResult.fitResult.normalizedError) * 100
                Quad(p, "Square", shapeFitResult.fitResult.fittedStroke, shapeFitResult.fitResult.normalizedError)
            }
            is ShapeFitResult.Circle -> {
                val p = (1.0f - shapeFitResult.fitResult.normalizedError) * 100
                Quad(p, "Circle", shapeFitResult.fitResult.fittedStroke, shapeFitResult.fitResult.normalizedError)
            }
            is ShapeFitResult.Polynomial -> {
                val p = (1.0f - shapeFitResult.fitResult.normalizedError) * 100
                Quad(p, "Poly(${shapeFitResult.fitResult.degree})", shapeFitResult.fitResult.fittedStroke, shapeFitResult.fitResult.normalizedError)
            }
        }

        val fitErrorThreshold = 0.5f

        // Handle shape fit button
        if (error > fitErrorThreshold) {
            btnShape.visibility = View.GONE
        } else {
            btnShape.text = "${String.format("%.0f", percentage)}% $shapeName"
            btnShape.visibility = View.VISIBLE
            btnShape.setOnClickListener {
                drawingView.replaceWithShape(shapeFitResult.strokeToReplace, fittedStroke)
                btnShape.visibility = View.GONE
                btnPolyline.visibility = View.GONE
            }
        }

        updateShowPolylineButton() // Handle polyline fit button
    }

    private fun updateShowPolylineButton()  {

        val highlightedStrokeCount = drawingView.getHighlightedStrokeCount()
        val isCurrentStrokeGroup = drawingView.isCurrentStrokeGroup()
        val strokeHasPolylineData = drawingView.currentStrokeHasPolylineData()

        if( highlightedStrokeCount <= 1       &&
            !isCurrentStrokeGroup             &&
            strokeHasPolylineData             &&
            drawingView.currentStroke != null &&
            drawingView.currentStroke?.polylineIndices != null) {
            if(drawingView.currentStroke?.renderAsPolyline ?: false)
                btnPolyline.text = "Restore"
            else
                btnPolyline.text = "PolyLine(${drawingView.currentStroke?.polylineIndices?.size})"
            btnPolyline.visibility = View.VISIBLE
        } else {
            btnPolyline.visibility = View.GONE
        }
    }

    override fun onNoShapeDetected() {
        btnShape.visibility = View.GONE
        btnPolyline.visibility = View.GONE
    }

    private fun updateUi() {
        val currentPaint = drawingView.currentPaint
        val widthValue = (currentPaint.strokeWidth - 2f) / 30f
        widthSlider.value = widthValue

        val colorIndex = colors.indexOf(currentPaint.color)
        if (colorIndex != -1) {
            colorSlider.value = colorIndex.toFloat() / (colors.size - 1)
        }

        widthSlider.color = currentPaint.color

        val isEditing = drawingView.isEditing()
        val highlightedStrokeCount = drawingView.getHighlightedStrokeCount()
        val isCurrentStrokeGroup = drawingView.isCurrentStrokeGroup()

        btnUndoStrokeEdit.visibility = if (isEditing) View.VISIBLE else View.GONE
        btnUndoStrokeEdit.isEnabled = drawingView.isCurrentStrokeModified()
        btnDuplicateStroke.visibility = if (isEditing) View.VISIBLE else View.GONE
        btnDuplicateStroke.isEnabled = highlightedStrokeCount == 1

        // Show Group button if more than 1 stroke is highlighted AND the current stroke is NOT a group
        btnGroupStrokes.visibility = if (highlightedStrokeCount > 1 && !isCurrentStrokeGroup) View.VISIBLE else View.GONE
        // Show UnGroup button if exactly 1 stroke is highlighted AND that stroke IS a group
        btnUnGroupStrokes.visibility = if (highlightedStrokeCount == 1 && isCurrentStrokeGroup) View.VISIBLE else View.GONE

        // Show polyline button if exactly 1 non-group stroke is highlighted and it has polyline data
        updateShowPolylineButton()

        if (highlightedStrokeCount != 1) {
            btnShape.visibility = View.GONE
        }

        // Show DEL button if:
        // 1. There are highlighted strokes, OR
        // 2. selectedStrokeIdx == strokes.lastIndex (but only if there are strokes)
        val shouldShowDel = highlightedStrokeCount > 0 || 
                           (drawingView.selectedStrokeIdx == drawingView.strokes.lastIndex && 
                            drawingView.strokes.isNotEmpty())
        btnDel.visibility = if (shouldShowDel) View.VISIBLE else View.GONE

        btnMoveStroke.visibility = View.VISIBLE
        btnMoveStroke.isEnabled = shouldShowDel // Same logic as for the DEL button
    }
}

data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)