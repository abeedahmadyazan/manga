package com.yazan.manga

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.AccountPicker
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.CommentsManager

class ProfileActivity : AppCompatActivity() {

    companion object {
        private const val RC_SIGN_IN = 9001
        private const val RC_IMAGE_PICK = 9003
        private const val RC_ACCOUNT_PICKER = 9002
    }

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var firebaseAuth: FirebaseAuth

    private lateinit var avatarImage: android.widget.ImageView
    private lateinit var avatarLetter: TextView
    private lateinit var btnChangeAvatar: ImageButton
    private lateinit var tvName: TextView
    private lateinit var tvUsername: TextView
    private lateinit var tvEmail: TextView
    private lateinit var tvAdminBadge: TextView
    private lateinit var tvStatComments: TextView
    private lateinit var tvStatFavorites: TextView
    private lateinit var tvStatHistory: TextView
    private lateinit var btnLogin: MaterialButton
    private lateinit var btnLogout: MaterialButton
    private lateinit var btnChangeName: MaterialButton
    private lateinit var btnChangeUsername: MaterialButton
    private lateinit var btnAdminPanel: MaterialButton
    private lateinit var statsContainer: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        val gso = AuthManager.getGoogleSignInOptions(this)
        googleSignInClient = GoogleSignIn.getClient(this, gso)
        firebaseAuth = FirebaseAuth.getInstance()

