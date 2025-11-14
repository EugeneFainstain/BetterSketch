package com.example.bettersketch

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), DrawingViewListener {

    private lateinit var drawingView: DrawingView
    private lateinit var loupeView: ImageView
    private lateinit var widthSlider: WidthSlider
    private lateinit var colorSlider: ColorSlider
    private lateinit var progressSeekBar: SeekBar
    private lateinit var historyIndicator: HistoryIndicatorDrawable
    private lateinit var rewButton: Button
    private lateinit var ffButton: Button
    private lateinit var toggleModeButton: Button

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
    private var isEditingMode = false

    // Multi-touch tracking
    private var lastMidpointX = 0f
    private var lastMidpointY = 0f
    private var lastAngle = 0f
    private var lastDistance = 0f

    // Single-touch tracking
    private var isDraggingLoupe = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    
    // Auto-repeat for buttons
    private val handler = Handler(Looper.getMainLooper())
    private var autoRepeatRunnable: Runnable? = null

    // Endpoint selection
    private var selectedEnd: SelectedEnd = SelectedEnd.START


    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawingView = findViewById(R.id.drawingView)
        drawingView.listener = this

        loupeView = findViewById(R.id.loupeView)
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


        findViewById<Button>(R.id.btnClear).setOnClickListener { 
            selectedEnd = SelectedEnd.END
            drawingView.deleteCurrentStroke() 
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveToGallery() }
        
        toggleModeButton.setOnClickListener {
            isEditingMode = !isEditingMode
            updateModeButtonState()
            updateUi()
        }

        loupeView.setOnTouchListener(::handleLoupeTouch)

        drawingView.post { updateUi() }
        updateModeButtonState()
    }

    private fun updateModeButtonState() {
        if (isEditingMode) {
            toggleModeButton.text = "EDITING"
            toggleModeButton.setBackgroundColor(Color.parseColor("#BB0000")) // Darker Red
            loupeView.visibility = View.VISIBLE
            rewButton.visibility = View.VISIBLE
            ffButton.visibility = View.VISIBLE
            drawingView.setOnTouchListener(null)
            loupeView.setOnTouchListener(::handleLoupeTouch)
        } else {
            toggleModeButton.text = "DRAWING"
            toggleModeButton.setBackgroundColor(Color.parseColor("#008800")) // Darker Green
            loupeView.visibility = View.GONE
            rewButton.visibility = View.GONE
            ffButton.visibility = View.GONE
            drawingView.setOnTouchListener { _, event -> drawingView.onTouchEvent(event) }
            loupeView.setOnTouchListener(null)
        }
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
                        handler.removeCallbacks(autoRepeatRunnable!!)
                        autoRepeatRunnable = null
                        view.isPressed = false
                    }
                }
                true
            }
        }

        rewButton.setOnTouchListener(repeatListener(::doRew))
        ffButton.setOnTouchListener(repeatListener(::doFf))
    }

    private fun doFf() {
        if (selectedEnd == SelectedEnd.START) {
            selectedEnd = SelectedEnd.END
            updateUi()
        } else {
            if (drawingView.canFF) {
                selectedEnd = SelectedEnd.START
                drawingView.redo()
            }
        }
    }

    private fun doRew() {
        if (selectedEnd == SelectedEnd.END) {
            selectedEnd = SelectedEnd.START
            updateUi()
        } else {
            if (drawingView.canRewind) {
                selectedEnd = SelectedEnd.END
                drawingView.undo()
            }
        }
    }

    private fun setupSliderListeners() {
        widthSlider.listener = object : MySlider.OnSliderValueChangedListener {
            override fun onValueChanged(value: Float) {
                val strokeWidth = 2f + value * 30f // 2...32
                drawingView.setStrokeWidth(strokeWidth, applyToLast = isEditingMode)
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
                drawingView.setColor(color, applyToLast = isEditingMode)
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
                    selectedEnd = SelectedEnd.END
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun onStateChanged() {
        updateUi()
    }

    override fun onLoupeUpdate(bitmap: Bitmap?) {
        loupeView.setImageBitmap(bitmap)
    }

    override fun onSelectedEndChanged(selectedEnd: SelectedEnd) {
        this.selectedEnd = selectedEnd
    }

    private fun updateUi() {
        rewButton.isEnabled = drawingView.canRewind || selectedEnd == SelectedEnd.END
        ffButton.isEnabled = drawingView.canFF || selectedEnd == SelectedEnd.START

        progressSeekBar.max = drawingView.historySize
        progressSeekBar.progress = drawingView.currentHistoryPosition
        historyIndicator.strokeColors = drawingView.getStrokeColors()
        
        drawingView.selectedEnd = selectedEnd
        drawingView.isEditingMode = isEditingMode

        val currentPaint = drawingView.lastStroke?.paint ?: drawingView.currentPaint
        val widthValue = (currentPaint.strokeWidth - 2f) / 30f
        widthSlider.value = widthValue

        val colorIndex = colors.indexOf(currentPaint.color)
        if (colorIndex != -1) {
            colorSlider.value = colorIndex.toFloat() / (colors.size - 1)
        }

        widthSlider.color = currentPaint.color
        drawingView.updateLoupes(loupeView.width, loupeView.height, selectedEnd)
    }

    private fun handleLoupeTouch(v: View, event: MotionEvent): Boolean {
        if (!isEditingMode) return true 
        
        val action = event.actionMasked

        if (event.pointerCount >= 2) {
            isDraggingLoupe = false
            if (action == MotionEvent.ACTION_POINTER_DOWN || (action == MotionEvent.ACTION_DOWN && event.pointerCount > 1)) {
                lastDistance = distance(event)
                lastAngle = angle(event)
                val midpoint = midpoint(event)
                lastMidpointX = midpoint.x
                lastMidpointY = midpoint.y
            } else if (action == MotionEvent.ACTION_MOVE) {
                val newDist = distance(event)
                val newAngle = angle(event)
                val midpoint = midpoint(event)

                val scale = if (lastDistance > 0) newDist / lastDistance else 1f
                val rotate = newAngle - lastAngle
                val translateX = midpoint.x - lastMidpointX
                val translateY = midpoint.y - lastMidpointY

                drawingView.transformLastStroke(translateX, translateY, scale, rotate)

                lastDistance = newDist
                lastAngle = newAngle
                lastMidpointX = midpoint.x
                lastMidpointY = midpoint.y
            }
        } else if (event.pointerCount == 1) {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    isDraggingLoupe = true
                    downX = event.x
                    downY = event.y
                    downTime = System.currentTimeMillis()
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingLoupe) {
                        val dx = event.x - lastTouchX
                        val dy = event.y - lastTouchY
                        if (selectedEnd == SelectedEnd.START) {
                            drawingView.moveStartPoint(dx, dy)
                        } else if (selectedEnd == SelectedEnd.END) {
                            drawingView.moveEndPoint(dx, dy)
                        }
                        lastTouchX = event.x
                        lastTouchY = event.y
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isDraggingLoupe) {
                        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
                        val dx = abs(event.x - downX)
                        val dy = abs(event.y - downY)
                        val dt = System.currentTimeMillis() - downTime
                        if (dx < touchSlop && dy < touchSlop && dt < ViewConfiguration.getTapTimeout()*2 ) {
                            selectedEnd = if (selectedEnd == SelectedEnd.START) SelectedEnd.END else SelectedEnd.START
                            updateUi()
                        }
                        isDraggingLoupe = false
                    }
                }
            }
        }
        return true
    }

    private fun distance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun angle(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return atan2(dy, dx) * (180f / Math.PI.toFloat())
    }

    private fun midpoint(event: MotionEvent): android.graphics.PointF {
        val x = (event.getX(0) + event.getX(1)) / 2f
        val y = (event.getY(0) + event.getY(1)) / 2f
        return android.graphics.PointF(x, y)
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
