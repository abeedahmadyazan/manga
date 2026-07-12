package com.yazan.manga

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AppCompatDelegate

class SettingsActivity : AppCompatActivity() {

    private val PREFS_NAME = "settings"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }

        // ===== المظهر =====
        val switchDarkMode = findViewById<SwitchCompat>(R.id.switchDarkMode)
        switchDarkMode.isChecked = prefs.getBoolean("dark_mode", true)
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("dark_mode", isChecked).apply()
            AppCompatDelegate.setDefaultNightMode(
                if (isChecked) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
        }

        // وضع القراءة
        val tvReadingMode = findViewById<TextView>(R.id.tvReadingMode)
        val readingMode = prefs.getString("reading_mode", "webtoon")
        tvReadingMode.text = if (readingMode == "webtoon") "مانهوا (تمرير)" else "مانجا (صفحات)"
        findViewById<View>(R.id.rowReadingMode).setOnClickListener {
            val options = arrayOf("📜 مانهوا (تمرير عمودي)", "📖 مانجا (صفحة لكل شاشة)")
            val current = if (readingMode == "webtoon") 0 else 1
            AlertDialog.Builder(this)
                .setTitle("وضع القراءة الافتراضي")
                .setSingleChoiceItems(options, current) { dialog, which ->
                    val mode = if (which == 0) "webtoon" else "manga"
                    prefs.edit().putString("reading_mode", mode).apply()
                    tvReadingMode.text = if (mode == "webtoon") "مانهوا (تمرير)" else "مانجا (صفحات)"
                    dialog.dismiss()
                    Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        // ===== القارئ =====
        val switchKeepScreenOn = findViewById<SwitchCompat>(R.id.switchKeepScreenOn)
        switchKeepScreenOn.isChecked = prefs.getBoolean("keep_screen_on", true)
        switchKeepScreenOn.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("keep_screen_on", isChecked).apply()
        }

        // جودة الصور
        val tvImageQuality = findViewById<TextView>(R.id.tvImageQuality)
        val quality = prefs.getString("image_quality", "high")
        tvImageQuality.text = when (quality) {
            "high" -> "عالية"
            "medium" -> "متوسطة"
            else -> "منخفضة"
        }
        findViewById<View>(R.id.rowImageQuality).setOnClickListener {
            val options = arrayOf("عالية (أفضل جودة)", "متوسطة (متوازن)", "منخفضة (أسرع)")
            val currentIdx = when (quality) {
                "high" -> 0
                "medium" -> 1
                else -> 2
            }
            AlertDialog.Builder(this)
                .setTitle("جودة الصور")
                .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                    val q = when (which) { 0 -> "high"; 1 -> "medium"; else -> "low" }
                    prefs.edit().putString("image_quality", q).apply()
                    tvImageQuality.text = when (q) {
                        "high" -> "عالية"; "medium" -> "متوسطة"; else -> "منخفضة"
                    }
                    dialog.dismiss()
                    Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        // ===== البيانات =====
        // مسح الكاش
        val tvCacheSize = findViewById<TextView>(R.id.tvCacheSize)
        updateCacheSize(tvCacheSize)
        findViewById<View>(R.id.rowClearCache).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("مسح الكاش")
                .setMessage("هل تريد مسح ذاكرة التخزين المؤقت؟")
                .setPositiveButton("مسح") { _, _ ->
                    Thread {
                        try {
                            com.yazan.manga.data.CacheManager.clearAllCache(this)
                            runOnUiThread {
                                updateCacheSize(tvCacheSize)
                                Toast.makeText(this, "تم المسح", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this, "تعذّر المسح", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.start()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        // مسح التاريخ
        findViewById<View>(R.id.rowClearHistory).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("مسح سجل القراءة")
                .setMessage("سيتم مسح كل سجل القراءة. لا يمكن التراجع.")
                .setPositiveButton("مسح") { _, _ ->
                    Thread {
                        try {
                            com.yazan.manga.data.ApiClient.clearHistory()
                            runOnUiThread {
                                Toast.makeText(this, "تم مسح السجل", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                Toast.makeText(this, "تعذّر المسح", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.start()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        // ===== حول التطبيق =====
        val tvAbout = findViewById<TextView>(R.id.tvAbout)
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val versionName = pInfo.versionName ?: "1.0"
            tvAbout.text = "YZ MANGA v${versionName}\n© 2026 يزن عبدالله"
        } catch (e: Exception) {}
    }

    private fun updateCacheSize(tv: TextView) {
        Thread {
            try {
                val cacheDir = cacheDir
                val size = getFolderSize(cacheDir)
                val sizeStr = if (size > 1024 * 1024) {
                    String.format("%.1f MB", size / (1024.0 * 1024.0))
                } else if (size > 1024) {
                    String.format("%.1f KB", size / 1024.0)
                } else {
                    "$size B"
                }
                runOnUiThread { tv.text = sizeStr }
            } catch (e: Exception) {
                runOnUiThread { tv.text = "—" }
            }
        }.start()
    }

    private fun getFolderSize(folder: java.io.File): Long {
        var size: Long = 0
        val files = folder.listFiles()
        if (files != null) {
            for (file in files) {
                size += if (file.isFile) file.length() else getFolderSize(file)
            }
        }
        return size
    }

}
