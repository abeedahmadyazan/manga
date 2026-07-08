package com.yazan.manga.ui

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView that supports pinch-to-zoom and pan when zoomed in.
 *
 * - Pinch with two fingers to zoom in/out at the focal point.
 * - Drag with one finger to pan when zoomed in (>1x).
 * - Double-tap to reset to 1x.
 * - When zoomed in, prevents the parent RecyclerView from intercepting touch
 *   events so the user can pan freely.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    private var scaleFactor = 1.0f
    private val minScale = 1.0f
    private val maxScale = 5.0f

    // Translation (pan) offsets
    private var posX = 0f
    private var posY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // The focal point of the pinch (where the user's fingers converge)
    private var focusX = 0f
    private var focusY = 0f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            focusX = detector.focusX
            focusY = detector.focusY
            // Stop the parent from scrolling while we zoom
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = (scaleFactor * detector.scaleFactor).coerceIn(minScale, maxScale)
            // Adjust the translation so the focal point stays under the fingers
            val scaleChange = newScale / scaleFactor
            posX = focusX - (focusX - posX) * scaleChange
            posY = focusY - (focusY - posY) * scaleChange
            scaleFactor = newScale
            applyTransform()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Reset zoom on double-tap
            if (scaleFactor > 1.0f) {
                scaleFactor = 1.0f
                posX = 0f
                posY = 0f
                applyTransform()
            } else {
                // Quick zoom in to 2.5x at the tap point
                scaleFactor = 2.5f
                posX = e.x
                posY = e.y
                applyTransform()
            }
            return true
        }
    })

    init {
        scaleType = ScaleType.FIT_CENTER
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                // Pan with one finger when zoomed in
                if (scaleFactor > 1.0f && event.pointerCount == 1) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    posX += dx
                    posY += dy
                    lastTouchX = event.x
                    lastTouchY = event.y
                    applyTransform()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // If we zoomed back to 1x, reset the position
                if (scaleFactor <= 1.0f) {
                    posX = 0f
                    posY = 0f
                    applyTransform()
                }
            }
        }
        return true
    }

    private fun applyTransform() {
        val matrix = Matrix()
        // Apply scale around the focal point, then translate
        matrix.setScale(scaleFactor, scaleFactor, focusX, focusY)
        matrix.postTranslate(posX - focusX * (scaleFactor - 1) / scaleFactor * 0, posY)
        // Simpler: just use setScale + postTranslate with posX/posy as the translation
        val m = Matrix()
        m.setScale(scaleFactor, scaleFactor, focusX, focusY)
        m.postTranslate(posX - focusX, posY - focusY)
        imageMatrix = m
        // Use MATRIX scale type so our matrix takes effect
        if (scaleType != ScaleType.MATRIX) {
            scaleType = ScaleType.MATRIX
        }
    }

    /** Reset the zoom to 1x (called when the page is recycled or rebound). */
    fun resetZoom() {
        scaleFactor = 1.0f
        posX = 0f
        posY = 0f
        scaleType = ScaleType.FIT_CENTER
        imageMatrix = null
    }
}
