package com.yazan.manga

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
        private const val RC_ACCOUNT_PICKER = 9002
        // RC_IMAGE_PICK removed — replaced by Photo Picker (no permission needed)
    }

    /**
     * Android Photo Picker (official, no permissions required on any Android version).
     * Replaces the old ACTION_PICK + MediaStore approach which could fail on
     * Android 7-9 without READ_EXTERNAL_STORAGE permission.
     */
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            saveProfileImage(uri)
        }
    }

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var firebaseAuth: FirebaseAuth

    private lateinit var avatarImage: android.widget.ImageView
    private lateinit var avatarLetter: TextView
    private lateinit var btnChangeAvatar: ImageButton
    private lateinit var tvName: TextView
    private lateinit var tvUsername: TextView
    private lateinit var tvEmail: TextView
    private lateinit var tvCreatedAt: TextView
    private lateinit var tvBirthDate: TextView
    private lateinit var tvCountry: TextView
    private lateinit var tvAdminBadge: TextView
    private lateinit var pieChart: PieChartView
    private lateinit var tvStatFavorites: TextView
    private lateinit var tvStatWatchLater: TextView
    private lateinit var tvStatWantToWatch: TextView
    private lateinit var tvStatCompleted: TextView
    private lateinit var btnLogin: MaterialButton
    private lateinit var loginProgress: ProgressBar
    private lateinit var btnLogout: MaterialButton
    private lateinit var btnChangeName: MaterialButton
    private lateinit var btnChangeUsername: MaterialButton
    private lateinit var btnEditProfileInfo: MaterialButton
    private lateinit var btnAdminPanel: MaterialButton
    private lateinit var statsContainer: View
    private var listsListener: ListenerRegistration? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)

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
        tvCreatedAt = findViewById(R.id.tvCreatedAt)
        tvBirthDate = findViewById(R.id.tvBirthDate)
        tvCountry = findViewById(R.id.tvCountry)
        tvAdminBadge = findViewById(R.id.tvAdminBadge)
        pieChart = findViewById(R.id.pieChart)
        tvStatFavorites = findViewById(R.id.tvStatFavorites)
        tvStatWatchLater = findViewById(R.id.tvStatWatchLater)
        tvStatWantToWatch = findViewById(R.id.tvStatWantToWatch)
        tvStatCompleted = findViewById(R.id.tvStatCompleted)
        btnLogin = findViewById(R.id.btnLogin)
        loginProgress = findViewById(R.id.loginProgress)
        btnLogout = findViewById(R.id.btnLogout)
        btnChangeName = findViewById(R.id.btnChangeName)
        btnChangeUsername = findViewById(R.id.btnChangeUsername)
        btnEditProfileInfo = findViewById(R.id.btnEditProfileInfo)
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
        btnEditProfileInfo.setOnClickListener {
            showEditProfileInfoDialog()
        }

        // Profile picture — both the avatar image and the dedicated camera button open the picker
        val openPicker = View.OnClickListener {
            if (AuthManager.getCurrentUser(this) == null) {
                Toast.makeText(this, "سجّل الدخول أولاً", Toast.LENGTH_SHORT).show()
                return@OnClickListener
            }
            // Android Photo Picker (no permissions required, works on all versions)
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
        // Check if this device is blocked from login (bot protection)
        val deviceId = com.yazan.manga.data.AuthManager.getDeviceId(this)
        com.yazan.manga.data.BotProtection.checkLoginBlock(deviceId) { block ->
            runOnUiThread {
                if (block.blocked) {
                    Toast.makeText(this, block.reason, Toast.LENGTH_LONG).show()
                } else {
                    try {
                        // Show spinner while Google Sign-In flow is in progress
                        setLoginLoading(true)
                        val signInIntent = googleSignInClient.signInIntent
                        startActivityForResult(signInIntent, RC_SIGN_IN)
                    } catch (e: Exception) {
                        setLoginLoading(false)
                        Toast.makeText(this, "تعذّر تسجيل الدخول. تأكد من وجود خدمات Google Play", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    /** Show/hide the spinning loader on the login button + enable/disable the button. */
    private fun setLoginLoading(loading: Boolean) {
        if (loading) {
            loginProgress.visibility = View.VISIBLE
            btnLogin.text = ""
            btnLogin.icon = null
            btnLogin.isEnabled = false
        } else {
            loginProgress.visibility = View.GONE
            btnLogin.text = "تسجيل الدخول عبر Google"
            btnLogin.setIconResource(R.drawable.ic_google)
            btnLogin.isEnabled = true
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
            // RC_IMAGE_PICK removed — Photo Picker uses callback (pickImage)
            RC_SIGN_IN -> {
                if (resultCode != Activity.RESULT_OK) {
                    setLoginLoading(false)
                    Toast.makeText(this, "تم إلغاء تسجيل الدخول", Toast.LENGTH_SHORT).show()
                    return
                }
                try {
                    val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                        .getResult(ApiException::class.java)
                    if (account != null && account.idToken != null) {
                        firebaseAuthWithGoogle(account.idToken!!)
                    } else {
                        setLoginLoading(false)
                        Toast.makeText(this, "تعذّر الحصول على بيانات الحساب", Toast.LENGTH_LONG).show()
                    }
                } catch (e: ApiException) {
                    setLoginLoading(false)
                    Toast.makeText(this, "فشل تسجيل الدخول: ${e.statusCode}", Toast.LENGTH_LONG).show()
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
                    setLoginLoading(false)
                    // DON'T fallback to Account Picker — force Google Sign-In
                    Toast.makeText(this, "فشل المصادقة مع Google. حاول مرة أخرى", Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun loginWithEmail(email: String, displayName: String) {
        val error = AuthManager.processEmailAuth(this, email, displayName)
        handleAuthResult(error)
    }

    private fun handleAuthResult(error: String?) {
        // Always hide the login spinner once auth completes
        setLoginLoading(false)
        if (error == null) {
            val user = AuthManager.getCurrentUser(this)
            if (user?.isAdmin == true) {
                Toast.makeText(this, "👑 مرحباً يا مشرف!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "تم تسجيل الدخول بنجاح", Toast.LENGTH_SHORT).show()
            }
            updateUI()
            // Clear any failed-login attempts on successful login
            val deviceId = com.yazan.manga.data.AuthManager.getDeviceId(this)
            com.yazan.manga.data.BotProtection.clearLoginAttempts(deviceId)
            // After login, the cloud sync (restoreUserFromCloud) runs async.
            // Re-run updateUI after a short delay so the restored name/username/avatar
            // are reflected in the UI.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                updateUI()
            }, 1500)
        } else {
            // Show a generic error — never expose the real cause to the user
            Toast.makeText(this, "حدث خطأ أثناء تسجيل الدخول", Toast.LENGTH_LONG).show()
            // Record the failed attempt for bot protection
            val deviceId = com.yazan.manga.data.AuthManager.getDeviceId(this)
            com.yazan.manga.data.BotProtection.recordFailedLogin(deviceId)
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
                val result = AuthManager.changeName(this, newName)
                if (result != null && result.startsWith("تم")) {
                    // Success message from server
                    Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                    updateUI()
                } else {
                    // Error message
                    Toast.makeText(this, result ?: "حدث خطأ", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showEditProfileInfoDialog() {
        val user = AuthManager.getCurrentUser(this) ?: return
        val countryInput = EditText(this).apply {
            setText(user.country)
            hint = "الدولة — اختياري"
            setPadding(40, 24, 40, 24)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val birthDateBtn = android.widget.Button(this).apply {
            text = if (user.birthDate.isNotEmpty())
                "🎂 تاريخ الميلاد: ${user.birthDate}"
            else
                "🎂 تحديد تاريخ الميلاد (اختياري)"
            background = getDrawable(R.drawable.bg_secondary_button)
            setTextColor(getColor(R.color.text_primary))
        }
        var selectedBirthDate = user.birthDate
        birthDateBtn.setOnClickListener {
            val cal = java.util.Calendar.getInstance()
            // Parse existing date if set
            if (selectedBirthDate.isNotEmpty()) {
                try {
                    val parts = selectedBirthDate.split("-")
                    cal.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt())
                } catch (_: Exception) {}
            }
            android.app.DatePickerDialog(this,
                { _, year, month, day ->
                    selectedBirthDate = String.format("%04d-%02d-%02d", year, month + 1, day)
                    birthDateBtn.text = "🎂 تاريخ الميلاد: $selectedBirthDate"
                },
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH),
                cal.get(java.util.Calendar.DAY_OF_MONTH)
            ).show()
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(20, 16, 20, 0)
            addView(birthDateBtn)
            addView(countryInput)
        }
        AlertDialog.Builder(this)
            .setTitle("تعديل المعلومات الشخصية")
            .setView(container)
            .setPositiveButton("حفظ") { _, _ ->
                val country = countryInput.text.toString().trim()
                AuthManager.updateBirthDate(this, selectedBirthDate)
                AuthManager.updateCountry(this, country)
                Toast.makeText(this, "تم تحديث المعلومات", Toast.LENGTH_SHORT).show()
                updateUI()
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

            // Strip any legacy '(مشرف)' suffix from the name — the admin badge
            // (green pill) is shown separately, so the name should be clean.
            val displayName = user.name
                .removeSuffix(" (مشرف)")
                .removeSuffix("(مشرف)")
                .trim()
            tvName.text = displayName
            tvUsername.text = "@${user.username.removePrefix("@")}"
            // Show the email ONLY on the owner's own profile (this activity
            // is only opened for the current user, not for viewing others).
            tvEmail.visibility = View.VISIBLE
            tvEmail.text = user.email
            tvAdminBadge.visibility = if (user.isAdmin) View.VISIBLE else View.GONE

            // Account creation date
            if (user.createdAt > 0) {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                tvCreatedAt.text = "عضو منذ ${sdf.format(java.util.Date(user.createdAt))}"
                tvCreatedAt.visibility = View.VISIBLE
            } else {
                // createdAt is 0 or missing — don't show 1970, just hide it.
                // The server sets createdAt on first profile PUT, so this
                // should only happen for users created before the fix.
                tvCreatedAt.visibility = View.GONE
            }

            // Optional birth date (only shown if set)
            if (user.birthDate.isNotEmpty()) {
                tvBirthDate.text = "🎂 تاريخ الميلاد: ${user.birthDate}"
                tvBirthDate.visibility = View.VISIBLE
            } else {
                tvBirthDate.visibility = View.GONE
            }

            // Optional country (only shown if set)
            if (user.country.isNotEmpty()) {
                tvCountry.text = "🌍 الدولة: ${user.country}"
                tvCountry.visibility = View.VISIBLE
            } else {
                tvCountry.visibility = View.GONE
            }

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
            btnEditProfileInfo.visibility = View.VISIBLE
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
            btnEditProfileInfo.visibility = View.GONE
            btnAdminPanel.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        // Just update UI from local storage (which has the latest changes).
        // DON'T call restoreUserFromCloud here — it overwrites local name/username
        // changes with stale cloud data. Cloud sync happens on app start (MangaApp).
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        listsListener?.remove()
        listsListener = null
    }
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in, R.anim.slide_out_left)
    }
}