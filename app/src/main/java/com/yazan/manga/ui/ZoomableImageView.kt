package com.yazan.manga.ui

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView that supports zoom (via buttons) + pan (drag with one finger
 * when zoomed in >1x).
 *
 * - The zoom level is controlled externally via scaleX/scaleY (set by the
 *   +/- buttons in ReaderActivity).
 * - When the scale is >1, dragging with one finger pans the image so the
 *   user can see all parts of it.
 * - Double-tap resets the pan position.
 * - Single tap is forwarded to the OnClickListener (toggles the UI bars).
 *
 * NOTE: pinch-to-zoom is intentionally NOT supported (it was removed because
 * it caused issues). Zoom is controlled only by the +/- buttons.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    // Pan offsets
    private var posX = 0f
    private var posY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Reset pan on double-tap
            posX = 0f
            posY = 0f
            translationX = 0f
            translationY = 0f
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // Forward to the OnClickListener (toggles UI bars)
            return performClick()
        }
    })

    init {
        scaleType = ScaleType.FIT_CENTER
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                // Pan with one finger only when zoomed in (scaleX > 1)
                if (scaleX > 1.0f && event.pointerCount == 1) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    posX += dx
                    posY += dy
                    lastTouchX = event.x
                    lastTouchY = event.y
                    translationX = posX
                    translationY = posY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // If zoomed back to 1x, reset the position
                if (scaleX <= 1.0f) {
                    posX = 0f
                    posY = 0f
                    translationX = 0f
                    translationY = 0f
                }
            }
        }
        return true
    }

    /** Reset the zoom and pan to defaults (called when changing pages). */
    fun resetZoom() {
        posX = 0f
        posY = 0f
        translationX = 0f
        translationY = 0f
        // Reset the matrix scale type to FIT_CENTER so the image fits the screen
        scaleType = ScaleType.FIT_CENTER
        imageMatrix = null
    }
}
