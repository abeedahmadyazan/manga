package com.yazan.manga

import android.content.Context
import android.os.Bundle
import android.view.View
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

        // Dark mode — the ONLY active setting. Actually applies via AppCompatDelegate.
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

        // Hide the other toggles — only dark mode is enabled for now
        findViewById<View>(R.id.switchDataSaver)?.visibility = View.GONE
        findViewById<View>(R.id.switchAutoDownload)?.visibility = View.GONE
        findViewById<View>(R.id.switchNotifications)?.visibility = View.GONE
        // Also hide the row labels (find their parent rows and hide them too)
        // The switches are inside card rows — hide the parent cards
        findViewById<View>(R.id.switchDataSaver)?.parent?.let { (it as? View)?.visibility = View.GONE }
        findViewById<View>(R.id.switchAutoDownload)?.parent?.let { (it as? View)?.visibility = View.GONE }
        findViewById<View>(R.id.switchNotifications)?.parent?.let { (it as? View)?.visibility = View.GONE }

        // About — no data source mentioned
        findViewById<TextView>(R.id.tvAbout).text = "تطبيق مانجا v1.0.0\n© 2026"
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        AppCompatDelegate.setDefaultNightMode(
            if (prefs.getBoolean("dark_mode", true)) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
