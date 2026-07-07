package com.yazan.manga

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.android.material.button.MaterialButton
import com.yazan.manga.data.AuthManager

class ProfileActivity : AppCompatActivity() {

    companion object {
        private const val RC_SIGN_IN = 9001
    }

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var firebaseAuth: FirebaseAuth

    private lateinit var avatar: android.widget.ImageView
    private lateinit var tvUsername: TextView
    private lateinit var tvEmail: TextView
    private lateinit var tvAdminBadge: TextView
    private lateinit var btnLogin: MaterialButton
    private lateinit var btnLogout: MaterialButton
    private lateinit var btnChangeUsername: MaterialButton
    private lateinit var btnAdminPanel: MaterialButton
    private lateinit var statsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        // Configure Google Sign-In
        val gso = AuthManager.getGoogleSignInOptions(this)
        googleSignInClient = GoogleSignIn.getClient(this, gso)
        firebaseAuth = FirebaseAuth.getInstance()

        initViews()
        updateUI()
    }

    private fun initViews() {
        avatar = findViewById(R.id.avatar)
        tvUsername = findViewById(R.id.tvUsername)
        tvEmail = findViewById(R.id.tvEmail)
        tvAdminBadge = findViewById(R.id.tvAdminBadge)
        btnLogin = findViewById(R.id.btnLogin)
        btnLogout = findViewById(R.id.btnLogout)
        btnChangeUsername = findViewById(R.id.btnChangeUsername)
        btnAdminPanel = findViewById(R.id.btnAdminPanel)
        statsContainer = findViewById(R.id.statsContainer)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        btnLogin.setOnClickListener {
            signInWithGoogle()
        }

        btnLogout.setOnClickListener {
            AuthManager.logout(this)
            updateUI()
        }

        btnChangeUsername.setOnClickListener {
            showChangeUsernameDialog()
        }

        btnAdminPanel.setOnClickListener {
            startActivity(Intent(this, AdminPanelActivity::class.java))
        }
    }

    private fun signInWithGoogle() {
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                firebaseAuthWithGoogle(account?.idToken)
            } catch (e: ApiException) {
                Toast.makeText(this, "فشل تسجيل الدخول: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String?) {
        if (idToken == null) {
            Toast.makeText(this, "فشل الحصول على التوكن", Toast.LENGTH_SHORT).show()
            return
        }

        val credential = GoogleAuthProvider.getCredential(idToken, null)
        firebaseAuth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                if (task.isSuccessful) {
                    val account = GoogleSignIn.getLastSignedInAccount(this)
                    val error = AuthManager.processFirebaseAuth(this, account)
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
                } else {
                    Toast.makeText(this, "فشل المصادقة: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun showChangeUsernameDialog() {
        val user = AuthManager.getCurrentUser(this) ?: return
        val input = EditText(this).apply {
            setText(user.username)
            hint = "@username"
        }

        AlertDialog.Builder(this)
            .setTitle("تغيير اسم المستخدم")
            .setMessage("يمكنك تغيير اسم المستخدم مرة كل 30 يوم.")
            .setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val newUsername = input.text.toString().trim()
                val err = AuthManager.changeUsername(this, newUsername)
                if (err == null) {
                    Toast.makeText(this, "تم تغيير اسم المستخدم بنجاح", Toast.LENGTH_SHORT).show()
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
            tvUsername.text = "${user.name}\n@${user.username.removePrefix("@")}"
            tvUsername.visibility = View.VISIBLE
            tvEmail.text = "📧 ${user.email}"
            tvEmail.visibility = View.VISIBLE
            tvAdminBadge.visibility = if (user.isAdmin) View.VISIBLE else View.GONE
            btnLogin.visibility = View.GONE
            btnLogout.visibility = View.VISIBLE
            btnChangeUsername.visibility = View.VISIBLE
            btnAdminPanel.visibility = if (user.isAdmin) View.VISIBLE else View.GONE
            statsContainer.visibility = View.VISIBLE
        } else {
            tvUsername.visibility = View.GONE
            tvEmail.visibility = View.GONE
            tvAdminBadge.visibility = View.GONE
            btnLogin.visibility = View.VISIBLE
            btnLogout.visibility = View.GONE
            btnChangeUsername.visibility = View.GONE
            btnAdminPanel.visibility = View.GONE
            statsContainer.visibility = View.GONE
        }
    }
}
