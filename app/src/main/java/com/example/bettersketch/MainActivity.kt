package com.example.bettersketch

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), LoupeListener {

    private lateinit var drawingView: DrawingView
    private lateinit var startView: ImageView
    private lateinit var endView: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawingView = findViewById(R.id.drawingView)
        drawingView.loupeListener = this

        startView = findViewById(R.id.startView)
        endView = findViewById(R.id.endView)

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

    override fun onStartLoupeUpdate(bitmap: Bitmap?) {
        startView.setImageBitmap(bitmap)
    }

    override fun onEndLoupeUpdate(bitmap: Bitmap?) {
        endView.setImageBitmap(bitmap)
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
