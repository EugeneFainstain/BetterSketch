package com.example.bettersketch

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var btnBezier: Button
    private lateinit var btnDel: ImageButton
    private lateinit var btnSelection: ImageButton
    private lateinit var btnRemoveAnchorPoint: ImageButton
    private lateinit var btnAddAnchorPoint: ImageButton
    private lateinit var gestureHelper: ButtonAugmentedGestureHelper
    
    // Gesture tags
    public object tagAddAnchorPointGesture
    public object tagSelectionGesture

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
        btnBezier = findViewById(R.id.btnBezier)
        btnDel = findViewById(R.id.btnDel)
        btnSelection = findViewById(R.id.btnSelection)
        btnRemoveAnchorPoint = findViewById(R.id.btnRemoveAnchorPoint)
        btnAddAnchorPoint = findViewById(R.id.btnAddAnchorPoint)

        setupSliderListeners()
        
        // Set up button-augmented gesture system
        gestureHelper = ButtonAugmentedGestureHelper(drawingView)
        gestureHelper.registerButton(btnSelection, tagSelectionGesture)
        gestureHelper.registerButton(btnAddAnchorPoint, tagAddAnchorPointGesture)
        drawingView.mainGestureHelper = gestureHelper

        // Set up touch listener on DrawingView
        drawingView.setOnTouchListener { _, event ->
            gestureHelper.onTargetViewTouch(event)
        }

        btnDel.setOnClickListener {
            drawingView.deleteStrokes()
        }

        btnUndoStrokeEdit.setOnClickListener {
            drawingView.undoModificationsForHighlightedStrokes()
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

        btnBezier.setOnClickListener {
            drawingView.toggleCurrentStrokeBezier()
        }

        drawingView.post { updateUi() }
    }

    private fun setupSliderListeners() {
        widthSlider.listener = object : MySlider.OnSliderValueChangedListener {
            override fun onValueChanged(value: Float) {
                val strokeWidth = 2f + value * 30f // 2...32
                drawingView.setStrokeWidth(strokeWidth, applyToSelected = drawingView.getHighlightedStrokeCount() > 0)
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
                drawingView.setColor(color, applyToSelected = drawingView.getHighlightedStrokeCount() > 0)
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
        updateShowBezierButton() // Handle bezier fit button
    }

    private fun updateShowPolylineButton()  {

        val highlightedStrokeCount = drawingView.getHighlightedStrokeCount()
        val isCurrentStrokeGroup = drawingView.isCurrentStrokeGroup()
        val strokeHasPolylineData = drawingView.currentStrokeHasPolylineData()

        if( highlightedStrokeCount <= 1       &&
            !isCurrentStrokeGroup             &&
            strokeHasPolylineData             &&
            drawingView.singleHighlightedStroke != null &&
            drawingView.singleHighlightedStroke?.polylineIndices != null) {
            if(drawingView.singleHighlightedStroke?.renderAsPolyline ?: false)
                btnPolyline.text = "Restore"
            else
                btnPolyline.text = "PolyLine(${drawingView.singleHighlightedStroke?.polylineIndices?.size})"
            btnPolyline.visibility = View.VISIBLE
        } else {
            btnPolyline.visibility = View.GONE
        }
    }

    private fun updateShowBezierButton() {
        val highlightedStrokeCount = drawingView.getHighlightedStrokeCount()
        val isCurrentStrokeGroup = drawingView.isCurrentStrokeGroup()
        val strokeHasBezierData = drawingView.currentStrokeHasBezierData()

        if (highlightedStrokeCount <= 1 &&
            !isCurrentStrokeGroup &&
            strokeHasBezierData &&
            drawingView.singleHighlightedStroke != null) {

            val stroke = drawingView.singleHighlightedStroke
            if (stroke?.renderAsBezier == true) {
                btnBezier.text = "Restore"
            } else {
                btnBezier.text = "Bezier(${stroke?.bezierAnchorIndices?.size})"
            }
            btnBezier.visibility = View.VISIBLE
        } else {
            btnBezier.visibility = View.GONE
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
        btnUndoStrokeEdit.isEnabled = drawingView.areAnyHighlightedStrokesModified()
        btnDuplicateStroke.visibility = if (isEditing) View.VISIBLE else View.GONE
        btnDuplicateStroke.isEnabled = highlightedStrokeCount == 1

        // Show Group button if more than 1 stroke is highlighted AND the current stroke is NOT a group
        btnGroupStrokes.visibility = if (highlightedStrokeCount > 1 && !isCurrentStrokeGroup) View.VISIBLE else View.GONE
        // Show UnGroup button if exactly 1 stroke is highlighted AND that stroke IS a group
        btnUnGroupStrokes.visibility = if (highlightedStrokeCount == 1 && isCurrentStrokeGroup) View.VISIBLE else View.GONE

        // Show polyline button if exactly 1 non-group stroke is highlighted and it has polyline data
        updateShowPolylineButton()
        updateShowBezierButton()

        if (highlightedStrokeCount != 1) {
            btnShape.visibility = View.GONE
        }

        // Show DEL button if:
        // 1. There are highlighted strokes, OR
        // 2. selectedStrokeIdx == strokes.lastIndex (but only if there are strokes)
        val shouldShowDel = highlightedStrokeCount > 0
        btnDel.visibility = if (shouldShowDel) View.VISIBLE else View.GONE

        // Show add anchor point button if ANY highlighted strokes are present
        // (Remove button still requires dragging an anchor point)
        if (drawingView.isEditing() && highlightedStrokeCount > 0) {
            if (drawingView.isAnchorPointDragging()) {
                // Show remove button, hide add button
                btnRemoveAnchorPoint.visibility = View.VISIBLE
                btnAddAnchorPoint.visibility = View.GONE
            } else {
                // Show add button if any strokes are highlighted
                btnRemoveAnchorPoint.visibility = View.GONE
                btnAddAnchorPoint.visibility = View.VISIBLE
            }
        } else {
            // Hide both buttons
            btnRemoveAnchorPoint.visibility = View.GONE
            btnAddAnchorPoint.visibility = View.GONE
        }

        btnSelection.visibility = View.VISIBLE
        btnSelection.isEnabled = drawingView.strokes.isNotEmpty() // As long as there are some strokes to select...
    }
}

data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)