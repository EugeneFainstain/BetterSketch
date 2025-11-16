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

    // Stroke in progress
    private val strokeInProgressPoints = mutableListOf<PathPoint>()
    private var strokeInProgressDistance = 0f
    var currentPaint = defaultPaint(Color.BLACK, 12f)

    // History for undo/redo
    private val strokes = mutableListOf<Stroke>()
    private val undone = ArrayDeque<Stroke>()
    private var selectedStrokeIdx: Int = -1

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
    private val selectedStroke: Stroke? get() = strokes.getOrNull(selectedStrokeIdx)

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
            redrawHistory(canvas, totalScale)
        } else {
            backingBitmap?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        }

        // 2. Draw the live part
        if (isEditingMode) {
            if (strokeInProgressPoints.isNotEmpty()) {
                drawStrokeWithHalo(canvas, strokeInProgressPoints, currentPaint)
            } else {
                selectedStroke?.let {
                    drawStrokeWithHalo(canvas, it.points, it.paint)
                }
            }
        } else { // Drawing mode
            if (strokeInProgressPoints.isNotEmpty()) {
                drawPoints(canvas, strokeInProgressPoints, currentPaint)
            } else if (selectedEnd != SelectedEnd.NONE) {
                selectedStroke?.let {
                    drawStrokeWithHalo(canvas, it.points, it.paint)
                }
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
            if (strokeInProgressPoints.isNotEmpty()) {
                if (strokeInProgressPoints.size > 5) {
                    commitStrokeInProgress()
                } else {
                    strokeInProgressPoints.clear()
                }
            }
            lastDistance = distance(event)
            lastAngle = angle(event)
            lastMidpoint.set(midpoint)
        } else if (action == MotionEvent.ACTION_MOVE) {
            val newDist = distance(event)
            val newAngle = angle(event)
            
            val scale = if (lastDistance > 0) newDist / lastDistance else 1f
            val rotate = newAngle - lastAngle
            val dx = midpoint.x - lastMidpoint.x
            val dy = midpoint.y - lastMidpoint.y
            
            val deltaMatrix = Matrix()
            if (isEditingMode && selectedEnd != SelectedEnd.NONE) {
                selectedStroke?.let {
                    val bounds = it.getBounds()
                    val centerX = bounds.centerX()
                    val centerY = bounds.centerY()
                    deltaMatrix.postTranslate(dx, dy)
                    deltaMatrix.postScale(scale, scale, centerX + dx, centerY + dy)
                    deltaMatrix.postRotate(rotate, centerX + dx, centerY + dy)
                    transformStroke(it, deltaMatrix)
                }
            } else {
                totalScale *= scale
                deltaMatrix.postTranslate(dx, dy)
                deltaMatrix.postScale(scale, scale, midpoint.x, midpoint.y)
                deltaMatrix.postRotate(rotate, midpoint.x, midpoint.y)
                transformAllStrokes(deltaMatrix)
            }

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
                    val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
                    val dx = abs(x - downX)
                    val dy = abs(y - downY)
                    val dt = System.currentTimeMillis() - downTime
                    if (dx < touchSlop && dy < touchSlop && dt < ViewConfiguration.getTapTimeout() * 2) {
                        // This is a tap, abandon the stroke and find the closest one
                        strokeInProgressPoints.clear()
                        findClosestStroke(PointF(x,y))
                        invalidate()
                    } else {
                        touchUp()
                    }
                 }
             }
         }
    }

    private fun transformStroke(stroke: Stroke, matrix: Matrix) {
        val scale = getScaleFromMatrix(matrix)
        stroke.paint.strokeWidth = stroke.paint.strokeWidth * scale
        stroke.points.forEach { pathPoint ->
            val point = floatArrayOf(pathPoint.point.x, pathPoint.point.y)
            matrix.mapPoints(point)
            pathPoint.point.set(point[0], point[1])
        }
        redrawHistory()
    }
    
    private fun transformAllStrokes(matrix: Matrix) {
        (strokes + undone).forEach { stroke ->
            transformStroke(stroke, matrix)
        }
    }

    private fun getScaleFromMatrix(matrix: Matrix): Float {
        val values = FloatArray(9)
        matrix.getValues(values)
        return values[Matrix.MSCALE_X]
    }

    private fun touchStart(x: Float, y: Float) {
        selectedStrokeIdx = -1
        selectedEnd = SelectedEnd.NONE
        strokeInProgressPoints.clear()
        strokeInProgressDistance = 0f
        strokeInProgressPoints.add(PathPoint(PointF(x, y), 0f))
        listener?.onStateChanged()
    }

    private fun touchMove(x: Float, y: Float) {
        if (strokeInProgressPoints.isEmpty()) return
        val lastPoint = strokeInProgressPoints.last().point
        val dx = x - lastPoint.x
        val dy = y - lastPoint.y
        strokeInProgressDistance += sqrt(dx * dx + dy * dy)
        strokeInProgressPoints.add(PathPoint(PointF(x, y), strokeInProgressDistance))
        listener?.onStateChanged()
    }

    private fun touchUp() {
        if (strokeInProgressPoints.isNotEmpty()) {
            commitStrokeInProgress()
        }
    }

    private fun commitStrokeInProgress() {
        val newStroke = Stroke(strokeInProgressPoints.toMutableList(), Paint(currentPaint), strokeInProgressDistance)
        strokes.add(newStroke)
        selectedStrokeIdx = -1
        selectedEnd = SelectedEnd.NONE
        strokeInProgressPoints.clear()
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
        var minDistance = Float.MAX_VALUE
        var closestIndex = -1

        strokes.forEachIndexed { index, stroke ->
            for (pathPoint in stroke.points) {
                val dx = pathPoint.point.x - tapPoint.x
                val dy = pathPoint.point.y - tapPoint.y
                val distance = sqrt(dx * dx + dy * dy)
                if (distance < minDistance) {
                    minDistance = distance
                    closestIndex = index
                }
            }
        }

        if (closestIndex != -1) {
            selectedStrokeIdx = closestIndex
            val closestStroke = strokes[closestIndex]
            val startPoint = closestStroke.points.first().point
            val endPoint = closestStroke.points.last().point
            val distToStart = sqrt((startPoint.x - tapPoint.x) * (startPoint.x - tapPoint.x) + (startPoint.y - tapPoint.y) * (startPoint.y - tapPoint.y))
            val distToEnd = sqrt((endPoint.x - tapPoint.x) * (endPoint.x - tapPoint.x) + (endPoint.y - tapPoint.y) * (endPoint.y - tapPoint.y))
            
            selectedEnd = if (distToStart < distToEnd) SelectedEnd.START else SelectedEnd.END
            
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    fun deselectAllStrokes() {
        selectedStrokeIdx = -1
        selectedEnd = SelectedEnd.NONE
        redrawHistory()
        listener?.onStateChanged()
    }

    fun resetStrokeSelection() {
        selectedEnd = SelectedEnd.END
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
        selectedStrokeIdx = -1
        selectedEnd = SelectedEnd.NONE
        redrawHistory()
        listener?.onStateChanged()
    }

    fun deleteCurrentStroke() {
        if (selectedStrokeIdx != -1) {
            strokes.removeAt(selectedStrokeIdx)
            selectedStrokeIdx = -1
            selectedEnd = SelectedEnd.NONE
        } else if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
        }
        redrawHistory()
        listener?.onStateChanged()
    }

    fun getStrokeColors(): IntArray {
        val pastColors = strokes.map { it.paint.color }
        val futureColors = undone.reversed().map { it.paint.color }
        return (pastColors + futureColors).toIntArray()
    }

    fun moveStartPoint(dx: Float, dy: Float) {
        selectedStroke?.let {
            val totalDistance = it.totalDistance
            if (totalDistance == 0f) return 
            for (pathPoint in it.points) {
                val weight = 1.0f - (pathPoint.distance / totalDistance)
                pathPoint.point.offset(dx * weight, dy * weight)
            }
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    fun moveEndPoint(dx: Float, dy: Float) {
        selectedStroke?.let {
            val totalDistance = it.totalDistance
            if (totalDistance == 0f) return 
            for (pathPoint in it.points) {
                val weight = pathPoint.distance / totalDistance
                pathPoint.point.offset(dx * weight, dy * weight)
            }
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    private fun drawStrokeWithHalo(canvas: Canvas, points: List<PathPoint>, paint: Paint) {
        if (points.isEmpty()) return

        if (selectedEnd != SelectedEnd.NONE) {
            // 1. Draw the halo
            val haloPaint = Paint(paint).apply {
                color = Color.LTGRAY
                strokeWidth = paint.strokeWidth * 2 + 32f
            }
            drawPoints(canvas, points, haloPaint)

            // 2. Draw the endpoint indicator circles
            if (strokeInProgressPoints.isNotEmpty() && isEditingMode) {
                // Special case: Drawing a new stroke while in edit mode.
                val startPaint = Paint().apply { style = Paint.Style.FILL; color = Color.GREEN }
                val endPaint = Paint().apply { style = Paint.Style.FILL; color = Color.RED }
                val radius = haloPaint.strokeWidth / 2f
                canvas.drawCircle(points.first().point.x, points.first().point.y, radius, startPaint)
                canvas.drawCircle(points.last().point.x, points.last().point.y, radius, endPaint)
            } else if (selectedEnd != SelectedEnd.NONE) {
                // Normal case: A completed stroke is selected.
                val endpointCirclePaint = Paint().apply {
                    style = Paint.Style.FILL
                    color = if (selectedEnd == SelectedEnd.START) Color.GREEN else Color.RED
                }
                val pointToHighlight = if (selectedEnd == SelectedEnd.START) points.first().point else points.last().point
                val radius = haloPaint.strokeWidth / 2f
                canvas.drawCircle(pointToHighlight.x, pointToHighlight.y, radius, endpointCirclePaint)
            }
        }
        
        // 3. Draw the actual stroke on top
        drawPoints(canvas, points, paint)
    }

    fun setColor(color: Int, applyToSelected: Boolean = false) {
        currentPaint.color = color
        if (applyToSelected) {
            selectedStroke?.paint?.color = color
            redrawHistory()
            listener?.onStateChanged()
        }
    }

    fun setStrokeWidth(px: Float, applyToSelected: Boolean = false) {
        val w = max(1f, min(120f, px))
        currentPaint.strokeWidth = w
        if (applyToSelected) {
            selectedStroke?.paint?.strokeWidth = w
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
        strokeInProgressPoints.clear()
        selectedStrokeIdx = -1
        selectedEnd = SelectedEnd.NONE
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
        val isFadedMode = !isEditingMode && selectedEnd != SelectedEnd.NONE

        // Draw future strokes (always faded)
        for (s in undone.reversed()) {
            tempPaint.set(s.paint)
            if(canvas != null) tempPaint.strokeWidth = s.paint.strokeWidth
            tempPaint.alpha = (tempPaint.alpha * 0.25f).toInt()
            drawPoints(c, s.points, tempPaint)
        }

        // Draw past strokes
        for ((index, s) in strokes.withIndex()) {
            tempPaint.set(s.paint)
            if(canvas != null) tempPaint.strokeWidth = s.paint.strokeWidth
            
            if (isFadedMode && index != selectedStrokeIdx) {
                 tempPaint.alpha = (tempPaint.alpha * 0.25f).toInt()
            }

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
