package com.yazan.manga.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A simple pie chart that shows the distribution of manga across 4 custom lists.
 *
 * - favorites  → primary (emerald)
 * - watchLater → accent (purple)
 * - wantToWatch→ warning (amber)
 * - completed  → success (green)
 *
 * If all lists are empty, draws an empty ring.
 */
class PieChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val favoritesColor = Color.parseColor("#10b981") // emerald
    private val watchLaterColor = Color.parseColor("#8b5cf6") // purple
    private val wantToWatchColor = Color.parseColor("#f59e0b") // amber
    private val completedColor = Color.parseColor("#3b82f6") // blue
    private val emptyColor = Color.parseColor("#2a2a3a") // muted

    private val slicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0a0a0f") // background
    }
    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val centerSubTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#9ca3af")
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    private val rectF = RectF()

    var favoritesCount: Int = 0
    var watchLaterCount: Int = 0
    var wantToWatchCount: Int = 0
    var completedCount: Int = 0

    fun setCounts(fav: Int, watchLater: Int, wantToWatch: Int, completed: Int) {
        favoritesCount = fav
        watchLaterCount = watchLater
        wantToWatchCount = wantToWatch
        completedCount = completed
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f - 8f
        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)

        val total = favoritesCount + watchLaterCount + wantToWatchCount + completedCount

        if (total == 0) {
            // Empty state — draw a muted ring
            slicePaint.color = emptyColor
            canvas.drawCircle(cx, cy, radius, slicePaint)
            // Donut hole
            centerPaint.color = Color.parseColor("#0a0a0f")
            canvas.drawCircle(cx, cy, radius * 0.55f, centerPaint)
            centerTextPaint.textSize = 36f
            canvas.drawText("0", cx, cy + 12f, centerTextPaint)
            centerSubTextPaint.textSize = 18f
            canvas.drawText("YZ", cx, cy + 38f, centerSubTextPaint)
            return
        }

        // Draw slices
        var startAngle = -90f // start at top
        val slices = listOf(
            favoritesCount to favoritesColor,
            watchLaterCount to watchLaterColor,
            wantToWatchCount to wantToWatchColor,
            completedCount to completedColor
        )

        for ((count, color) in slices) {
            if (count == 0) continue
            val sweep = 360f * count / total
            slicePaint.color = color
            canvas.drawArc(rectF, startAngle, sweep, true, slicePaint)
            startAngle += sweep
        }

        // Donut hole (makes it a ring chart)
        centerPaint.color = Color.parseColor("#0a0a0f")
        canvas.drawCircle(cx, cy, radius * 0.55f, centerPaint)

        // Center text — total count
        centerTextPaint.textSize = 48f
        canvas.drawText(total.toString(), cx, cy + 16f, centerTextPaint)
        centerSubTextPaint.textSize = 20f
        canvas.drawText("YZ", cx, cy + 44f, centerSubTextPaint)
    }
}
