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
    private lateinit var tvName: TextView
    private lateinit var tvUsername: TextView
    private lateinit var tvEmail: TextView
    private lateinit var tvAdminBadge: TextView
    private lateinit var tvStatComments: TextView
    private lateinit var tvStatFavorites: TextView
    private lateinit var tvStatHistory: TextView
    private lateinit var btnLogin: MaterialButton
    private lateinit var btnLogout: MaterialButton
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
        tvName = findViewById(R.id.tvName)
        tvUsername = findViewById(R.id.tvUsername)
        tvEmail = findViewById(R.id.tvEmail)
        tvAdminBadge = findViewById(R.id.tvAdminBadge)
        tvStatComments = findViewById(R.id.tvStatComments)
        tvStatFavorites = findViewById(R.id.tvStatFavorites)
        tvStatHistory = findViewById(R.id.tvStatHistory)
        btnLogin = findViewById(R.id.btnLogin)
        btnLogout = findViewById(R.id.btnLogout)
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
        btnChangeUsername.setOnClickListener {
            showChangeUsernameDialog()
        }

        // Profile picture
        avatarImage.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, RC_IMAGE_PICK)
        }

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
        try {
            val prefs = getSharedPreferences("manga_prefs", MODE_PRIVATE)
            prefs.edit().putString("profile_image", uri.toString()).apply()
            com.bumptech.glide.Glide.with(this).load(uri).circleCrop().into(avatarImage)
            Toast.makeText(this, "تم تحديث الصورة", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "فشل تحميل الصورة", Toast.LENGTH_SHORT).show()
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
            // Load profile image
            val prefs = getSharedPreferences("manga_prefs", MODE_PRIVATE)
            val imageUri = prefs.getString("profile_image", null)
            if (imageUri != null) {
                com.bumptech.glide.Glide.with(this).load(android.net.Uri.parse(imageUri)).circleCrop().into(avatarImage)
            }
            // Avatar
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
            tvEmail.text = user.email
            tvAdminBadge.visibility = if (user.isAdmin) View.VISIBLE else View.GONE

            // Stats
            val commentsCount = CommentsManager.getAllComments(this)
                .count { it.authorEmail == user.email }
            tvStatComments.text = commentsCount.toString()
            tvStatFavorites.text = "0"
            tvStatHistory.text = "0"
            statsContainer.visibility = View.VISIBLE

            btnLogin.visibility = View.GONE
            btnLogout.visibility = View.VISIBLE
            btnChangeUsername.visibility = View.VISIBLE
            btnAdminPanel.visibility = if (user.isAdmin) View.VISIBLE else View.GONE
        } else {
            avatarLetter.text = "ز"
            avatarLetter.visibility = View.VISIBLE
            avatarImage.visibility = View.GONE
            tvName.text = "زائر"
            tvUsername.text = ""
            tvEmail.text = ""
            tvAdminBadge.visibility = View.GONE
            statsContainer.visibility = View.GONE
            btnLogin.visibility = View.VISIBLE
            btnLogout.visibility = View.GONE
            btnChangeUsername.visibility = View.GONE
            btnAdminPanel.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }
}
