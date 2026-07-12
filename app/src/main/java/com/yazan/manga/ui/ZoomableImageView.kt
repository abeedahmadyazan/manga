package com.yazan.manga.ui

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.OverScroller
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView with double-tap zoom + pan support.
 * 
 * - Double-tap: zoom to 2.5x at tap position, or reset to 1x
 * - Pan with 1 finger when zoomed in (>1x)
 * - Clamped edges
 * - At 1x: allows parent scroll (RecyclerView)
 * - At >1x: captures touch events
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

    // When false (webtoon mode), zoom is disabled and touch events pass to parent
    private var zoomEnabled = true

    fun setZoomEnabled(enabled: Boolean) {
        zoomEnabled = enabled
        if (!enabled) {
            // Reset zoom to 1x
            zoomTo(MIN_SCALE)
        }
    }

    private var scaleFactor = 1.0f
    private var posX = 0f
    private var posY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scaleFactor > MIN_SCALE) {
                // Reset to 1x
                scaleFactor = MIN_SCALE
                posX = 0f
                posY = 0f
                scaleX = 1.0f
                scaleY = 1.0f
                translationX = 0f
                translationY = 0f
            } else {
                // Zoom to 2.5x
                scaleFactor = DOUBLE_TAP_SCALE
                scaleX = DOUBLE_TAP_SCALE
                scaleY = DOUBLE_TAP_SCALE
                // Adjust position to zoom around the tap point
                val cx = width / 2f
                val cy = height / 2f
                posX = (cx - e.x) * (scaleFactor - 1f)
                posY = (cy - e.y) * (scaleFactor - 1f)
                clampTranslation()
                translationX = posX
                translationY = posY
            }
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
        // In webtoon mode, disable zoom — let parent (RecyclerView) handle scroll
        if (!zoomEnabled) return false
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                if (scaleFactor > MIN_SCALE) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaleFactor > MIN_SCALE && event.pointerCount == 1) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val dx = (event.x - lastTouchX) * panSpeedMultiplier()
                    val dy = (event.y - lastTouchY) * panSpeedMultiplier()
                    posX += dx
                    posY += dy
                    lastTouchX = event.x
                    lastTouchY = event.y
                    clampTranslation()
                    translationX = posX
                    translationY = posY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (scaleFactor <= MIN_SCALE) {
                    posX = 0f
                    posY = 0f
                    translationX = 0f
                    translationY = 0f
                } else {
                    clampTranslation()
                    translationX = posX
                    translationY = posY
                }
            }
        }
        return true
    }

    private fun panSpeedMultiplier(): Float {
        return (1.0f + (scaleX - 1.0f) * 0.5f).coerceIn(1.0f, 3.5f)
    }

    private fun clampTranslation() {
        if (width <= 0 || height <= 0) return
        val scale = scaleX
        if (scale <= 1.0f) {
            posX = 0f
            posY = 0f
            return
        }
        val maxX = (scale - 1f) * (width / 2f)
        val maxY = (scale - 1f) * (height / 2f)
        posX = posX.coerceIn(-maxX, maxX)
        posY = posY.coerceIn(-maxY, maxY)
    }

    fun resetZoom() {
        scaleFactor = MIN_SCALE
        posX = 0f
        posY = 0f
        scaleX = 1.0f
        scaleY = 1.0f
        translationX = 0f
        translationY = 0f
        scaleType = ScaleType.FIT_CENTER
    }

    /**
     * Programmatically zoom IN by a fixed step (e.g. when the user taps a
     * zoom-in button in the reader toolbar). Animates the scale change for
     * a smooth feel.
     */
    fun zoomIn() {
        val target = (scaleFactor * 1.4f).coerceAtMost(MAX_SCALE)
        animateToZoom(target)
    }

    /**
     * Programmatically zoom OUT by a fixed step (opposite of zoomIn).
     * At 1x it's a no-op so the user can't shrink below fit-to-screen.
     */
    fun zoomOut() {
        val target = (scaleFactor / 1.4f).coerceAtLeast(MIN_SCALE)
        animateToZoom(target)
    }

    private fun animateToZoom(target: Float) {
        if (target == scaleFactor) return
        val from = scaleFactor
        scaleFactor = target
        if (target <= MIN_SCALE) {
            // Reset to fit
            posX = 0f
            posY = 0f
        }
        animate()
            .scaleX(target)
            .scaleY(target)
            .translationX(posX)
            .translationY(posY)
            .setDuration(180)
            .start()
    }
}
