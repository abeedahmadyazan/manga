package com.yazan.manga

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.yazan.manga.data.AuthManager

class UserProfileActivity : AppCompatActivity() {

    private var userEmail: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_profile)

        userEmail = intent.getStringExtra("user_email") ?: ""
        if (userEmail.isEmpty()) {
            finish()
            return
        }

        initViews()
    }

    private fun initViews() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        val currentUser = AuthManager.getCurrentUser(this)
        val targetUser = AuthManager.getUserByEmail(this, userEmail)

        if (targetUser == null) {
            findViewById<TextView>(R.id.tvProfileName).text = "المستخدم غير موجود"
            return
        }

        findViewById<TextView>(R.id.tvProfileName).text = targetUser.name
        findViewById<TextView>(R.id.tvProfileUsername).text = targetUser.username
        findViewById<TextView>(R.id.tvProfileEmail).text = "📧 ${targetUser.email}"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        findViewById<TextView>(R.id.tvProfileJoined).text = "انضم: ${sdf.format(java.util.Date(targetUser.createdAt))}"
        findViewById<TextView>(R.id.tvProfileAdminBadge).visibility = if (targetUser.isAdmin) View.VISIBLE else View.GONE

        val commentCount = countUserComments(targetUser.email)
        findViewById<TextView>(R.id.tvProfileCommentsCount).text = commentCount.toString()

        val btnSuspend = findViewById<MaterialButton>(R.id.btnSuspendUser)
        val btnBanDevice = findViewById<MaterialButton>(R.id.btnBanDevice)

        if (currentUser?.isAdmin == true && !targetUser.isAdmin) {
            btnSuspend.visibility = View.VISIBLE
            btnBanDevice.visibility = View.VISIBLE

            btnSuspend.setOnClickListener {
                showSuspendDialog(targetUser.email)
            }

            btnBanDevice.setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("حظر الجهاز")
                    .setMessage("سيتم حظر جهاز هذا المستخدم نهائياً. متابعة؟")
                    .setPositiveButton("حظر") { _, _ ->
                        AuthManager.banDevice(this, targetUser.deviceId)
                        android.widget.Toast.makeText(this, "تم حظر الجهاز", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        } else {
            btnSuspend.visibility = View.GONE
            btnBanDevice.visibility = View.GONE
        }
    }

    private fun countUserComments(email: String): Int {
        val prefs = getSharedPreferences("manga_comments", MODE_PRIVATE)
        val json = prefs.getString("all_comments", "[]") ?: "[]"
        val arr = org.json.JSONArray(json)
        var count = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("authorEmail") == email) count++
        }
        return count
    }

    private fun showSuspendDialog(email: String) {
        val options = arrayOf("إيقاف يومين", "إيقاف أسبوع", "إيقاف شهر", "إيقاف دائم")
        AlertDialog.Builder(this)
            .setTitle("إيقاف الحساب")
            .setItems(options) { _, which ->
                val days = when (which) {
                    0 -> 2
                    1 -> 7
                    2 -> 30
                    else -> 0
                }
                val input = android.widget.EditText(this)
                input.hint = "سبب الإيقاف"
                AlertDialog.Builder(this)
                    .setTitle("سبب الإيقاف")
                    .setView(input)
                    .setPositiveButton("إيقاف") { _, _ ->
                        val reason = input.text.toString().ifEmpty { "مخالفة القوانين" }
                        val err = AuthManager.suspendUser(this, email, days, reason)
                        if (err == null) {
                            android.widget.Toast.makeText(this, "تم إيقاف الحساب", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
