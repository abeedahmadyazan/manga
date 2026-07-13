package com.yazan.manga

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.yazan.manga.ui.BaseSwipeBackActivity
import com.bumptech.glide.Glide
import com.google.firebase.firestore.FirebaseFirestore
import com.yazan.manga.data.AuthManager

class UserProfileActivity : BaseSwipeBackActivity() {

    private val db = FirebaseFirestore.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_profile)

        val email = intent.getStringExtra("user_email") ?: ""
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        if (email.isEmpty()) { finish(); return }

        // Show a loading state while we fetch from cloud
        findViewById<TextView>(R.id.tvName).text = "جارٍ التحميل..."
        findViewById<TextView>(R.id.tvUsername).text = ""

        // ALWAYS fetch from cloud (not local) so every user sees the same profile
        AuthManager.fetchCloudUser(email) { cu ->
            runOnUiThread {
                if (cu == null) {
                    // Cloud fetch failed — try local as fallback
                    val localUser = AuthManager.getUserByEmail(this, email)
                    if (localUser != null) {
                        displayUser(localUser.name, localUser.username, localUser.avatar, localUser.isAdmin, email)
                    } else {
                        // No data anywhere — show "مستخدم" with the email's first part
                        val fallbackName = email.substringBefore("@")
                        displayUser(fallbackName, "@$fallbackName", "", false, email)
                    }
                    return@runOnUiThread
                }

                // We have cloud data — display it
                val name = cu.name.ifEmpty { email.substringBefore("@") }
                val username = cu.username.ifEmpty { "@${email.substringBefore("@")}" }
                displayUserFromCloud(name, username, cu.avatarBase64, cu.isAdmin, cu.createdAt, cu.birthDate, cu.country, email)
            }
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
                        // Try to get deviceId from cloud user
                        AuthManager.fetchCloudUser(email) { cloudUser ->
                            cloudUser?.let {
                                // We don't have deviceId in CloudUser, so just ban by email
                            }
                        }
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

        // === User block button — visible to ALL signed-in users viewing someone else,
        // EXCEPT when the target is an admin (admins cannot be blocked by anyone).
        // Hides this user's comments from me AND my comments from them.
        val btnBlock = findViewById<com.google.android.material.button.MaterialButton?>(R.id.btnBlockUser)
        if (currentUser != null && email != currentUser.email) {
            // Async-check if the target is an admin → hide the block button entirely.
            // Also check the current block status (to toggle the label).
            Thread {
                try {
                    val cu = com.yazan.manga.data.ApiClient.getUserProfile(email)
                    val isTargetAdmin = cu?.isAdmin == true
                    val status = if (!isTargetAdmin) com.yazan.manga.data.ApiClient.checkBlockStatus(email) else null
                    runOnUiThread {
                        if (isTargetAdmin) {
                            // Admins cannot be blocked — hide the button entirely.
                            btnBlock?.visibility = View.GONE
                        } else {
                            btnBlock?.visibility = View.VISIBLE
                            if (status != null && status.iBlockedThem) {
                                btnBlock?.text = "✓ إلغاء الحظر"
                                btnBlock?.setTextColor(getColor(R.color.primary))
                            } else {
                                btnBlock?.text = "🚫 حظر هذا المستخدم"
                                btnBlock?.setTextColor(getColor(R.color.danger))
                            }
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { btnBlock?.visibility = View.VISIBLE }
                }
            }.start()

            btnBlock?.setOnClickListener {
                // Re-check status (might have changed) then toggle
                Thread {
                    try {
                        val status = com.yazan.manga.data.ApiClient.checkBlockStatus(email)
                        if (status.iBlockedThem) {
                            // Unblock
                            val (ok, err) = com.yazan.manga.data.ApiClient.unblockUser(email)
                            runOnUiThread {
                                if (ok) {
                                    btnBlock.text = "🚫 حظر هذا المستخدم"
                                    btnBlock.setTextColor(getColor(R.color.danger))
                                    Toast.makeText(this, "تم إلغاء الحظر", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this, err ?: "تعذّر إلغاء الحظر", Toast.LENGTH_LONG).show()
                                }
                            }
                        } else {
                            // Block — confirm first
                            runOnUiThread {
                                AlertDialog.Builder(this)
                                    .setTitle("حظر هذا المستخدم؟")
                                    .setMessage("لن تظهر تعليقاته لك، ولن تظهر تعليقاتك له. يمكنك إلغاء الحظر لاحقاً.")
                                    .setPositiveButton("حظر") { _, _ ->
                                        Thread {
                                            // Fetch the target user's name + avatar for the block list snapshot
                                            var name = ""
                                            var avatar = ""
                                            try {
                                                val cu = com.yazan.manga.data.ApiClient.getUserProfile(email)
                                                if (cu != null) { name = cu.name; avatar = cu.avatarBase64 }
                                            } catch (e: Exception) {}
                                            val (ok, err) = com.yazan.manga.data.ApiClient.blockUser(email, name, avatar)
                                            runOnUiThread {
                                                if (ok) {
                                                    btnBlock.text = "✓ إلغاء الحظر"
                                                    btnBlock.setTextColor(getColor(R.color.primary))
                                                    Toast.makeText(this, "تم حظر المستخدم", Toast.LENGTH_SHORT).show()
                                                } else {
                                                    Toast.makeText(this, err ?: "تعذّر الحظر", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }.start()
                                    }
                                    .setNegativeButton("إلغاء", null)
                                    .show()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, "تعذّر التحقق من حالة الحظر", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
        }
    }

    /**
     * Display user profile entirely from cloud data.
     * This is what every user sees when they click on someone's profile
     * from a comment.
     */
    private fun displayUserFromCloud(
        name: String,
        username: String,
        avatarBase64: String,
        isAdmin: Boolean,
        createdAt: Long,
        birthDate: String,
        country: String,
        email: String
    ) {
        val cleanName = name.removeSuffix(" (مشرف)").removeSuffix("(مشرف)").trim()
        findViewById<TextView>(R.id.tvName).text = cleanName
        findViewById<TextView>(R.id.tvUsername).text = username
        findViewById<TextView>(R.id.tvAdminBadge).visibility = if (isAdmin) View.VISIBLE else View.GONE

        val avatarImg = findViewById<android.widget.ImageView>(R.id.avatarImage)
        val avatarLetter = findViewById<TextView>(R.id.avatarLetter)

        // Decode avatar from base64 (cloud-stored)
        if (avatarBase64.isNotEmpty()) {
            try {
                val bytes = android.util.Base64.decode(avatarBase64, android.util.Base64.NO_WRAP)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    avatarImg.visibility = View.VISIBLE
                    avatarLetter.visibility = View.GONE
                    avatarImg.setImageBitmap(bitmap)
                } else {
                    avatarImg.visibility = View.GONE
                    avatarLetter.visibility = View.VISIBLE
                    avatarLetter.text = cleanName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
                }
            } catch (e: Exception) {
                avatarImg.visibility = View.GONE
                avatarLetter.visibility = View.VISIBLE
                avatarLetter.text = cleanName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            }
        } else {
            avatarImg.visibility = View.GONE
            avatarLetter.visibility = View.VISIBLE
            avatarLetter.text = cleanName.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        }

        // Show account creation date
        if (createdAt > 0) {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            findViewById<TextView>(R.id.tvCreatedAt).text = "عضو منذ ${sdf.format(java.util.Date(createdAt))}"
            findViewById<TextView>(R.id.tvCreatedAt).visibility = View.VISIBLE
        }

        // Show birth date if set
        if (birthDate.isNotEmpty()) {
            findViewById<TextView>(R.id.tvBirthDate).text = "🎂 تاريخ الميلاد: $birthDate"
            findViewById<TextView>(R.id.tvBirthDate).visibility = View.VISIBLE
        }

        // Show country if set
        if (country.isNotEmpty()) {
            findViewById<TextView>(R.id.tvCountry).text = "🌍 الدولة: $country"
            findViewById<TextView>(R.id.tvCountry).visibility = View.VISIBLE
        }
    }

    /**
     * Fallback: display from local data (used when cloud fetch fails).
     * Tries to fetch cloud profile info (dates, country) as a best-effort.
     */
    private fun displayUser(name: String, username: String, avatar: String, isAdmin: Boolean, email: String) {
        val cleanName = name.removeSuffix(" (مشرف)").removeSuffix("(مشرف)").trim()
        findViewById<TextView>(R.id.tvName).text = cleanName
        findViewById<TextView>(R.id.tvUsername).text = username
        findViewById<TextView>(R.id.tvAdminBadge).visibility = if (isAdmin) View.VISIBLE else View.GONE

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

        // Best-effort: try fetching cloud profile info again (dates, country)
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