        initViews()
        updateUI()
    }

    private fun initViews() {
        avatarImage = findViewById(R.id.avatarImage)
        avatarLetter = findViewById(R.id.avatarLetter)
        btnChangeAvatar = findViewById(R.id.btnChangeAvatar)
        tvName = findViewById(R.id.tvName)
        tvUsername = findViewById(R.id.tvUsername)
        tvEmail = findViewById(R.id.tvEmail)
        tvAdminBadge = findViewById(R.id.tvAdminBadge)
        tvStatComments = findViewById(R.id.tvStatComments)
        tvStatFavorites = findViewById(R.id.tvStatFavorites)
        tvStatHistory = findViewById(R.id.tvStatHistory)
        btnLogin = findViewById(R.id.btnLogin)
        btnLogout = findViewById(R.id.btnLogout)
        btnChangeName = findViewById(R.id.btnChangeName)
        btnChangeUsername = findViewById(R.id.btnChangeUsername)
        btnAdminPanel = findViewById(R.id.btnAdminPanel)
        statsContainer = findViewById(R.id.statsContainer)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        btnLogin.setOnClickListener { tryGoogleSignIn() }
        btnLogout.setOnClickListener {
            AuthManager.logout(this)
            updateUI()
            Toast.makeText(this, "تم تسجيل الخروج", Toast.LENGTH_SHORT).show()
        }
        btnChangeName.setOnClickListener {
            showChangeNameDialog()
        }
        btnChangeUsername.setOnClickListener {
            showChangeUsernameDialog()
        }

        // Profile picture — both the avatar image and the dedicated camera button open the picker
        val openPicker = View.OnClickListener {
            if (AuthManager.getCurrentUser(this) == null) {
                Toast.makeText(this, "سجّل الدخول أولاً", Toast.LENGTH_SHORT).show()
                return@OnClickListener
            }
            val intent = Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, RC_IMAGE_PICK)
        }
        avatarImage.setOnClickListener(openPicker)
        btnChangeAvatar.setOnClickListener(openPicker)

        btnAdminPanel.setOnClickListener { startActivity(Intent(this, AdminPanelActivity::class.java)) }
    }

    private fun tryGoogleSignIn() {
        try {
            val signInIntent = googleSignInClient.signInIntent
            startActivityForResult(signInIntent, RC_SIGN_IN)
        } catch (e: Exception) {
            showAccountPicker()
        }
    }

    private fun showAccountPicker() {
        try {
            val intent = AccountPicker.newChooseAccountIntent(
                null, null, arrayOf("com.google"),
                false, null, null, null, null
            )
            startActivityForResult(intent, RC_ACCOUNT_PICKER)
        } catch (e: Exception) {
            Toast.makeText(this, "فشل تسجيل الدخول: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveProfileImage(uri: android.net.Uri) {
        // Copy the image into internal storage so it survives app restarts
        // and permission revocation (the old URI-based approach broke after restart).
        val savedPath = AuthManager.setAvatar(this, uri)
        if (savedPath != null) {
            Toast.makeText(this, "تم تحديث الصورة", Toast.LENGTH_SHORT).show()
            updateUI()
        } else {
            Toast.makeText(this, "فشل حفظ الصورة", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            RC_IMAGE_PICK -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    saveProfileImage(data.data!!)
                }
            }
            RC_SIGN_IN -> {
                if (resultCode != Activity.RESULT_OK) {
                    showAccountPicker()
                    return
                }
                try {
                    val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                        .getResult(ApiException::class.java)
                    if (account != null && account.idToken != null) {
                        firebaseAuthWithGoogle(account.idToken!!)
                    } else if (account != null && account.email != null) {
                        loginWithEmail(account.email!!, account.displayName ?: account.email!!.split("@")[0])
                    } else {
                        showAccountPicker()
                    }
                } catch (e: ApiException) {
                    showAccountPicker()
                }
            }
            RC_ACCOUNT_PICKER -> {
                if (resultCode != Activity.RESULT_OK) {
                    Toast.makeText(this, "تم إلغاء تسجيل الدخول", Toast.LENGTH_SHORT).show()
                    return
                }
                val accountName = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                if (!accountName.isNullOrEmpty()) {
                    val displayName = accountName.split("@")[0]
                    loginWithEmail(accountName, displayName)
                }
            }
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        firebaseAuth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val account = GoogleSignIn.getLastSignedInAccount(this)
                    val error = AuthManager.processFirebaseAuth(this, account)
                    handleAuthResult(error)
                } else {
                    val account = GoogleSignIn.getLastSignedInAccount(this)
                    if (account?.email != null) {
                        loginWithEmail(account.email!!, account.displayName ?: account.email!!.split("@")[0])
                    } else {
                        Toast.makeText(this, "فشل المصادقة", Toast.LENGTH_SHORT).show()
                    }
                }
            }
    }

    private fun loginWithEmail(email: String, displayName: String) {
        val error = AuthManager.processEmailAuth(this, email, displayName)
        handleAuthResult(error)
    }

    private fun handleAuthResult(error: String?) {
        if (error == null) {
            val user = AuthManager.getCurrentUser(this)
            if (user?.isAdmin == true) {
                Toast.makeText(this, "👑 مرحباً يا مشرف!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "تم تسجيل الدخول بنجاح", Toast.LENGTH_SHORT).show()
            }
            updateUI()
        } else {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            AuthManager.logout(this)
            updateUI()
        }
    }

    private fun showChangeNameDialog() {
        val user = AuthManager.getCurrentUser(this) ?: return
        // Strip the admin suffix when showing the current name
        val currentName = user.name.removeSuffix(" (مشرف)")
        val input = EditText(this).apply {
            setText(currentName)
            setSelection(currentName.length)
            hint = "اكتب اسمك الجديد"
            setPadding(40, 24, 40, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("تغيير الاسم")
            .setMessage("الاسم الذي يظهر للآخرين على تعليقاتك وملفك الشخصي.")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "الاسم لا يمكن أن يكون فارغاً", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val err = AuthManager.changeName(this, newName)
                if (err == null) {
                    Toast.makeText(this, "تم تحديث الاسم", Toast.LENGTH_SHORT).show()
                    updateUI()
                } else {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showChangeUsernameDialog() {
        val user = AuthManager.getCurrentUser(this) ?: return
        val input = EditText(this).apply {
            setText(user.username)
            setSelection(user.username.length)
            hint = "@username"
            setPadding(40, 24, 40, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("تغيير اسم المستخدم")
            .setMessage("يمكنك تغيير اسم المستخدم مرة كل 30 يوم.")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val newUsername = input.text.toString().trim()
                val err = AuthManager.changeUsername(this, newUsername)
                if (err == null) {
                    Toast.makeText(this, "تم تغيير اسم المستخدم", Toast.LENGTH_SHORT).show()
                    updateUI()
                } else {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun updateUI() {
        val user = AuthManager.getCurrentUser(this)

        if (user != null) {
            // Avatar — show image if available, otherwise the letter.
            val first = user.name.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
            avatarLetter.text = first
            if (user.avatar.isNotEmpty()) {
                avatarImage.visibility = View.VISIBLE
                avatarLetter.visibility = View.GONE
                Glide.with(this).load(user.avatar).circleCrop()
                    .placeholder(R.drawable.bg_avatar_gradient)
                    .into(avatarImage)
            } else {
                avatarImage.visibility = View.GONE
                avatarLetter.visibility = View.VISIBLE
            }

            tvName.text = user.name
            tvUsername.text = "@${user.username.removePrefix("@")}"
            // Hide the email for privacy — never shown on the profile screen
            tvEmail.visibility = View.GONE
            tvAdminBadge.visibility = if (user.isAdmin) View.VISIBLE else View.GONE

            // Stats — comments count is best-effort from CloudCommentsManager if available,
            // otherwise fall back to local CommentsManager.
            try {
                val commentsCount = CommentsManager.getAllComments(this)
                    .count { it.authorEmail == user.email }
                tvStatComments.text = commentsCount.toString()
            } catch (e: Exception) {
                tvStatComments.text = "0"
            }
            tvStatFavorites.text = "0"
            tvStatHistory.text = "0"
            statsContainer.visibility = View.VISIBLE

            btnLogin.visibility = View.GONE
            btnLogout.visibility = View.VISIBLE
            btnChangeName.visibility = View.VISIBLE
            btnChangeUsername.visibility = View.VISIBLE
            btnChangeAvatar.visibility = View.VISIBLE
            btnAdminPanel.visibility = if (user.isAdmin) View.VISIBLE else View.GONE
        } else {
            avatarLetter.text = "ز"
            avatarLetter.visibility = View.VISIBLE
            avatarImage.visibility = View.GONE
            btnChangeAvatar.visibility = View.GONE
            tvName.text = "زائر"
            tvUsername.text = ""
            tvEmail.visibility = View.GONE
            tvAdminBadge.visibility = View.GONE
            statsContainer.visibility = View.GONE
            btnLogin.visibility = View.VISIBLE
            btnLogout.visibility = View.GONE
            btnChangeName.visibility = View.GONE
            btnChangeUsername.visibility = View.GONE
            btnAdminPanel.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }
}
