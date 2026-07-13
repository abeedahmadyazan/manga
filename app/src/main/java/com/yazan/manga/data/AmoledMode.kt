package com.yazan.manga.data

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.view.View

/**
 * AMOLED Black Mode — pure black backgrounds (#000000) for OLED/AMOLED screens.
 *
 * When enabled, overrides the activity's window background and root view
 * background to pure black. This saves battery on AMOLED displays (pixels
 * are literally turned off) and is easier on the eyes in dark environments.
 *
 * The setting is stored in SharedPreferences("settings") under "amoled_mode".
 * Applied in every Activity's onCreate via applyIfEnabled().
 */
object AmoledMode {
    private const val PREFS = "settings"
    private const val KEY = "amoled_mode"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }

    /**
     * Apply AMOLED black to an activity's window + decorView.
     * Call this in onCreate AFTER setContentView.
     * If AMOLED is disabled, this is a no-op.
     */
    fun applyIfEnabled(activity: Activity) {
        if (!isEnabled(activity)) return
        try {
            // Window background → pure black
            activity.window.decorView.setBackgroundColor(Color.BLACK)
            // Status bar + nav bar → pure black
            activity.window.statusBarColor = Color.BLACK
            activity.window.navigationBarColor = Color.BLACK
        } catch (e: Exception) {}
    }
}
