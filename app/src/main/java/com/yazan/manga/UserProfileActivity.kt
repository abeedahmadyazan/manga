package com.yazan.manga

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.firebase.firestore.FirebaseFirestore
import com.yazan.manga.data.AuthManager

class UserProfileActivity : AppCompatActivity() {

    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_profile)

        val email = intent.getStringExtra("user_email") ?: ""
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        if (email.isEmpty()) { finish(); return }

        // Get user from local DB first
        val user = AuthManager.getUserByEmail(this, email)

        if (user != null) {
            displayUser(user.name, user.username, user.avatar, user.isAdmin)
        }

        // Admin actions — only visible if I'm an admin and viewing someone else
        val currentUser = AuthManager.getCurrentUser(this)
        val canAdmin = currentUser?.isAdmin == true && email != currentUser.email

        if (canAdmin) {
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSuspend).visibility = View.VISIBLE
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBanDevice).visibility = View.VISIBLE

            // Suspend button → show duration options (1 hour / 1 day / 1 week / permanent)
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSuspend).setOnClickListener {
                showSuspendDialog(email)
            }

            // Ban device → permanent device ban + delete all comments
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBanDevice).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("حظر الجهاز").setMessage("حظر هذا المستخدم نهائياً؟ سيتم حذف جميع تعليقاته.")
                    .setPositiveButton("حظر") { _, _ ->
                        user?.let { AuthManager.banDevice(this, it.deviceId) }
                        // Also delete all their comments from Firestore
                        db.collection("comments").whereEqualTo("authorEmail", email).get()
                            .addOnSuccessListener { snapshot ->
                                val batch = db.batch()
                                snapshot.documents.forEach { doc -> batch.delete(doc.reference) }
                                batch.commit()
                                Toast.makeText(this, "تم الحظر", Toast.LENGTH_SHORT).show()
                                finish()
                            }
                    }.setNegativeButton("إلغاء", null).show()
            }
        }
    }

    private fun displayUser(name: String, username: String, avatar: String, isAdmin: Boolean) {
        // Strip any legacy '(مشرف)' suffix — the badge is shown separately
        val cleanName = name
            .removeSuffix(" (مشرف)")
            .removeSuffix("(مشرف)")
            .trim()
        findViewById<TextView>(R.id.tvName).text = cleanName
        findViewById<TextView>(R.id.tvUsername).text = username
        findViewById<TextView>(R.id.tvAdminBadge).visibility = if (isAdmin) View.VISIBLE else View.GONE
        // DON'T show email!

        val avatarImg = findViewById<android.widget.ImageView>(R.id.avatarImage)
        val avatarLetter = findViewById<TextView>(R.id.avatarLetter)

        if (avatar.isNotEmpty()) {
            val avatarFile = java.io.File(avatar)
            if (avatarFile.exists()) {
                avatarImg.visibility = View.VISIBLE
                avatarLetter.visibility = View.GONE
                Glide.with(this).load(avatar).circleCrop().into(avatarImg)
            } else {
                avatarImg.visibility = View.GONE
                avatarLetter.visibility = View.VISIBLE
                avatarLetter.text = cleanName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            }
        } else {
            avatarImg.visibility = View.GONE
            avatarLetter.visibility = View.VISIBLE
            avatarLetter.text = cleanName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        }

        // Fetch the cloud profile to show createdAt, birthDate, and country.
        // These are visible to everyone (not just the owner).
        val email = intent.getStringExtra("user_email") ?: return
        AuthManager.fetchCloudUser(email) { cu ->
            runOnUiThread {
                if (cu == null) return@runOnUiThread
                if (cu.createdAt > 0) {
                    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    findViewById<TextView>(R.id.tvCreatedAt).text = "عضو منذ ${sdf.format(java.util.Date(cu.createdAt))}"
                    findViewById<TextView>(R.id.tvCreatedAt).visibility = View.VISIBLE
                }
                if (cu.birthDate.isNotEmpty()) {
                    findViewById<TextView>(R.id.tvBirthDate).text = "🎂 تاريخ الميلاد: ${cu.birthDate}"
                    findViewById<TextView>(R.id.tvBirthDate).visibility = View.VISIBLE
                }
                if (cu.country.isNotEmpty()) {
                    findViewById<TextView>(R.id.tvCountry).text = "🌍 الدولة: ${cu.country}"
                    findViewById<TextView>(R.id.tvCountry).visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * Suspend a user for a chosen duration. Stores the suspension in Firestore
     * (banned_users collection) so it persists across app updates and affects
     * the user on all devices.
     */
    private fun showSuspendDialog(email: String) {
        val options = arrayOf(
            "⏰ إيقاف ساعة واحدة",
            "📅 إيقاف يوم واحد",
            "🗓️ إيقاف أسبوع",
            "♾️ إيقاف دائم"
        )
        AlertDialog.Builder(this)
            .setTitle("إيقاف الحساب")
            .setItems(options) { _, which ->
                // Duration in milliseconds. 0 = permanent.
                val durationMs: Long = when (which) {
                    0 -> 60 * 60 * 1000L          // 1 hour
                    1 -> 24 * 60 * 60 * 1000L      // 1 day
                    2 -> 7 * 24 * 60 * 60 * 1000L  // 1 week
                    else -> 0L                      // permanent
                }
                val reasonInput = android.widget.EditText(this)
                reasonInput.hint = "سبب الإيقاف"
                AlertDialog.Builder(this).setTitle("سبب الإيقاف").setView(reasonInput)
                    .setPositiveButton("إيقاف") { _, _ ->
                        val reason = reasonInput.text.toString().ifEmpty { "مخالفة القوانين" }
                        suspendUserInCloud(email, durationMs, reason)
                    }.setNegativeButton("إلغاء", null).show()
            }.setNegativeButton("إلغاء", null).show()
    }

    /**
     * Write the suspension to Firestore so it's enforced on every device.
     * Document: banned_users/{email} → { email, until, reason, bannedAt }
     * until = 0 means permanent.
     */
    private fun suspendUserInCloud(email: String, durationMs: Long, reason: String) {
        val now = System.currentTimeMillis()
        val until = if (durationMs == 0L) 0L else now + durationMs
        val data = mapOf(
            "email" to email,
            "until" to until,
            "reason" to reason,
            "bannedAt" to now
        )
        db.collection("banned_users").document(email).set(data)
            .addOnSuccessListener {
                Toast.makeText(this, "تم الإيقاف", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "حدث خطأ", Toast.LENGTH_SHORT).show()
            }
    }
}
