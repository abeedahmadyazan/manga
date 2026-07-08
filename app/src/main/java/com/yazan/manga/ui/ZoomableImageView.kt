package com.yazan.manga.ui

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView that supports zoom (via buttons) + pan (drag with one finger
 * when zoomed in >1x), with clamping so the image can't be dragged past
 * its own edges.
 *
 * - The zoom level is controlled externally via scaleX/scaleY (set by the
 *   +/- buttons in ReaderActivity).
 * - When the scale is >1, dragging with one finger pans the image. The pan
 *   speed is multiplied by a factor so it feels responsive even at high
 *   zoom levels. The translation is clamped so the image edges are always
 *   visible (you can't drag it completely off-screen).
 * - Double-tap resets the pan position.
 * - Single tap is forwarded to the OnClickListener (toggles the UI bars).
 *
 * NOTE: pinch-to-zoom is intentionally NOT supported. Zoom is controlled
 * only by the +/- buttons.
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

    // Multiplier to make panning feel faster/more responsive — scales with
    // the zoom level so high zoom = faster pan (less finger travel needed).
    private fun panSpeedMultiplier(): Float {
        // At scale=1, multiplier is 1.5x. At scale=5, it's ~3.5x.
        // This prevents the jitter at low zoom (where small finger movements
        // would otherwise cause large jumps) while keeping high zoom responsive.
        return (1.0f + (scaleX - 1.0f) * 0.5f).coerceIn(1.0f, 3.5f)
    }

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
                    // Apply the speed multiplier so panning is faster
                    val dx = (event.x - lastTouchX) * panSpeedMultiplier()
                    val dy = (event.y - lastTouchY) * panSpeedMultiplier()
                    posX += dx
                    posY += dy
                    lastTouchX = event.x
                    lastTouchY = event.y
                    // Clamp the translation so the image can't be dragged past its edges
                    clampTranslation()
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
                } else {
                    // Re-clamp on release in case the scale changed
                    clampTranslation()
                    translationX = posX
                    translationY = posY
                }
            }
        }
        return true
    }

    /**
     * Clamp posX/posY so the image can't be dragged past its own edges.
     * The max pan distance is (scale - 1) * halfDimension, because at scale=1
     * there's no room to pan, and at scale=2 there's half the dimension of room.
     */
    private fun clampTranslation() {
        if (width <= 0 || height <= 0) return
        val scale = scaleX
        // At scale <= 1, there's no room to pan (the image fits within the view)
        if (scale <= 1.0f) {
            posX = 0f
            posY = 0f
            return
        }
        // Max pan on each axis = (scale - 1) * (view dimension / 2)
        val maxX = (scale - 1f) * (width / 2f)
        val maxY = (scale - 1f) * (height / 2f)
        posX = posX.coerceIn(-maxX, maxX)
        posY = posY.coerceIn(-maxY, maxY)
    }

    /** Reset the zoom and pan to defaults (called when changing pages). */
    fun resetZoom() {
        posX = 0f
        posY = 0f
        translationX = 0f
        translationY = 0f
        scaleType = ScaleType.FIT_CENTER
        imageMatrix = null
    }
}
