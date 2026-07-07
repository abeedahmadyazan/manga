package com.yazan.manga

import android.content.Context
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        findViewById<SwitchCompat>(R.id.switchDarkMode).apply {
            isChecked = prefs.getBoolean("dark_mode", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("dark_mode", isChecked).apply()
                toast("الوضع الليلي: ${if (isChecked) "مفعل" else "معطل"}")
            }
        }

        findViewById<SwitchCompat>(R.id.switchDataSaver).apply {
            isChecked = prefs.getBoolean("data_saver", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("data_saver", isChecked).apply()
                toast("حفظ البيانات: ${if (isChecked) "مفعل" else "معطل"}")
            }
        }

        findViewById<SwitchCompat>(R.id.switchAutoDownload).apply {
            isChecked = prefs.getBoolean("auto_download", false)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("auto_download", isChecked).apply()
                toast("التحميل التلقائي: ${if (isChecked) "مفعل" else "معطل"}")
            }
        }

        findViewById<SwitchCompat>(R.id.switchNotifications).apply {
            isChecked = prefs.getBoolean("notifications", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("notifications", isChecked).apply()
                toast("الإشعارات: ${if (isChecked) "مفعل" else "معطل"}")
            }
        }

        findViewById<TextView>(R.id.tvAbout).text =
            "تطبيق مانجا v1.0.0\nمصدر البيانات: 3asq.pro\n© 2026"
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
