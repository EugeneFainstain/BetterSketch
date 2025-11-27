package com.example.bettersketch

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
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

        setupSliderListeners()

        btnDel.setOnClickListener {
            drawingView.deleteCurrentStroke()
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
            btnShape.text = "${String.format("%.2f", percentage)}% $shapeName"
            btnShape.visibility = View.VISIBLE
            btnShape.setOnClickListener {
                drawingView.replaceWithShape(shapeFitResult.strokeToReplace, fittedStroke)
                btnShape.visibility = View.GONE
                btnPolyline.visibility = View.GONE
            }
        }

        // Handle polyline fit button
        if (polylineFit != null) {
            val polylinePercentage = (1.0f - polylineFit.error) * 100
            btnPolyline.text = "${String.format("%.2f", polylinePercentage)}% PolyLine(${polylineFit.k})"
            btnPolyline.visibility = View.VISIBLE
            btnPolyline.setOnClickListener {
                drawingView.replaceWithShape(shapeFitResult.strokeToReplace, polylineFit.fittedStroke)
                btnShape.visibility = View.GONE
                btnPolyline.visibility = View.GONE
            }
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

        if (highlightedStrokeCount != 1) {
            btnShape.visibility = View.GONE
            btnPolyline.visibility = View.GONE
        }

        btnDel.visibility = View.GONE
        if (drawingView.isStrokeSelected)
            btnDel.visibility = View.VISIBLE

        if( drawingView.selectedStrokeIdx == drawingView.strokes.lastIndex )
            btnDel.visibility = View.VISIBLE
    }
}

data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)