package com.example.bettersketch

import android.graphics.Paint
import android.graphics.Path

data class Stroke(
    val path: Path,
    val paint: Paint,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
)
