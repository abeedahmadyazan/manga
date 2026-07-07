package com.yazan.manga

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.CommentsManager

/**
 * UserProfileActivity — Shows another user's profile.
 *
 * - Avatar, name, username
 * - Stats (comments count)
 * - Admin actions: suspend (dialog with duration), ban device
 */
class UserProfileActivity : AppCompatActivity() {

    private lateinit var avatarLetter: TextView
    private lateinit var avatarImage: android.widget.ImageView
    private lateinit var tvName: TextView
    private lateinit var tvUsername: TextView
    private lateinit var tvAdminBadge: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvCommentsCount: TextView
    private lateinit var tvAdminTitle: TextView
    private lateinit var btnSuspend: MaterialButton
    private lateinit var btnUnsuspend: MaterialButton
    private lateinit var btnBanDevice: MaterialButton

    private var targetUser: AuthManager.User? = null
    private var targetEmail: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_profile)

        targetEmail = intent.getStringExtra("user_email") ?: ""

        initViews()
        loadUser()
    }

    private fun initViews() {
        avatarLetter = findViewById(R.id.avatarLetter)
        avatarImage = findViewById(R.id.avatarImage)
        tvName = findViewById(R.id.tvName)
        tvUsername = findViewById(R.id.tvUsername)
        tvAdminBadge = findViewById(R.id.tvAdminBadge)
        tvStatus = findViewById(R.id.tvStatus)
        tvCommentsCount = findViewById(R.id.tvCommentsCount)
        tvAdminTitle = findViewById(R.id.tvAdminTitle)
        btnSuspend = findViewById(R.id.btnSuspend)
        btnUnsuspend = findViewById(R.id.btnUnsuspend)
        btnBanDevice = findViewById(R.id.btnBanDevice)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        btnSuspend.setOnClickListener { showSuspendDialog() }
        btnUnsuspend.setOnClickListener {
            val err = AuthManager.unsuspendUser(this, targetEmail)
            if (err != null) android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_SHORT).show()
            else android.widget.Toast.makeText(this, "تم إلغاء الإيقاف", android.widget.Toast.LENGTH_SHORT).show()
            loadUser()
        }
        btnBanDevice.setOnClickListener { showBanDialog() }
    }

    private fun loadUser() {
        if (targetEmail.isEmpty()) {
            finish()
            return
        }
        val user = AuthManager.getUserByEmail(this, targetEmail)
        targetUser = user
        if (user == null) {
            tvName.text = "مستخدم غير موجود"
            tvUsername.text = ""
            hideAdminActions()
            return
        }

        val first = user.name.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
        avatarLetter.text = first
        avatarLetter.visibility = View.VISIBLE
        if (user.avatar.isNotEmpty()) {
            avatarImage.visibility = View.VISIBLE
            Glide.with(this).load(user.avatar).circleCrop().into(avatarImage)
        } else {
            avatarImage.visibility = View.GONE
        }

        tvName.text = user.name
        tvUsername.text = "@${user.username.removePrefix("@")}"
        tvAdminBadge.visibility = if (user.isAdmin) View.VISIBLE else View.GONE

        // Comments count (across all contexts)
        val count = CommentsManager.getAllComments(this)
            .count { it.authorEmail == user.email }
        tvCommentsCount.text = count.toString()

        // Suspend status
        val (suspended, reason) = AuthManager.isUserSuspended(this, user.email)
        if (suspended) {
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "موقوف"
            tvStatus.background = getDrawable(R.drawable.bg_danger_button)
            btnSuspend.visibility = View.GONE
            btnUnsuspend.visibility = View.VISIBLE
        } else {
            tvStatus.visibility = View.GONE
            btnSuspend.visibility = View.VISIBLE
            btnUnsuspend.visibility = View.GONE
        }

        // Admin actions visibility (only for admin viewing other users, not self, not other admins)
        val me = AuthManager.getCurrentUser(this)
        val canAdmin = me?.isAdmin == true && me.email != user.email && !user.isAdmin
        if (canAdmin) {
            tvAdminTitle.visibility = View.VISIBLE
            btnSuspend.visibility = if (!suspended) View.VISIBLE else View.GONE
            btnUnsuspend.visibility = if (suspended) View.VISIBLE else View.GONE
            btnBanDevice.visibility = View.VISIBLE
        } else {
            hideAdminActions()
        }
    }

    private fun hideAdminActions() {
        tvAdminTitle.visibility = View.GONE
        btnSuspend.visibility = View.GONE
        btnUnsuspend.visibility = View.GONE
        btnBanDevice.visibility = View.GONE
    }

    private fun showSuspendDialog() {
        val durations = arrayOf(
            "يوم واحد" to 1,
            "3 أيام" to 3,
            "أسبوع" to 7,
            "شهر" to 30,
            "دائم" to 0
        )
        val labels = durations.map { it.first }.toTypedArray()
        val reasonInput = android.widget.EditText(this).apply {
            hint = "السبب (اختياري)"
            setPadding(40, 24, 40, 24)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(20, 16, 20, 0)
            addView(reasonInput)
        }
        val checked = intArrayOf(1)  // default = 3 days

        AlertDialog.Builder(this)
            .setTitle("إيقاف ${targetUser?.name ?: "المستخدم"}")
            .setSingleChoiceItems(labels, checked[0]) { _, which -> checked[0] = which }
            .setView(container)
            .setPositiveButton("إيقاف") { _, _ ->
                val days = durations[checked[0]].second
                val reason = reasonInput.text.toString().trim().ifEmpty { "مخالفة القوانين" }
                val err = AuthManager.suspendUser(this, targetEmail, days, reason)
                if (err != null) android.widget.Toast.makeText(this, err, android.widget.Toast.LENGTH_SHORT).show()
                else {
                    android.widget.Toast.makeText(this, "تم إيقاف الحساب", android.widget.Toast.LENGTH_SHORT).show()
                    loadUser()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showBanDialog() {
        AlertDialog.Builder(this)
            .setTitle("حظر الجهاز")
            .setMessage("سيتم حظر جهاز ${targetUser?.name ?: "المستخدم"} نهائياً. متابعة؟")
            .setPositiveButton("حظر") { _, _ ->
                targetUser?.let { u ->
                    AuthManager.banDevice(this, u.deviceId)
                    android.widget.Toast.makeText(this, "تم حظر الجهاز", android.widget.Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
