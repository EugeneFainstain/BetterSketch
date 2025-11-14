package com.example.bettersketch

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), DrawingViewListener {

    private lateinit var drawingView: DrawingView
    private lateinit var startView: ImageView
    private lateinit var endView: ImageView
    private lateinit var widthSlider: WidthSlider
    private lateinit var colorSlider: ColorSlider
    private lateinit var progressSeekBar: SeekBar
    private lateinit var historyIndicator: HistoryIndicatorDrawable
    private lateinit var rewButton: Button
    private lateinit var ffButton: Button

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

    private var lastStartX = 0f
    private var lastStartY = 0f
    private var lastEndX = 0f
    private var lastEndY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawingView = findViewById(R.id.drawingView)
        drawingView.listener = this

        startView = findViewById(R.id.startView)
        endView = findViewById(R.id.endView)
        widthSlider = findViewById(R.id.widthSlider)
        colorSlider = findViewById(R.id.colorSlider)
        progressSeekBar = findViewById(R.id.seekProgress)
        rewButton = findViewById(R.id.btnUndo)
        ffButton = findViewById(R.id.btnRedo)

        // Setup history slider with tick marks over the default rail
        historyIndicator = HistoryIndicatorDrawable()
        val originalProgressDrawable = progressSeekBar.progressDrawable.constantState?.newDrawable()?.mutate()
        if (originalProgressDrawable != null) {
            val layers = arrayOf(originalProgressDrawable, historyIndicator)
            progressSeekBar.progressDrawable = LayerDrawable(layers)
        } else {
            progressSeekBar.progressDrawable = historyIndicator
        }

        setupSliderListeners()

        rewButton.setOnClickListener { drawingView.undo() }
        ffButton.setOnClickListener { drawingView.redo() }

        findViewById<Button>(R.id.btnClear).setOnClickListener { drawingView.deleteCurrentStroke() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveToGallery() }

        startView.setOnTouchListener { _, event -> handleLoupeTouch(event, isStart = true) }
        endView.setOnTouchListener { _, event -> handleLoupeTouch(event, isStart = false) }

        // Trigger an initial update to draw the loupes
        drawingView.post { drawingView.updateLoupes(startView.width, startView.height, endView.width, endView.height) }
    }

    private fun setupSliderListeners() {
        // --- Width Slider ---
        widthSlider.listener = object : MySlider.OnSliderValueChangedListener {
            override fun onValueChanged(value: Float) {
                val strokeWidth = 2f + value * 30f // 2...32
                drawingView.setStrokeWidth(strokeWidth, applyToLast = false)
            }

            override fun onValueEdit(value: Float) {
                val strokeWidth = 2f + value * 30f // 2...32
                drawingView.setStrokeWidth(strokeWidth, applyToLast = true)
            }

            override fun onValueEditEnd() {
                // No action needed
            }
        }

        // --- Color Slider ---
        colorSlider.colors = colors
        colorSlider.listener = object : MySlider.OnSliderValueChangedListener {
            override fun onValueChanged(value: Float) {
                val colorIndex = (value * (colors.size - 1)).roundToInt()
                val color = colors[colorIndex]
                drawingView.setColor(color, applyToLast = false)
                widthSlider.color = color
            }

            override fun onValueEdit(value: Float) {
                val colorIndex = (value * (colors.size - 1)).roundToInt()
                val color = colors[colorIndex]
                drawingView.setColor(color, applyToLast = true)
                widthSlider.color = color
            }

            override fun onValueEditEnd() {
                // No action needed
            }
        }

        // --- Progress SeekBar ---
        progressSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    drawingView.navigateToHistoryState(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun onStateChanged() {
        updateUi()
    }

    override fun onStartLoupeUpdate(bitmap: Bitmap?) {
        startView.setImageBitmap(bitmap)
    }

    override fun onEndLoupeUpdate(bitmap: Bitmap?) {
        endView.setImageBitmap(bitmap)
    }

    private fun updateUi() {
        rewButton.isEnabled = drawingView.canRewind
        ffButton.isEnabled = drawingView.canFF

        progressSeekBar.max = drawingView.historySize
        progressSeekBar.progress = drawingView.currentHistoryPosition
        historyIndicator.strokeColors = drawingView.getStrokeColors()

        val currentPaint = drawingView.lastStroke?.paint ?: drawingView.currentPaint
        val widthValue = (currentPaint.strokeWidth - 2f) / 30f
        widthSlider.value = widthValue

        val colorIndex = colors.indexOf(currentPaint.color)
        if (colorIndex != -1) {
            colorSlider.value = colorIndex.toFloat() / (colors.size - 1)
        }

        widthSlider.color = currentPaint.color
        drawingView.updateLoupes(startView.width, startView.height, endView.width, endView.height)
    }

    private fun handleLoupeTouch(event: MotionEvent, isStart: Boolean): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (isStart) {
                    lastStartX = event.x
                    lastStartY = event.y
                } else {
                    lastEndX = event.x
                    lastEndY = event.y
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isStart) {
                    val dx = event.x - lastStartX
                    val dy = event.y - lastStartY
                    drawingView.moveStartPoint(dx, dy)
                    lastStartX = event.x
                    lastStartY = event.y
                } else {
                    val dx = event.x - lastEndX
                    val dy = event.y - lastEndY
                    drawingView.moveEndPoint(dx, dy)
                    lastEndX = event.x
                    lastEndY = event.y
                }
            }
        }
        return true
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
