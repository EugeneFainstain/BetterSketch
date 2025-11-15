package com.example.bettersketch

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

interface DrawingViewListener {
    fun onStateChanged()
    fun onSelectedEndChanged(selectedEnd: SelectedEnd)
}

enum class SelectedEnd {
    START, END, NONE
}

class DrawingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var listener: DrawingViewListener? = null
    var selectedEnd: SelectedEnd = SelectedEnd.NONE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
                listener?.onSelectedEndChanged(value)
            }
        }
    var isEditingMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    // Drawing state
    private var backingBitmap: Bitmap? = null
    private var backingCanvas: Canvas? = null

    // Current tools
    private val currentPoints = mutableListOf<PathPoint>()
    private var currentDistance = 0f
    var currentPaint = defaultPaint(Color.BLACK, 12f)

    // History for undo/redo
    private val strokes = mutableListOf<Stroke>()
    private val undone = ArrayDeque<Stroke>()

    // Transformation state
    private var totalScale = 1.0f
    private var lastMidpoint = PointF()
    private var lastDistance = 0f
    private var lastAngle = 0f
    private var isTransforming = false
    private var singleFingerGestureAllowed = true

    // Tap detection state
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastTouchX = 0f
    private var lastTouchY = 0f


    // Public properties for history state
    val canRewind: Boolean get() = strokes.isNotEmpty()
    val canFF: Boolean get() = undone.isNotEmpty()
    val historySize: Int get() = strokes.size + undone.size
    val currentHistoryPosition: Int get() = strokes.size
    val lastStroke: Stroke? get() = strokes.lastOrNull()

    // Export bitmap helper
    fun exportBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        draw(c)
        return bmp
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            backingBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            backingCanvas = Canvas(backingBitmap!!)
            redrawHistory()
        } else {
            backingBitmap = null
            backingCanvas = null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()

        // 1. Draw the cached history
        if (isTransforming) {
            redrawHistory(canvas, getScale())
        } else {
            backingBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }

        // 2. Draw the live stroke or the halo for the selected stroke
        if (isEditingMode) {
            if (currentPoints.isNotEmpty()) {
                drawStrokeWithHalo(canvas, currentPoints, currentPaint)
            } else {
                strokes.lastOrNull()?.let {
                    drawStrokeWithHalo(canvas, it.points, it.paint)
                }
            }
        } else {
            if (currentPoints.isNotEmpty()) {
                val scaledPaint = Paint(currentPaint)
                scaledPaint.strokeWidth = max(1f / getScale(), currentPaint.strokeWidth)
                drawPoints(canvas, currentPoints, scaledPaint)
            }
        }
        
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerCount = event.pointerCount

        if (pointerCount >= 2) {
            handleMultiTouch(event)
        } else if (pointerCount == 1 && singleFingerGestureAllowed) {
            handleSingleTouch(event)
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || (action == MotionEvent.ACTION_POINTER_UP && pointerCount == 2)) {
            if(isTransforming) {
                redrawHistory()
                isTransforming = false
            }

            if( !singleFingerGestureAllowed )
                if( action == MotionEvent.ACTION_UP ) // last finger lifted?
                    singleFingerGestureAllowed = true // re-enable single fingure gestures
        }

        invalidate()
        return true
    }
    
    private fun handleMultiTouch(event: MotionEvent) {
        val action = event.actionMasked
        val midpoint = midpoint(event)
        isTransforming = true
        singleFingerGestureAllowed = false // disable single finger gestures until all fingers are lifted

        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            if (currentPoints.isNotEmpty()) {
                if (currentPoints.size > 5) {
                    commitCurrentStroke()
                } else {
                    currentPoints.clear()
                }
            }
            lastDistance = distance(event)
            lastAngle = angle(event)
            lastMidpoint.set(midpoint)
        } else if (action == MotionEvent.ACTION_MOVE) {
            val newDist = distance(event)
            val newAngle = angle(event)
            
            val scale = if (lastDistance > 0) newDist / lastDistance else 1f
            totalScale *= scale
            val rotate = newAngle - lastAngle
            val dx = midpoint.x - lastMidpoint.x
            val dy = midpoint.y - lastMidpoint.y
            
            val deltaMatrix = Matrix()
            deltaMatrix.postTranslate(dx, dy)
            deltaMatrix.postScale(scale, scale, midpoint.x, midpoint.y)
            deltaMatrix.postRotate(rotate, midpoint.x, midpoint.y)
            
            transformAllStrokes(deltaMatrix)

            lastDistance = newDist
            lastAngle = newAngle
            lastMidpoint.set(midpoint)
        }
    }
    
    private fun handleSingleTouch(event: MotionEvent) {
         val x = event.x
         val y = event.y
         
         when (event.actionMasked) {
             MotionEvent.ACTION_DOWN -> {
                 downX = x
                 downY = y
                 downTime = System.currentTimeMillis()
                 lastTouchX = x
                 lastTouchY = y
                 if (!isEditingMode) {
                     touchStart(x, y)
                 }
             }
             MotionEvent.ACTION_MOVE -> {
                 if (isEditingMode) {
                     val dx = x - lastTouchX
                     val dy = y - lastTouchY
                     if (selectedEnd == SelectedEnd.START) {
                         moveStartPoint(dx, dy)
                     } else if (selectedEnd == SelectedEnd.END) {
                         moveEndPoint(dx, dy)
                     }
                     lastTouchX = x
                     lastTouchY = y
                 } else {
                    touchMove(x, y)
                 }
             }
             MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                 if (isEditingMode) {
                     val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
                     val dx = abs(x - downX)
                     val dy = abs(y - downY)
                     val dt = System.currentTimeMillis() - downTime
                     if (dx < touchSlop && dy < touchSlop && dt < ViewConfiguration.getTapTimeout() * 2) {
                         findClosestStroke(PointF(x,y))
                     }
                 } else {
                    touchUp()
                 }
             }
         }
    }
    
    private fun transformAllStrokes(matrix: Matrix) {
        val scale = getScaleFromMatrix(matrix)
        (strokes + undone).forEach { stroke ->
            stroke.paint.strokeWidth = max(1f, stroke.paint.strokeWidth * scale)
            stroke.points.forEach { pathPoint ->
                val point = floatArrayOf(pathPoint.point.x, pathPoint.point.y)
                matrix.mapPoints(point)
                pathPoint.point.set(point[0], point[1])
            }
        }
        redrawHistory()
    }

    private fun getScaleFromMatrix(matrix: Matrix): Float {
        val values = FloatArray(9)
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun touchStart(x: Float, y: Float) {
        selectedEnd = SelectedEnd.END
        currentPoints.clear()
        currentDistance = 0f
        currentPoints.add(PathPoint(PointF(x, y), 0f))
        listener?.onStateChanged()
    }

    private fun touchMove(x: Float, y: Float) {
        if (currentPoints.isEmpty()) return
        val lastPoint = currentPoints.last().point
        val dx = x - lastPoint.x
        val dy = y - lastPoint.y
        currentDistance += sqrt(dx * dx + dy * dy)
        currentPoints.add(PathPoint(PointF(x, y), currentDistance))
        listener?.onStateChanged()
    }

    private fun touchUp() {
        if (currentPoints.isNotEmpty()) {
            commitCurrentStroke()
        }
    }

    private fun commitCurrentStroke() {
        selectedEnd = SelectedEnd.END
        val newStroke = Stroke(currentPoints.toMutableList(), Paint(currentPaint), currentDistance)
        strokes.add(newStroke)
        currentPoints.clear()
        redrawHistory()
        listener?.onStateChanged()
    }
    
    private fun distance(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun angle(event: MotionEvent): Float {
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()).toDouble()).toFloat()
    }
    
    private fun midpoint(event: MotionEvent): PointF {
        val x = (event.getX(0) + event.getX(1)) / 2f
        val y = (event.getY(0) + event.getY(1)) / 2f
        return PointF(x,y)
    }

    fun findClosestStroke(tapPoint: PointF) {
        var closestStroke: Stroke? = null
        var minDistance = Float.MAX_VALUE

        for (stroke in strokes) {
            for (pathPoint in stroke.points) {
                val dx = pathPoint.point.x - tapPoint.x
                val dy = pathPoint.point.y - tapPoint.y
                val distance = sqrt(dx * dx + dy * dy)
                if (distance < minDistance) {
                    minDistance = distance
                    closestStroke = stroke
                }
            }
        }

        if (closestStroke != null) {
            val index = strokes.indexOf(closestStroke)
            if (index != -1) {
                if (index != strokes.size -1) {
                    strokes.removeAt(index)
                    strokes.add(closestStroke)
                }

                val startPoint = closestStroke.points.first().point
                val endPoint = closestStroke.points.last().point
                val distToStart = sqrt((startPoint.x - tapPoint.x) * (startPoint.x - tapPoint.x) + (startPoint.y - tapPoint.y) * (startPoint.y - tapPoint.y))
                val distToEnd = sqrt((endPoint.x - tapPoint.x) * (endPoint.x - tapPoint.x) + (endPoint.y - tapPoint.y) * (endPoint.y - tapPoint.y))
                
                selectedEnd = if (distToStart < distToEnd) SelectedEnd.START else SelectedEnd.END
                
                redrawHistory()
                listener?.onStateChanged()
            }
        }
    }

    fun deselectAllStrokes() {
        selectedEnd = SelectedEnd.NONE
        redrawHistory()
        listener?.onStateChanged()
    }

    fun navigateToHistoryState(index: Int) {
        while (strokes.size > index) {
            undone.addLast(strokes.removeAt(strokes.lastIndex))
        }
        while (strokes.size < index) {
            if (undone.isNotEmpty()) {
                strokes.add(undone.removeLast())
            } else {
                break
            }
        }
        redrawHistory()
        listener?.onStateChanged()
    }

    fun deleteCurrentStroke() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    fun getStrokeColors(): IntArray {
        val pastColors = strokes.map { it.paint.color }
        val futureColors = undone.reversed().map { it.paint.color }
        return (pastColors + futureColors).toIntArray()
    }

    fun transformLastStroke(translateX: Float, translateY: Float, scale: Float, rotate: Float) {
        lastStroke?.let {
            val bounds = it.getBounds()
            val centerX = bounds.centerX()
            val centerY = bounds.centerY()

            val matrix = Matrix()
            matrix.postTranslate(translateX, translateY)
            matrix.postScale(scale, scale, centerX + translateX, centerY + translateY)
            matrix.postRotate(rotate, centerX + translateX, centerY + translateY)

            val pts = it.points.flatMap { listOf(it.point.x, it.point.y) }.toFloatArray()
            matrix.mapPoints(pts)

            for ((index, pathPoint) in it.points.withIndex()) {
                pathPoint.point.x = pts[index * 2]
                pathPoint.point.y = pts[index * 2 + 1]
            }

            redrawHistory()
            listener?.onStateChanged()
        }
    }

    fun moveStartPoint(dx: Float, dy: Float) {
        if (strokes.isNotEmpty()) {
            val lastStroke = strokes.last()
            val totalDistance = lastStroke.totalDistance
            if (totalDistance == 0f) return 
            for (pathPoint in lastStroke.points) {
                val weight = 1.0f - (pathPoint.distance / totalDistance)
                pathPoint.point.offset(dx * weight, dy * weight)
            }
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    fun moveEndPoint(dx: Float, dy: Float) {
        if (strokes.isNotEmpty()) {
            val lastStroke = strokes.last()
            val totalDistance = lastStroke.totalDistance
            if (totalDistance == 0f) return 
            for (pathPoint in lastStroke.points) {
                val weight = pathPoint.distance / totalDistance
                pathPoint.point.offset(dx * weight, dy * weight)
            }
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    private fun getScale(): Float {
        return totalScale
    }

    private fun drawStrokeWithHalo(canvas: Canvas, points: List<PathPoint>, paint: Paint) {
        if (points.isEmpty()) return

        val scale = getScale()
        val scaledPaint = Paint(paint)
        scaledPaint.strokeWidth = max(1f / scale, paint.strokeWidth)

        // 1. Draw the halo
        val haloPaint = Paint(scaledPaint).apply {
            color = Color.LTGRAY
            strokeWidth = scaledPaint.strokeWidth + (32f / scale)
        }
        drawPoints(canvas, points, haloPaint)

        // 2. Draw the endpoint indicator circles
        if (isEditingMode) {
            if (currentPoints.isNotEmpty()) {
                // Special case: Drawing in progress, highlight both ends
                val startPaint = Paint().apply { style = Paint.Style.FILL; color = Color.GREEN }
                val endPaint = Paint().apply { style = Paint.Style.FILL; color = Color.RED }
                val radius = haloPaint.strokeWidth / 2f
                canvas.drawCircle(points.first().point.x, points.first().point.y, radius, startPaint)
                canvas.drawCircle(points.last().point.x, points.last().point.y, radius, endPaint)
            } else {
                // Normal case: Highlight only the selected end
                if (selectedEnd != SelectedEnd.NONE) {
                    val endpointCirclePaint = Paint().apply {
                        style = Paint.Style.FILL
                        color = if (selectedEnd == SelectedEnd.START) Color.GREEN else Color.RED
                    }
                    val pointToHighlight = if (selectedEnd == SelectedEnd.START) points.first().point else points.last().point
                    val radius = haloPaint.strokeWidth / 2f
                    canvas.drawCircle(pointToHighlight.x, pointToHighlight.y, radius, endpointCirclePaint)
                }
            }
        }
        
        // 3. Draw the actual stroke on top
        drawPoints(canvas, points, scaledPaint)
    }

    fun setColor(color: Int, applyToLast: Boolean = false) {
        currentPaint.color = color
        if (applyToLast && strokes.isNotEmpty()) {
            strokes.last().paint.color = color
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    fun setStrokeWidth(px: Float, applyToLast: Boolean = false) {
        val w = max(1f, min(120f, px))
        currentPaint.strokeWidth = w
        if (applyToLast && strokes.isNotEmpty()) {
            strokes.last().paint.strokeWidth = w
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    fun undo() {
        if (canRewind) {
            navigateToHistoryState(currentHistoryPosition - 1)
        }
    }

    fun redo() {
        if (canFF) {
            navigateToHistoryState(currentHistoryPosition + 1)
        }
    }

    fun clearAll() {
        strokes.clear()
        undone.clear()
        currentPoints.clear()
        totalScale = 1.0f
        redrawHistory()
        listener?.onStateChanged()
    }

    private fun redrawHistory(canvas: Canvas? = null, scale: Float = 1.0f) {
        val c = canvas ?: backingCanvas ?: return
        if (canvas == null) {
            c.drawColor(Color.WHITE, PorterDuff.Mode.SRC)
        }
        
        val tempPaint = Paint()

        // Draw future strokes with 25% alpha
        for (s in undone.reversed()) {
            tempPaint.set(s.paint)
            if(canvas != null) tempPaint.strokeWidth = max(1f / scale, s.paint.strokeWidth)
            val originalAlpha = tempPaint.alpha
            tempPaint.alpha = (originalAlpha * 0.25f).toInt()
            drawPoints(c, s.points, tempPaint)
        }

        // Draw past strokes at full opacity
        for (s in strokes) {
            tempPaint.set(s.paint)
            if(canvas != null) tempPaint.strokeWidth = max(1f / scale, s.paint.strokeWidth)
            drawPoints(c, s.points, tempPaint)
        }

        if (canvas == null) invalidate()
    }

    private fun drawPoints(canvas: Canvas?, points: List<PathPoint>, paint: Paint) {
        if (canvas == null || points.size < 2) return
        val path = Path()
        path.moveTo(points.first().point.x, points.first().point.y)
        for (i in 1 until points.size) {
            path.lineTo(points[i].point.x, points[i].point.y)
        }
        canvas.drawPath(path, paint)
    }

    private fun defaultPaint(_color: Int, _widthPx: Float) = Paint().apply {
        isAntiAlias = true
        isDither = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = _color
        strokeWidth = _widthPx
    }
}
