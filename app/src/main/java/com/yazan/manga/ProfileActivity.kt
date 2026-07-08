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
import com.google.firebase.firestore.ListenerRegistration
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.MangaListsManager
import com.yazan.manga.ui.PieChartView

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
    private lateinit var pieChart: PieChartView
    private lateinit var tvStatFavorites: TextView
    private lateinit var tvStatWatchLater: TextView
    private lateinit var tvStatWantToWatch: TextView
    private lateinit var tvStatCompleted: TextView
    private lateinit var btnLogin: MaterialButton
    private lateinit var btnLogout: MaterialButton
    private lateinit var btnChangeName: MaterialButton
    private lateinit var btnChangeUsername: MaterialButton
    private lateinit var btnAdminPanel: MaterialButton
    private lateinit var statsContainer: View
    private var listsListener: ListenerRegistration? = null

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
        pieChart = findViewById(R.id.pieChart)
        tvStatFavorites = findViewById(R.id.tvStatFavorites)
        tvStatWatchLater = findViewById(R.id.tvStatWatchLater)
        tvStatWantToWatch = findViewById(R.id.tvStatWantToWatch)
        tvStatCompleted = findViewById(R.id.tvStatCompleted)
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

        // Make the 4 list cards open MangaListActivity for the corresponding list
        findViewById<View>(R.id.cardFavorites).setOnClickListener { openList(0) }
        findViewById<View>(R.id.cardWatchLater).setOnClickListener { openList(1) }
        findViewById<View>(R.id.cardWantToWatch).setOnClickListener { openList(2) }
        findViewById<View>(R.id.cardCompleted).setOnClickListener { openList(3) }
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
            Toast.makeText(this, "فشل تسجيل الدخول", Toast.LENGTH_LONG).show()
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
            // After login, the cloud sync (restoreUserFromCloud) runs async.
            // Re-run updateUI after a short delay so the restored name/username/avatar
            // are reflected in the UI.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                updateUI()
            }, 1500)
        } else {
            // Show a generic error — never expose the real cause to the user
            Toast.makeText(this, "حدث خطأ أثناء تسجيل الدخول", Toast.LENGTH_LONG).show()
            AuthManager.logout(this)
            updateUI()
        }
    }

    private fun openList(tabIndex: Int) {
        val user = AuthManager.getCurrentUser(this)
        if (user == null) {
            Toast.makeText(this, "سجّل الدخول أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, MangaListActivity::class.java)
        intent.putExtra("user_email", user.email)
        intent.putExtra("initial_tab", tabIndex)
        startActivity(intent)
    }

    private fun showChangeNameDialog() {
        val user = AuthManager.getCurrentUser(this) ?: return
        // Strip the admin suffix when showing the current name
        val currentName = user.name
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
        val dialog = AlertDialog.Builder(this)
            .setTitle("تغيير اسم المستخدم")
            .setMessage("يمكنك تغيير اسم المستخدم مرة كل 30 يوم.")
            .setView(input)
            .setPositiveButton("حفظ", null) // set to null so it doesn't auto-dismiss
            .setNegativeButton("إلغاء", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newUsername = input.text.toString().trim()
                // Show a loading toast while checking the cloud
                Toast.makeText(this, "جارٍ التحقق...", Toast.LENGTH_SHORT).show()
                AuthManager.changeUsername(this, newUsername) { err ->
                    runOnUiThread {
                        if (err == null) {
                            Toast.makeText(this, "تم تغيير اسم المستخدم", Toast.LENGTH_SHORT).show()
                            updateUI()
                            dialog.dismiss()
                        } else {
                            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        dialog.show()
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

            // Stats — manga lists from the cloud (real-time)
            listsListener?.remove()
            listsListener = MangaListsManager.listenToMyLists(
                email = user.email,
                onUpdate = { lists ->
                    runOnUiThread {
                        tvStatFavorites.text = lists.favorites.size.toString()
                        tvStatWatchLater.text = lists.watchLater.size.toString()
                        tvStatWantToWatch.text = lists.wantToWatch.size.toString()
                        tvStatCompleted.text = lists.completed.size.toString()
                        pieChart.setCounts(
                            lists.favorites.size,
                            lists.watchLater.size,
                            lists.wantToWatch.size,
                            lists.completed.size
                        )
                    }
                }
            )
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
        // If the user is logged in, try to pull the latest profile from the cloud.
        // This ensures the profile screen always shows the current name/username/avatar
        // even if they were changed from another device.
        val user = AuthManager.getCurrentUser(this)
        if (user != null) {
            AuthManager.restoreUserFromCloud(this) { changed ->
                if (changed) {
                    runOnUiThread { updateUI() }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        listsListener?.remove()
        listsListener = null
    }
}
