package com.example.bettersketch

import android.annotation.SuppressLint
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), LoupeListener, ConfirmActionDialogFragment.Listener {

    private lateinit var drawingView: DrawingView
    private lateinit var startView: ImageView
    private lateinit var endView: ImageView

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawingView = findViewById(R.id.drawingView)
        drawingView.loupeListener = this

        startView = findViewById(R.id.startView)
        endView = findViewById(R.id.endView)

        startView.setOnTouchListener { _, event -> handleLoupeTouch(event, isStart = true) }
        endView.setOnTouchListener { _, event -> handleLoupeTouch(event, isStart = false) }

        findViewById<Button>(R.id.btnUndo).setOnClickListener { drawingView.undo() }
        findViewById<Button>(R.id.btnRedo).setOnClickListener { drawingView.redo() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { drawingView.clearAll() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { saveToGallery() }

        findViewById<SeekBar>(R.id.seekWidth).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    drawingView.setStrokeWidth(progress.toFloat().coerceAtLeast(1f))
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            }
        )

        wireColorSwatch(R.id.colorBlack, Color.BLACK)
        wireColorSwatch(R.id.colorRed,   0xFFF44336.toInt())
        wireColorSwatch(R.id.colorBlue,  0xFF2196F3.toInt())
        wireColorSwatch(R.id.colorGreen, 0xFF4CAF50.toInt())
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

    private fun wireColorSwatch(viewId: Int, color: Int) {
        val v = findViewById<View>(viewId)
        v.setOnClickListener { drawingView.setColor(color) }
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
