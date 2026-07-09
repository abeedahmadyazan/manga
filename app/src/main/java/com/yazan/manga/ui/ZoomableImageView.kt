package com.yazan.manga.ui

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * Professional pinch-to-zoom ImageView.
 * 
 * Features:
 * - Pinch to zoom (2 fingers) — smooth, like gallery apps
 * - Pan/drag with 1 finger when zoomed in (>1x)
 * - Double-tap to zoom in (2x) or reset to 1x
 * - Clamped edges (can't drag image off-screen)
 * - Smooth scaling with min=1x, max=5x
 * 
 * When inside a RecyclerView/ScrollView:
 * - At 1x scale: allows parent to scroll (vertical swipe)
 * - At >1x scale: captures touch events (prevents parent scroll)
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    companion object {
        private const val MIN_SCALE = 1.0f
        private const val MAX_SCALE = 5.0f
        private const val DOUBLE_TAP_SCALE = 2.5f
    }

    private var scaleFactor = 1.0f
    private var posX = 0f
    private var posY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = scaleFactor * detector.scaleFactor
            scaleFactor = newScale.coerceIn(MIN_SCALE, MAX_SCALE)
            
            // Adjust position to zoom around the focal point
            val focusX = detector.focusX
            val focusY = detector.focusY
            val scaleChange = scaleFactor / (scaleFactor / detector.scaleFactor)
            posX = (posX - (focusX - width / 2f)) * detector.scaleFactor + (focusX - width / 2f)
            posY = (posY - (focusY - height / 2f)) * detector.scaleFactor + (focusY - height / 2f)
            
            applyTransform()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scaleFactor > MIN_SCALE) {
                // Reset to 1x
                scaleFactor = MIN_SCALE
                posX = 0f
                posY = 0f
            } else {
                // Zoom to 2.5x at the double-tap position
                scaleFactor = DOUBLE_TAP_SCALE
                posX = (width / 2f - e.x) * (scaleFactor - 1f)
                posY = (height / 2f - e.y) * (scaleFactor - 1f)
            }
            applyTransform()
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            return performClick()
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    private fun applyTransform() {
        val matrix = Matrix()
        // Center the image
        val drawable = drawable
        if (drawable != null) {
            val drawableWidth = drawable.intrinsicWidth
            val drawableHeight = drawable.intrinsicHeight
            val viewWidth = width
            val viewHeight = height
            
            if (viewWidth > 0 && viewHeight > 0 && drawableWidth > 0 && drawableHeight > 0) {
                val scale = minOf(viewWidth.toFloat() / drawableWidth, viewHeight.toFloat() / drawableHeight)
                val totalScale = scale * scaleFactor
                
                // Center
                val dx = (viewWidth - drawableWidth * totalScale) / 2f
                val dy = (viewHeight - drawableHeight * totalScale) / 2f
                
                matrix.setScale(totalScale, totalScale)
                matrix.postTranslate(dx + posX, dy + posY)
                
                // Clamp position
                clampPosition()
                matrix.reset()
                matrix.setScale(totalScale, totalScale)
                matrix.postTranslate(dx + posX, dy + posY)
            }
        }
        imageMatrix = matrix
    }

    private fun clampPosition() {
        if (width <= 0 || height <= 0) return
        val drawable = drawable ?: return
        val drawableWidth = drawable.intrinsicWidth
        val drawableHeight = drawable.intrinsicHeight
        val viewWidth = width
        val viewHeight = height
        
        val scale = minOf(viewWidth.toFloat() / drawableWidth, viewHeight.toFloat() / drawableHeight) * scaleFactor
        val scaledWidth = drawableWidth * scale
        val scaledHeight = drawableHeight * scale
        
        // Max pan = how much the image exceeds the view
        val maxX = ((scaledWidth - viewWidth) / 2f).coerceAtLeast(0f)
        val maxY = ((scaledHeight - viewHeight) / 2f).coerceAtLeast(0f)
        
        posX = posX.coerceIn(-maxX, maxX)
        posY = posY.coerceIn(-maxY, maxY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                // If zoomed in, tell parent not to scroll
                if (scaleFactor > MIN_SCALE) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleFactor > MIN_SCALE && event.pointerCount == 1) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    posX += dx
                    posY += dy
                    lastTouchX = event.x
                    lastTouchY = event.y
                    clampPosition()
                    applyTransform()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (scaleFactor <= MIN_SCALE) {
                    posX = 0f
                    posY = 0f
                    applyTransform()
                }
            }
        }
        return true
    }

    fun resetZoom() {
        scaleFactor = MIN_SCALE
        posX = 0f
        posY = 0f
        applyTransform()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTransform()
    }
}
