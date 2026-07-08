package com.yazan.manga

import android.content.Context
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        // Dark mode — actually apply it via AppCompatDelegate
        findViewById<SwitchCompat>(R.id.switchDarkMode).apply {
            isChecked = prefs.getBoolean("dark_mode", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("dark_mode", isChecked).apply()
                AppCompatDelegate.setDefaultNightMode(
                    if (isChecked) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                )
                toast("الوضع الليلي: ${if (isChecked) "مفعل" else "معطل"}")
            }
        }

        // Data saver — affects image quality in the reader (handled by SettingsProvider)
        findViewById<SwitchCompat>(R.id.switchDataSaver).apply {
            isChecked = prefs.getBoolean("data_saver", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("data_saver", isChecked).apply()
                toast("حفظ البيانات: ${if (isChecked) "مفعل" else "معطل"}")
            }
        }

        // Auto-download — download new chapters automatically when available
        findViewById<SwitchCompat>(R.id.switchAutoDownload).apply {
            isChecked = prefs.getBoolean("auto_download", false)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("auto_download", isChecked).apply()
                toast("التحميل التلقائي: ${if (isChecked) "مفعل" else "معطل"}")
            }
        }

        // Notifications — chapter notifications (respects this toggle)
        findViewById<SwitchCompat>(R.id.switchNotifications).apply {
            isChecked = prefs.getBoolean("notifications", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("notifications", isChecked).apply()
                toast("الإشعارات: ${if (isChecked) "مفعل" else "معطل"}")
            }
        }

        // About — no data source mentioned (privacy)
        findViewById<TextView>(R.id.tvAbout).text =
            "تطبيق مانجا v1.0.0\n© 2026"
    }

    override fun onResume() {
        super.onResume()
        // Re-apply dark mode setting when returning to this activity
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.getBoolean("dark_mode", true)) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** Helper for other activities to check settings. */
        fun isDataSaverOn(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("data_saver", true)

        fun isAutoDownloadOn(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("auto_download", false)

        fun isNotificationsOn(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("notifications", true)

        fun isDarkModeOn(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("dark_mode", true)
    }
}
