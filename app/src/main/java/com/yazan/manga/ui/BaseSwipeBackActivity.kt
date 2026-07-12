package com.yazan.manga.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.appcompat.app.AppCompatActivity

/**
 * Edge-swipe-to-go-back.
 *
 * Swiping from the left edge of the screen toward the right slides the current
 * activity out and finishes it — exactly like the system gesture in modern apps.
 * This masks the (sometimes laggy) back-button transition because the content
 * follows the finger instantly.
 *
 * The activity window MUST be translucent (see Theme.MangaApp.SwipeBack) so the
 * previous activity is revealed underneath as the current one slides away.
 *
 * Sub-activities may override [canSwipeBack] to conditionally disable the gesture
 * (e.g. ReaderActivity disables it in the middle of horizontal page paging).
 */
open class BaseSwipeBackActivity : AppCompatActivity() {

    private val touchSlop: Int by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    private val edgeSize: Int by lazy { (28 * resources.displayMetrics.density).toInt() }
    private val screenWidth: Int by lazy { resources.displayMetrics.widthPixels }

    private var downX = 0f
    private var downY = 0f
    private var edgeDown = false
    private var swiping = false
    private var childCancelled = false
    // Captured at ACTION_DOWN so the gesture's behavior is locked for its duration
    // (e.g. ReaderActivity's canSwipeBack depends on the current page, which must
    // not flip mid-gesture).
    private var gestureSwipeEnabled = true

    /** Whether the swipe-back gesture is currently allowed. Override to restrict. */
    protected open fun canSwipeBack(): Boolean = true

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSwipeEnabled = canSwipeBack()
                downX = ev.rawX
                downY = ev.rawY
                edgeDown = gestureSwipeEnabled && ev.rawX <= edgeSize
                swiping = false
                childCancelled = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (edgeDown && !swiping) {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    // Only steal clearly-horizontal, rightward drags from the edge.
                    if (dx > touchSlop && dx > Math.abs(dy) * 1.5f) {
                        swiping = true
                        // Cancel any gesture the children already started so they
                        // don't keep scrolling/selecting while we take over.
                        if (!childCancelled) {
                            val cancel = MotionEvent.obtain(ev)
                            cancel.action = MotionEvent.ACTION_CANCEL
                            window.decorView.dispatchTouchEvent(cancel)
                            cancel.recycle()
                            childCancelled = true
                        }
                    }
                }
                if (swiping) {
                    val dx = (ev.rawX - downX).coerceAtLeast(0f)
                    window.decorView.translationX = dx
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (swiping) {
                    val dx = window.decorView.translationX
                    if (dx > screenWidth * 0.30f) {
                        animateOutAndFinish()
                    } else {
                        animateBack()
                    }
                    swiping = false
                    edgeDown = false
                    return true
                }
                resetState()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun resetState() {
        edgeDown = false
        swiping = false
        childCancelled = false
    }

    private fun animateBack() {
        val start = window.decorView.translationX
        ValueAnimator.ofFloat(start, 0f).apply {
            duration = 200
            addUpdateListener { window.decorView.translationX = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { resetState() }
            })
            start()
        }
    }

    private fun animateOutAndFinish() {
        val start = window.decorView.translationX
        ValueAnimator.ofFloat(start, screenWidth.toFloat()).apply {
            duration = 220
            addUpdateListener { window.decorView.translationX = it.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    finish()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(0, 0)
                }
            })
            start()
        }
    }
}
