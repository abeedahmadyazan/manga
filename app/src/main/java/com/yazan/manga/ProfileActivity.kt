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
import com.yazan.manga.ui.BaseSwipeBackActivity
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

class ProfileActivity : BaseSwipeBackActivity() {

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
    private lateinit var avatarProgress: ProgressBar
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
    private lateinit var btnEditCountry: MaterialButton
    private lateinit var btnBlockedUsers: MaterialButton
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
        avatarProgress = findViewById(R.id.avatarProgress)
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
        btnEditCountry = findViewById(R.id.btnEditCountry)
        btnBlockedUsers = findViewById(R.id.btnBlockedUsers)
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
            showBirthDateDialog()
        }
        btnEditCountry.setOnClickListener {
            showCountryDialog()
        }
        btnBlockedUsers.setOnClickListener {
            startActivity(Intent(this, BlockedUsersActivity::class.java))
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
        // Show a circular progress overlay and disable the change button while
        // saving. setAvatar does disk I/O + a 15s-countdown API call, so running
        // it on the main thread was blocking the UI (looked like the app froze).
        avatarProgress.visibility = View.VISIBLE
        btnChangeAvatar.isEnabled = false
        avatarImage.isEnabled = false

        Thread {
            // Copy the image into internal storage + upload base64 to the cloud.
            val savedPath = AuthManager.setAvatar(this, uri)
            runOnUiThread {
                avatarProgress.visibility = View.GONE
                btnChangeAvatar.isEnabled = true
                avatarImage.isEnabled = true
                if (savedPath != null) {
                    Toast.makeText(this, "تم تحديث الصورة", Toast.LENGTH_SHORT).show()
                    // Reload the avatar, bypassing Glide's cache: the file path is
                    // the same as before (avatar_<email>.jpg) but the CONTENT has
                    // changed. Without skipMemoryCache + diskCacheStrategy NONE,
                    // Glide would show the OLD image from its cache forever.
                    reloadAvatarNoCache(savedPath)
                } else {
                    Toast.makeText(this, "فشل حفظ الصورة", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** Force-reload the avatar from the given path, bypassing Glide's caches. */
    private fun reloadAvatarNoCache(path: String) {
        try {
            avatarImage.visibility = View.VISIBLE
            avatarLetter.visibility = View.GONE
            // Add a cache-busting signature so Glide treats this as a new request.
            val sig = com.bumptech.glide.signature.ObjectKey("avatar_${System.currentTimeMillis()}")
            Glide.with(this)
                .load(java.io.File(path))
                .circleCrop()
                .signature(sig)
                .skipMemoryCache(true)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                .placeholder(R.drawable.bg_avatar_gradient)
                .into(avatarImage)
        } catch (e: Exception) {
            // Fallback to the normal updateUI path
            updateUI()
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
        val currentName = user.name
        val input = EditText(this).apply {
            setText(currentName)
            setSelection(currentName.length)
            hint = "اكتب اسمك الجديد"
            setPadding(40, 24, 40, 24)
        }
        // Cooldown status line — updated asynchronously from the cloud
        val cooldownStatus = TextView(this).apply {
            text = "⏳ جارٍ التحقق من إمكانية التغيير..."
            setPadding(40, 8, 40, 8)
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#FFB74D"))
        }
        val policy = TextView(this).apply {
            text = "ℹ️ يمكن تغيير الاسم مرة واحدة كل شهر."
            setPadding(40, 0, 40, 16)
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#aaaaaa"))
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(20, 16, 20, 0)
            addView(input)
            addView(cooldownStatus)
            addView(policy)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("تغيير الاسم")
            .setMessage("الاسم الذي يظهر للآخرين على تعليقاتك وملفك الشخصي.")
            .setView(container)
            .setPositiveButton("حفظ", null)
            .setNegativeButton("إلغاء", null)
            .create()

        // Async fetch the server-side cooldown so we can show remaining days up-front
        AuthManager.fetchCloudUser(user.email) { cu ->
            runOnUiThread {
                val msg = cooldownMessage(cu?.lastNameChange ?: 0L, 30)
                cooldownStatus.text = msg
                cooldownStatus.setTextColor(
                    android.graphics.Color.parseColor(if (msg.startsWith("✅")) "#4CAF50" else "#FFB74D")
                )
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "الاسم لا يمكن أن يكون فارغاً", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (newName == currentName) { dialog.dismiss(); return@setOnClickListener }
                val result = AuthManager.changeName(this, newName)
                if (result != null && result.startsWith("تم")) {
                    Toast.makeText(this, result, Toast.LENGTH_SHORT).show()
                    updateUI()
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, result ?: "حدث خطأ", Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }

    /** Build a human-readable cooldown message. windowDays = the cooldown length. */
    private fun cooldownMessage(lastChangeMs: Long, windowDays: Int): String {
        if (lastChangeMs <= 0L) return "✅ يمكنك التغيير الآن."
        val windowMs = windowDays * 24L * 60 * 60 * 1000
        val elapsed = System.currentTimeMillis() - lastChangeMs
        if (elapsed >= windowMs) return "✅ يمكنك التغيير الآن."
        val remaining = windowMs - elapsed
        val days = (remaining / (24L * 60 * 60 * 1000)).toInt()
        val hours = ((remaining % (24L * 60 * 60 * 1000)) / (60L * 60 * 1000)).toInt()
        return if (days > 0) "⏳ تبقّى $days يوم قبل أن تتمكن من التغيير."
        else "⏳ تبقّى $hours ساعة قبل أن تتمكن من التغيير."
    }

    /**
     * Birth date editor — a separate dialog with Day → Month → Year pickers.
     * No free-text editing: the date is built ONLY from NumberPicker selections,
     * so the format is always valid (YYYY-MM-DD with Latin digits).
     */
    private fun showBirthDateDialog() {
        val user = AuthManager.getCurrentUser(this) ?: return

        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val minYear = 1940
        val maxYear = currentYear - 10  // لا يقل عمره عن 10 سنوات

        // Parse existing birth date (if any) — normalize Arabic digits first
        // (some old dates were saved as "٢٠٠٩-٠٩-٢٤" before the Latin-digit fix).
        val normalized = toLatinDigitsLocal(user.birthDate)
        var initYear = maxYear - 20
        var initMonth = 1
        var initDay = 1
        if (normalized.isNotEmpty()) {
            try {
                val p = normalized.split("-")
                initYear = p[0].toInt().coerceIn(minYear, maxYear)
                initMonth = p[1].toInt().coerceIn(1, 12)
                initDay = p[2].toInt().coerceIn(1, 31)
            } catch (_: Exception) {}
        }

        fun daysInMonth(y: Int, m: Int): Int = java.util.Calendar.getInstance().apply {
            set(y, m - 1, 1)
        }.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)

        // Day picker (rightmost in RTL)
        val dayPicker = android.widget.NumberPicker(this).apply {
            minValue = 1
            maxValue = daysInMonth(initYear, initMonth)
            value = initDay.coerceAtMost(maxValue)
            wrapSelectorWheel = false
        }
        // Month picker (middle)
        val monthPicker = android.widget.NumberPicker(this).apply {
            minValue = 1
            maxValue = 12
            value = initMonth
            displayedValues = arrayOf(
                "1 - يناير", "2 - فبراير", "3 - مارس", "4 - أبريل",
                "5 - مايو", "6 - يونيو", "7 - يوليو", "8 - أغسطس",
                "9 - سبتمبر", "10 - أكتوبر", "11 - نوفمبر", "12 - ديسمبر"
            )
            wrapSelectorWheel = false
        }
        // Year picker (leftmost in RTL)
        val yearPicker = android.widget.NumberPicker(this).apply {
            minValue = minYear
            maxValue = maxYear
            value = initYear
            wrapSelectorWheel = false
        }

        var interacted = false
        var cleared = false

        val recomputeDays = {
            val newMax = daysInMonth(yearPicker.value, monthPicker.value)
            if (dayPicker.maxValue != newMax) dayPicker.maxValue = newMax
            if (dayPicker.value > newMax) dayPicker.value = newMax
        }
        dayPicker.setOnValueChangedListener { _, _, _ -> interacted = true; cleared = false }
        monthPicker.setOnValueChangedListener { _, _, _ -> recomputeDays(); interacted = true; cleared = false }
        yearPicker.setOnValueChangedListener { _, _, _ -> recomputeDays(); interacted = true; cleared = false }

        val dayLabel = TextView(this).apply { text = "اليوم"; gravity = android.view.Gravity.CENTER; textSize = 12f; setTextColor(getColor(R.color.text_secondary)) }
        val monthLabel = TextView(this).apply { text = "الشهر"; gravity = android.view.Gravity.CENTER; textSize = 12f; setTextColor(getColor(R.color.text_secondary)) }
        val yearLabel = TextView(this).apply { text = "السنة"; gravity = android.view.Gravity.CENTER; textSize = 12f; setTextColor(getColor(R.color.text_secondary)) }

        val pickersRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
            gravity = android.view.Gravity.CENTER
            val dayCol = android.widget.LinearLayout(this@ProfileActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
                addView(dayLabel); addView(dayPicker)
            }
            val monthCol = android.widget.LinearLayout(this@ProfileActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
                addView(monthLabel); addView(monthPicker)
            }
            val yearCol = android.widget.LinearLayout(this@ProfileActivity).apply {
                orientation = android.widget.LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER
                addView(yearLabel); addView(yearPicker)
            }
            addView(dayCol)
            addView(android.view.View(this@ProfileActivity).apply { layoutParams = android.widget.LinearLayout.LayoutParams(24, 0) })
            addView(monthCol)
            addView(android.view.View(this@ProfileActivity).apply { layoutParams = android.widget.LinearLayout.LayoutParams(24, 0) })
            addView(yearCol)
        }

        val clearBtn = android.widget.TextView(this).apply {
            text = "🗑️ مسح تاريخ الميلاد"
            setTextColor(getColor(R.color.danger))
            setPadding(16, 16, 16, 8)
            gravity = android.view.Gravity.CENTER
            setOnClickListener {
                cleared = true
                interacted = true
                text = "✓ سيتم حذف تاريخ الميلاد عند الحفظ"
            }
        }

        val birthCooldown = TextView(this).apply {
            text = "⏳ جارٍ التحقق..."
            setPadding(40, 4, 40, 4); textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#FFB74D"))
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(20, 16, 20, 0)
            addView(TextView(this@ProfileActivity).apply {
                text = "🎂 تاريخ الميلاد"; setPadding(40, 0, 40, 0); textSize = 13f
                setTextColor(getColor(R.color.text_primary))
            })
            addView(pickersRow)
            addView(clearBtn)
            addView(birthCooldown)
        }

        AuthManager.fetchCloudUser(user.email) { cu ->
            runOnUiThread {
                val bm = cooldownMessage(cu?.lastBirthDateChange ?: 0L, 30)
                birthCooldown.text = bm
                birthCooldown.setTextColor(android.graphics.Color.parseColor(if (bm.startsWith("✅")) "#4CAF50" else "#FFB74D"))
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("تاريخ الميلاد")
            .setView(container)
            .setPositiveButton("حفظ", null)
            .setNegativeButton("إلغاء", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val y = yearPicker.value
                val m = monthPicker.value
                val d = dayPicker.value
                val newBirthDate = when {
                    cleared -> ""
                    interacted -> String.format(java.util.Locale.US, "%04d-%02d-%02d", y, m, d)
                    else -> user.birthDate
                }

                if (newBirthDate == user.birthDate) { dialog.dismiss(); return@setOnClickListener }

                Thread {
                    val err = AuthManager.updateBirthDate(this, newBirthDate)
                    runOnUiThread {
                        if (err == null) {
                            Toast.makeText(this, if (newBirthDate.isEmpty()) "تم حذف تاريخ الميلاد" else "تم تحديث تاريخ الميلاد", Toast.LENGTH_SHORT).show()
                            updateUI()
                            dialog.dismiss()
                        } else {
                            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    /**
     * Country editor — a separate dialog with a free-text input for the country.
     * (Country is the only profile field that stays free-text; the birth date is
     *  fully picker-driven to prevent invalid formats.)
     */
    private fun showCountryDialog() {
        val user = AuthManager.getCurrentUser(this) ?: return

        val countryInput = EditText(this).apply {
            setText(user.country)
            hint = "مثال: سوريا، مصر، السعودية..."
            setPadding(40, 24, 40, 24)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val countryCooldown = TextView(this).apply {
            text = "⏳ جارٍ التحقق..."
            setPadding(40, 4, 40, 4); textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#FFB74D"))
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(20, 16, 20, 0)
            addView(TextView(this@ProfileActivity).apply {
                text = "🌍 الدولة (اختياري)"; setPadding(40, 0, 40, 0); textSize = 13f
                setTextColor(getColor(R.color.text_primary))
            })
            addView(countryInput)
            addView(countryCooldown)
        }

        AuthManager.fetchCloudUser(user.email) { cu ->
            runOnUiThread {
                val cm = cooldownMessage(cu?.lastCountryChange ?: 0L, 7)
                countryCooldown.text = cm
                countryCooldown.setTextColor(android.graphics.Color.parseColor(if (cm.startsWith("✅")) "#4CAF50" else "#FFB74D"))
            }
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("الدولة")
            .setView(container)
            .setPositiveButton("حفظ", null)
            .setNegativeButton("إلغاء", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val country = countryInput.text.toString().trim()
                if (country == user.country) { dialog.dismiss(); return@setOnClickListener }

                Thread {
                    val err = AuthManager.updateCountry(this, country)
                    runOnUiThread {
                        if (err == null) {
                            Toast.makeText(this, "تم تحديث الدولة", Toast.LENGTH_SHORT).show()
                            updateUI()
                            dialog.dismiss()
                        } else {
                            Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        }
        dialog.show()
    }

    /** Convert Arabic-Indic (and Persian) digits to Latin 0-9. */
    private fun toLatinDigitsLocal(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(when {
                c in '\u0660'..'\u0669' -> ('0' + (c - '\u0660'))
                c in '\u06F0'..'\u06F9' -> ('0' + (c - '\u06F0'))
                else -> c
            })
        }
        return sb.toString()
    }

    /**
     * Show the list of users I have blocked, with an "unblock" button next to each.
     * Fetches from /api/blocks (server-side enforced — the user can only see
     * blocks where they are the blocker).
     */
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
                // Cache-busting signature: when the avatar file is rewritten, its
                // lastModified timestamp changes → Glide treats it as a new image
                // instead of showing the stale cached one.
                val avatarFile = java.io.File(user.avatar)
                val sig = com.bumptech.glide.signature.ObjectKey(
                    "avatar_${avatarFile.lastModified()}_${avatarFile.length()}"
                )
                Glide.with(this).load(avatarFile).circleCrop()
                    .signature(sig)
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
            btnEditCountry.visibility = View.VISIBLE
            btnBlockedUsers.visibility = View.VISIBLE
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
            btnEditCountry.visibility = View.GONE
            btnBlockedUsers.visibility = View.GONE
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
}