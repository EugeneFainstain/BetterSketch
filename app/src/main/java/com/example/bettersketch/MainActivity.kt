package com.example.bettersketch

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat

class MainActivity : AppCompatActivity(), LoupeListener, ConfirmActionDialogFragment.Listener {

    private lateinit var drawingView: DrawingView
    private lateinit var startView: ImageView
    private lateinit var endView: ImageView
    private lateinit var strokeWidthSeekBar: SeekBar
    private lateinit var colorSeekBar: SeekBar

    private val colors = intArrayOf(
        Color.BLACK,
        Color.parseColor("#FF0000"), // Red
        Color.parseColor("#FF8000"), // Orange
        Color.parseColor("#EEEE00"), // Darker Yellow
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

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // Flags to track double-tap state
    private var isWidthInDoubleTap = false
    private var isColorInDoubleTap = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawingView = findViewById(R.id.drawingView)
        drawingView.loupeListener = this

        startView = findViewById(R.id.startView)
        endView = findViewById(R.id.endView)
        strokeWidthSeekBar = findViewById(R.id.seekWidth)
        colorSeekBar = findViewById(R.id.seekColor)

        strokeWidthSeekBar.progressDrawable = WidthIndicatorDrawable()
        colorSeekBar.progressDrawable = DiscreteColorDrawable(colors)

        setupSeekBarListeners()

        startView.setOnTouchListener { _, event -> handleLoupeTouch(event, isStart = true) }
        endView.setOnTouchListener { _, event -> handleLoupeTouch(event, isStart = false) }

        findViewById<Button>(R.id.btnUndo).setOnClickListener { drawingView.undo() }
        findViewById<Button>(R.id.btnRedo).setOnClickListener { drawingView.redo() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { drawingView.clearAll() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveToGallery() }
    }

    private fun setupSeekBarListeners() {
        // --- Width SeekBar ---_class
        val widthGestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                isWidthInDoubleTap = true
                drawingView.setStrokeWidth(strokeWidthSeekBar.progress.toFloat(), applyToLast = true)
                return true
            }
        })

        strokeWidthSeekBar.setOnTouchListener { _, event ->
            widthGestureDetector.onTouchEvent(event)
            // Reset the flag when the gesture ends
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                // We need to post this to the end of the queue to ensure it's processed
                // after onStopTrackingTouch has been called.
                strokeWidthSeekBar.post { isWidthInDoubleTap = false }
            }
            false // Let the SeekBar handle the event for thumb movement
        }

        strokeWidthSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && isWidthInDoubleTap) {
                    drawingView.setStrokeWidth(progress.toFloat(), applyToLast = true)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (!isWidthInDoubleTap) {
                    drawingView.setStrokeWidth(seekBar.progress.toFloat(), applyToLast = false)
                }
            }
        })

        // --- Color SeekBar ---_class
        val colorGestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                isColorInDoubleTap = true
                drawingView.setColor(colors[colorSeekBar.progress], applyToLast = true)
                return true
            }
        })

        colorSeekBar.setOnTouchListener { _, event ->
            colorGestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                colorSeekBar.post { isColorInDoubleTap = false }
            }
            false
        }

        colorSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && isColorInDoubleTap) {
                    drawingView.setColor(colors[progress], applyToLast = true)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (!isColorInDoubleTap) {
                    drawingView.setColor(colors[seekBar.progress], applyToLast = false)
                }
            }
        })
    }

    private fun handleLoupeTouch(event: MotionEvent, isStart: Boolean): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                val dy = event.y - lastTouchY
                if (isStart) {
                    drawingView.moveStartPoint(dx, dy)
                } else {
                    drawingView.moveEndPoint(dx, dy)
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
        }
        return true
    }

    override fun onStartLoupeUpdate(bitmap: Bitmap?) {
        startView.setImageBitmap(bitmap)
    }

    override fun onEndLoupeUpdate(bitmap: Bitmap?) {
        endView.setImageBitmap(bitmap)
    }

    override fun onRedoHistoryDecisionRequired() {
        ConfirmActionDialogFragment().show(supportFragmentManager, "confirm_dialog")
    }

    override fun onCurrentStrokeWidthChanged(width: Float) {
        strokeWidthSeekBar.progress = width.toInt()
    }

    override fun onCurrentColorChanged(color: Int) {
        val index = colors.indexOf(color)
        if (index != -1) {
            colorSeekBar.progress = index
        }
    }

    override fun onConfirmDiscardRedo() {
        drawingView.clearRedoHistory()
        Toast.makeText(this, "Redo history cleared. You can now draw a new stroke.", Toast.LENGTH_SHORT).show()
    }

    override fun onConfirmInsertStroke() {
        drawingView.prepareToInsertStroke()
        Toast.makeText(this, "You can now draw. The next stroke will be inserted.", Toast.LENGTH_LONG).show()
    }

    override fun onCancel() {
        // No-op
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
