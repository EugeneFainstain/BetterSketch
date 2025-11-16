package com.example.bettersketch

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), DrawingViewListener {

    private lateinit var drawingView: DrawingView
    private lateinit var widthSlider: WidthSlider
    private lateinit var colorSlider: ColorSlider
    private lateinit var progressSeekBar: SeekBar
    private lateinit var historyIndicator: HistoryIndicatorDrawable
    private lateinit var rewButton: Button
    private lateinit var ffButton: Button
    private lateinit var toggleModeButton: Button
    private lateinit var drawingGestureDetector: GestureDetector

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

    // App state
    private var isAppInEditMode = false

    // Auto-repeat for buttons
    private val handler = Handler(Looper.getMainLooper())
    private var autoRepeatRunnable: Runnable? = null


    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawingView = findViewById(R.id.drawingView)
        drawingView.listener = this

        widthSlider = findViewById(R.id.widthSlider)
        colorSlider = findViewById(R.id.colorSlider)
        progressSeekBar = findViewById(R.id.seekProgress)
        rewButton = findViewById(R.id.btnUndo)
        ffButton = findViewById(R.id.btnRedo)
        toggleModeButton = findViewById(R.id.btnToggleMode)


        historyIndicator = HistoryIndicatorDrawable()
        val originalProgressDrawable = progressSeekBar.progressDrawable.constantState?.newDrawable()?.mutate()
        if (originalProgressDrawable != null) {
            val layers = arrayOf(originalProgressDrawable, historyIndicator)
            progressSeekBar.progressDrawable = LayerDrawable(layers)
        } else {
            progressSeekBar.progressDrawable = historyIndicator
        }

        setupSliderListeners()
        setupAutoRepeatListeners()
        setupDrawingViewGestureDetector()


        findViewById<Button>(R.id.btnClear).setOnClickListener { 
            drawingView.resetStrokeSelection()
            drawingView.deleteCurrentStroke() 
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveToGallery() }
        
        toggleModeButton.setOnClickListener {
            isAppInEditMode = !isAppInEditMode
            updateModeButtonState()
            updateUi()
        }

        drawingView.post { updateUi() }
        updateModeButtonState()
    }

    private fun updateModeButtonState() {
        if (isAppInEditMode) {
            toggleModeButton.text = "EDITING"
            toggleModeButton.setBackgroundColor(Color.parseColor("#BB0000")) // Darker Red
            rewButton.visibility = View.VISIBLE
            ffButton.visibility = View.VISIBLE
            drawingView.setOnTouchListener { _, event -> drawingGestureDetector.onTouchEvent(event) }
        } else {
            toggleModeButton.text = "DRAWING"
            toggleModeButton.setBackgroundColor(Color.parseColor("#008800")) // Darker Green
            rewButton.visibility = View.GONE
            ffButton.visibility = View.GONE
            drawingView.setOnTouchListener { _, event -> drawingView.onTouchEvent(event) }
        }
    }

    private fun setupDrawingViewGestureDetector() {
        drawingGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                drawingView.deselectAllStrokes()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                drawingView.findClosestStroke(PointF(e.x, e.y))
                return true
            }
        })
    }

    private fun setupAutoRepeatListeners() {
        val repeatListener = { action: () -> Unit ->
            View.OnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        handler.removeCallbacksAndMessages(null)
                        view.isPressed = true
                        action() 
                        autoRepeatRunnable = object : Runnable {
                            override fun run() {
                                action()
                                handler.postDelayed(this, 100)
                            }
                        }
                        handler.postDelayed(autoRepeatRunnable!!, 200)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        autoRepeatRunnable?.let { handler.removeCallbacks(it) }
                        autoRepeatRunnable = null
                        view.isPressed = false
                    }
                }
                true
            }
        }

        rewButton.setOnTouchListener(repeatListener(drawingView::undo))
        ffButton.setOnTouchListener(repeatListener(drawingView::redo))
    }

    private fun setupSliderListeners() {
        widthSlider.listener = object : MySlider.OnSliderValueChangedListener {
            override fun onValueChanged(value: Float) {
                val strokeWidth = 2f + value * 30f // 2...32
                drawingView.setStrokeWidth(strokeWidth, applyToLast = isAppInEditMode)
            }

            override fun onValueEdit(value: Float) {
                val strokeWidth = 2f + value * 30f // 2...32
                drawingView.setStrokeWidth(strokeWidth, applyToLast = true)
            }

            override fun onValueEditEnd() {}
        }

        colorSlider.colors = colors
        colorSlider.listener = object : MySlider.OnSliderValueChangedListener {
            override fun onValueChanged(value: Float) {
                val colorIndex = (value * (colors.size - 1)).roundToInt()
                val color = colors[colorIndex]
                drawingView.setColor(color, applyToLast = isAppInEditMode)
                widthSlider.color = color
            }

            override fun onValueEdit(value: Float) {
                val colorIndex = (value * (colors.size - 1)).roundToInt()
                val color = colors[colorIndex]
                drawingView.setColor(color, applyToLast = true)
                widthSlider.color = color
            }

            override fun onValueEditEnd() {}
        }

        progressSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    drawingView.navigateToHistoryState(progress)
                    drawingView.resetStrokeSelection()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun onStateChanged() {
        updateUi()
    }

    private fun updateUi() {
        rewButton.isEnabled = drawingView.canRewind
        ffButton.isEnabled = drawingView.canFF

        progressSeekBar.max = drawingView.historySize
        progressSeekBar.progress = drawingView.currentHistoryPosition
        historyIndicator.strokeColors = drawingView.getStrokeColors()
        
        drawingView.isEditingMode = isAppInEditMode

        val currentPaint = drawingView.lastStroke?.paint ?: drawingView.currentPaint
        val widthValue = (currentPaint.strokeWidth - 2f) / 30f
        widthSlider.value = widthValue

        val colorIndex = colors.indexOf(currentPaint.color)
        if (colorIndex != -1) {
            colorSlider.value = colorIndex.toFloat() / (colors.size - 1)
        }

        widthSlider.color = currentPaint.color
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
