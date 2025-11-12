package com.example.bettersketch

import android.graphics.Paint
import android.graphics.PointF

data class Stroke(
    val points: MutableList<PointF>,
    val paint: Paint
)
