package com.example.bettersketch

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), DrawingViewListener, ShapeDetectionListener {

    private lateinit var drawingView: DrawingView
    private lateinit var widthSlider: WidthSlider
    private lateinit var colorSlider: ColorSlider
    private lateinit var smoothingSlider: SmoothingSlider
    private lateinit var btnUndoStrokeEdit: Button
    private lateinit var btnDuplicateStroke: Button
    private lateinit var btnGroupStrokes: Button
    private lateinit var btnUnGroupStrokes: Button
    private lateinit var btnSquare: Button

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
        btnSquare = findViewById(R.id.btnSquare)

        findViewById<View>(R.id.seekProgress).visibility = View.GONE
        findViewById<View>(R.id.btnUndo).visibility = View.GONE
        findViewById<View>(R.id.btnRedo).visibility = View.GONE

        setupSliderListeners()

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            drawingView.deleteCurrentStroke()
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveToGallery() }

        findViewById<View>(R.id.btnToggleMode).visibility = View.GONE

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

    override fun onShapeDetected(fitResult: SquareFitter.FitResult) {
        btnSquare.text = "Square?"
        btnSquare.visibility = View.VISIBLE
        btnSquare.setOnClickListener {
            drawingView.replaceWithSquare(drawingView.strokes.last(), fitResult)
            btnSquare.visibility = View.GONE
        }
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
    }

    private fun saveToGallery() {
        val bmp: Bitmap = drawingView.exportBitmap()
        val name = "Doodle_${System.currentTimeMillis()}.png"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Doodles")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            Toast.makeText(this, "Saved to gallery ✓", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Save failed", Toast.LENGTH_SHORT).show()
        }
    }
}
