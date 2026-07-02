package com.pawchive.ui.widget

import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val matrixValues = FloatArray(9)
    private val scaleMatrix = Matrix()
    private var currentScale = 1f
    private var minScale = 1f
    private var maxScale = 3f
    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false
    private var startPoint = PointF()
    private var onLongPressListener: (() -> Unit)? = null
    private var onTapListener: (() -> Unit)? = null

    private val scaleGestureDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newScale = currentScale * scaleFactor
            val clampedScale = max(minScale, min(newScale, maxScale))
            val actualFactor = clampedScale / currentScale

            scaleMatrix.postScale(
                actualFactor, actualFactor,
                detector.focusX, detector.focusY
            )
            currentScale = clampedScale
            imageMatrix = scaleMatrix
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            onLongPressListener?.invoke()
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTapListener?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val targetScale = if (currentScale > minScale * 1.5f) {
                minScale
            } else {
                minScale * 2
            }
            val factor = targetScale / currentScale
            scaleMatrix.postScale(factor, factor, e.x, e.y)
            currentScale = targetScale

            scaleMatrix.getValues(matrixValues)
            val transX = matrixValues[Matrix.MTRANS_X]
            val transY = matrixValues[Matrix.MTRANS_Y]
            val drawableWidth = drawable?.intrinsicWidth?.times(currentScale) ?: 0f
            val drawableHeight = drawable?.intrinsicHeight?.times(currentScale) ?: 0f

            var finalDx = 0f
            var finalDy = 0f

            if (drawableWidth > width) {
                if (transX > 0) finalDx = -transX
                if (transX + drawableWidth < width) finalDx = width - transX - drawableWidth
            } else {
                finalDx = (width - drawableWidth) / 2f - transX
            }

            if (drawableHeight > height) {
                if (transY > 0) finalDy = -transY
                if (transY + drawableHeight < height) finalDy = height - transY - drawableHeight
            } else {
                finalDy = (height - drawableHeight) / 2f - transY
            }

            scaleMatrix.postTranslate(finalDx, finalDy)
            imageMatrix = scaleMatrix
            return true
        }
    })

    private var isImageSized = false

    init {
        scaleType = ScaleType.MATRIX
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (drawable != null && !isImageSized) {
                centerImage()
                isImageSized = true
            }
        }
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        isImageSized = false
        if (drawable != null && width > 0 && height > 0) {
            centerImage()
            isImageSized = true
        }
    }

    override fun setImageResource(resId: Int) {
        super.setImageResource(resId)
        isImageSized = false
    }

    fun resetScale() {
        isImageSized = false
        if (drawable != null && width > 0 && height > 0) {
            centerImage()
            isImageSized = true
        }
    }

    fun setOnLongPressListener(listener: () -> Unit) {
        onLongPressListener = listener
    }

    fun setOnTapListener(listener: () -> Unit) {
        onTapListener = listener
    }

    fun setMaxScale(max: Float) {
        maxScale = max
    }

    private fun centerImage() {
        drawable?.let { d ->
            val viewWidth = width.toFloat()
            val viewHeight = height.toFloat()
            val drawableWidth = d.intrinsicWidth.toFloat()
            val drawableHeight = d.intrinsicHeight.toFloat()

            val scale = min(viewWidth / drawableWidth, viewHeight / drawableHeight)
            minScale = scale
            maxScale = scale * 4
            currentScale = scale

            val dx = (viewWidth - drawableWidth * scale) / 2f
            val dy = (viewHeight - drawableHeight * scale) / 2f

            scaleMatrix.reset()
            scaleMatrix.postScale(scale, scale)
            scaleMatrix.postTranslate(dx, dy)
            imageMatrix = scaleMatrix
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        super.onTouchEvent(event)
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (event.pointerCount == 1) {
                    startPoint.set(event.x, event.y)
                    lastX = event.x
                    lastY = event.y
                    isDragging = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    scaleMatrix.getValues(matrixValues)
                    val transX = matrixValues[Matrix.MTRANS_X]
                    val transY = matrixValues[Matrix.MTRANS_Y]
                    val drawableWidth = drawable?.intrinsicWidth?.times(currentScale) ?: 0f
                    val drawableHeight = drawable?.intrinsicHeight?.times(currentScale) ?: 0f

                    var finalDx = dx
                    var finalDy = dy

                    if (drawableWidth <= width) {
                        finalDx = 0f
                    } else {
                        if (transX + dx > 0) finalDx = -transX
                        if (transX + drawableWidth + dx < width) finalDx = width - transX - drawableWidth
                    }

                    if (drawableHeight <= height) {
                        finalDy = 0f
                    } else {
                        if (transY + dy > 0) finalDy = -transY
                        if (transY + drawableHeight + dy < height) finalDy = height - transY - drawableHeight
                    }

                    scaleMatrix.postTranslate(finalDx, finalDy)
                    imageMatrix = scaleMatrix
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return true
    }
}
