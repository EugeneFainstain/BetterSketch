package com.example.bettersketch

import android.graphics.Paint
import android.graphics.PointF

data class PathPoint(val point: PointF, val distance: Float)

data class Stroke(
    val points: MutableList<PathPoint>,
    val paint: Paint,
    val totalDistance: Float
)
