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
            displayUser(user.name, user.username, "", user.avatar, user.isAdmin)
        }

        // Get comment count from Firestore
        db.collection("comments")
            .whereEqualTo("authorEmail", email)
            .get()
            .addOnSuccessListener { snapshot ->
                val count = snapshot.size()
                findViewById<TextView>(R.id.tvCommentsCount).text = count.toString()
            }

        // Admin actions
        val currentUser = AuthManager.getCurrentUser(this)
        if (currentUser?.isAdmin == true && email != currentUser.email) {
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSuspend).visibility = View.VISIBLE
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBanDevice).visibility = View.VISIBLE

            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSuspend).setOnClickListener {
                showSuspendDialog(email)
            }
            findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBanDevice).setOnClickListener {
                AlertDialog.Builder(this)
                    .setTitle("حظر الجهاز").setMessage("حظر هذا المستخدم نهائياً؟")
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

    private fun displayUser(name: String, username: String, email: String, avatar: String, isAdmin: Boolean) {
        findViewById<TextView>(R.id.tvName).text = name
        findViewById<TextView>(R.id.tvUsername).text = username
        findViewById<TextView>(R.id.tvAdminBadge).visibility = if (isAdmin) View.VISIBLE else View.GONE
        // DON'T show email!
        
        val avatarImg = findViewById<android.widget.ImageView>(R.id.avatarImage)
        val avatarLetter = findViewById<TextView>(R.id.avatarLetter)
        
        if (avatar.isNotEmpty()) {
            avatarImg.visibility = View.VISIBLE
            avatarLetter.visibility = View.GONE
            Glide.with(this).load(avatar).circleCrop().into(avatarImg)
        } else {
            avatarImg.visibility = View.GONE
            avatarLetter.visibility = View.VISIBLE
            avatarLetter.text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        }
    }

    private fun showSuspendDialog(email: String) {
        val options = arrayOf("إيقاف يومين", "إيقاف أسبوع", "إيقاف شهر", "إيقاف دائم")
        AlertDialog.Builder(this)
            .setTitle("إيقاف الحساب")
            .setItems(options) { _, which ->
                val days = when (which) { 0 -> 2; 1 -> 7; 2 -> 30; else -> 0 }
                val input = android.widget.EditText(this)
                input.hint = "سبب الإيقاف"
                AlertDialog.Builder(this).setTitle("سبب الإيقاف").setView(input)
                    .setPositiveButton("إيقاف") { _, _ ->
                        val reason = input.text.toString().ifEmpty { "مخالفة القوانين" }
                        AuthManager.suspendUser(this, email, days, reason)
                        Toast.makeText(this, "تم الإيقاف", Toast.LENGTH_SHORT).show()
                    }.setNegativeButton("إلغاء", null).show()
            }.setNegativeButton("إلغاء", null).show()
    }
}
