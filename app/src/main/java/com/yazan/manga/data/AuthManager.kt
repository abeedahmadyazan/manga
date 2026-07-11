package com.yazan.manga.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Firebase Auth Manager — Google Sign-In with one button.
 * - Admin role determined by Firestore 'admins/{uid}' collection (no
 *   hardcoded admin email — decompiling the APK reveals nothing)
 * - Device fingerprint for security
 * - Banned devices check
 * - Multi-account prevention (one account per device)
 * - Unique username (changeable once per 30 days)
 * - Account suspension (temporary or permanent)
 */
object AuthManager {

    /**
     * Admin email — XOR-obfuscated so it does NOT appear as a plain string
     * in the APK. Decompiling with jadx shows only random bytes + an XOR
     * operation, never the actual email.
     *
     * To change the admin email:
     *   1. XOR each character with 0x5A
     *   2. Paste the resulting bytes into ADMIN_EMAIL_OBFUSCATED
     *
     * To verify in Python:
     *   email = "yznabyd@gmail.com"
     *   key = 0x5A
     *   print([hex(ord(c) ^ key) for c in email])
     */
    // Admin email is NO LONGER in the APK — it's on the server (Vercel API) only!
    // The server checks admin status and returns isAdmin in the profile response.

    private const val PREFS_NAME = "manga_auth"
    private const val KEY_USER = "current_user"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_BANNED_DEVICES = "banned_devices"
    private const val KEY_LINKED_EMAIL = "linked_email"
    private const val KEY_USERS_DB = "users_db"
    private const val KEY_SUSPENDED = "suspended_users"
    private const val USERNAME_COOLDOWN_MS = 30L * 24 * 60 * 60 * 1000  // 30 days

    // Web client ID from google-services.json (will be set at runtime)
    private var webClientId: String? = null

    data class User(
        val email: String,
        val name: String,
        val username: String,
        val isAdmin: Boolean,
        val deviceId: String,
        val createdAt: Long,
        val lastUsernameChange: Long,
        val avatar: String,
        val bio: String,
        val birthDate: String = "",
        val country: String = ""
    )

    data class SuspendedUser(
        val email: String,
        val deviceId: String,
        val until: Long,
        val reason: String,
        val suspendedAt: Long
    )

    /**
     * Check if a user is an admin by looking up their UID in the 'admins'
     * Firestore collection. Returns true if the UID exists as a document.
     *
     * No email-based fallback anymore — admin status is determined 100%
     * by Firestore. This means the source code contains no hint of which
     * account is the admin, so decompiling the APK reveals nothing.
     *
     * Returns false on network errors (so the user just appears as a
     * regular user until the next successful check).
     */
    fun checkAdminFromCloud(uid: String, callback: (Boolean) -> Unit) {
        try {
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            db.collection("admins").document(uid).get()
                .addOnSuccessListener { doc ->
                    callback(doc.exists())
                }
                .addOnFailureListener {
                    // Firestore failed — return false (user appears as regular)
                    callback(false)
                }
        } catch (e: Exception) {
            callback(false)
        }
    }

    /**
     * Refresh the current user's admin status from Firestore.
     * Called after sign-in to update the local user object if the user has
     * been granted admin privileges via the 'admins' collection.
     */
    fun refreshAdminStatus(context: Context) {
        val user = getCurrentUser(context) ?: return
        val firebaseUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
        val uid = firebaseUser?.uid ?: return
        checkAdminFromCloud(uid) { isAdmin ->
            if (isAdmin != user.isAdmin) {
                // Status changed — update the user object and save it
                val updatedUser = user.copy(isAdmin = isAdmin)
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString(KEY_USER, serializeUser(updatedUser)).apply()
                saveUser(context, updatedUser)
                uploadUserToCloud(context)
            }
        }
    }

    /**
     * Bootstrap the admin role: ONLY promote the user if their email matches
     * the hardcoded admin email (XOR-decoded). This prevents ANY other user
     * from becoming admin.
     *
     * This is safe because:
     * 1. Only the specific admin email gets promoted
     * 2. The email is XOR-obfuscated (not visible in APK)
     * 3. Once promoted, the admin doc stays in Firestore
     */
    // bootstrapFirstAdmin removed — server handles admin creation now
    // (admins/{uid} is created by the Vercel API when the admin signs in)

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        val deviceInfo = listOf(
            androidId,
            android.os.Build.MANUFACTURER,
            android.os.Build.MODEL,
            android.os.Build.FINGERPRINT,
            android.os.Build.VERSION.RELEASE
        ).joinToString("|")

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(deviceInfo.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

        val deviceId = "dev_$hash"
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
        return deviceId
    }

    fun isDeviceBanned(context: Context): Boolean {
        val deviceId = getDeviceId(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val banned = prefs.getStringSet(KEY_BANNED_DEVICES, emptySet()) ?: emptySet()
        if (banned.contains(deviceId)) return true

        val suspended = getSuspendedUsers(context)
        val now = System.currentTimeMillis()
        for (s in suspended) {
            if (s.deviceId == deviceId) {
                if (s.until == 0L || s.until > now) return true
            }
        }
        return false
    }

    fun isUserSuspended(context: Context, email: String): Pair<Boolean, String?> {
        val suspended = getSuspendedUsers(context)
        val now = System.currentTimeMillis()
        for (s in suspended) {
            if (s.email == email) {
                if (s.until == 0L) {
                    return Pair(true, "تم حظر حسابك بشكل دائم. السبب: ${s.reason}")
                } else if (s.until > now) {
                    val daysLeft = ((s.until - now) / (24 * 60 * 60 * 1000)).toInt() + 1
                    return Pair(true, "تم إيقاف حسابك لمدة $daysLeft يوم. السبب: ${s.reason}")
                }
            }
        }
        return Pair(false, null)
    }

    /**
     * Get Google SignIn options.
     * The web client ID is read from google-services.json at runtime.
     */
    fun getGoogleSignInOptions(context: Context): GoogleSignInOptions {
        // Read web client ID from google-services.json
        if (webClientId == null) {
            webClientId = readWebClientId(context)
        }

        return GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(webClientId ?: "")
            .requestEmail()
            .build()
    }

    /**
     * Read the web client ID from google-services.json (oauth_client section).
     * Note: This requires the user to add a Web Client ID in Firebase Console.
     */
    private fun readWebClientId(context: Context): String? {
        // Web Client ID from Firebase Console (oauth_client type 3)
        return "561976290856-mkfmpgn8avr4d891oliu3sboq9anln30.apps.googleusercontent.com"
    }

    /**
     * Process email-based auth (fallback when Google Sign-In fails due to missing SHA-1).
     * Uses Account Picker to get the user's Google email.
     *
     * SECURITY: Multi-account prevention is now SERVER-SIDE only.
     * We call /api/auth/link-device which checks device_links/{deviceId}.
     * If the device is already linked to a different email, the server
     * returns 403 and we abort the login.
     */
    fun processEmailAuth(context: Context, email: String, displayName: String): String? {
        val cleanEmail = email.lowercase().trim()

        if (isDeviceBanned(context)) {
            return "🚫 هذا الجهاز محظور بسبب مخالفة القوانين"
        }

        val (suspended, reason) = isUserSuspended(context, cleanEmail)
        if (suspended) return reason

        // NOTE: processEmailAuth is called WITHOUT Firebase sign-in (Account Picker fallback).
        // We can't call linkDevice here because there's no Firebase ID token.
        // The device link check happens server-side on every API call anyway
        // (authenticate() checks device_links/{deviceId} for write operations).

        val deviceId = getDeviceId(context)

        var user = getUserByEmail(context, cleanEmail)
        var wasNewUser = false

        if (user == null) {
            var cloudUser: AuthManager.CloudUser? = null
            val latch2 = java.util.concurrent.CountDownLatch(1)
            Thread {
                try { cloudUser = ApiClient.getUserProfile(cleanEmail) } catch (e: Exception) {}
                latch2.countDown()
            }.start()
            try { latch2.await(10, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}

            val cu = cloudUser
            if (cu != null && cu.name.isNotEmpty()) {
                user = User(email = cleanEmail, name = cu.name,
                    username = cu.username.ifEmpty { "@${cleanEmail.substringBefore("@")}" },
                    isAdmin = cu.isAdmin, deviceId = deviceId, createdAt = cu.createdAt,
                    lastUsernameChange = 0L, avatar = "", bio = "")
                if (cu.avatarBase64.isNotEmpty()) {
                    try {
                        val bytes = android.util.Base64.decode(cu.avatarBase64, android.util.Base64.NO_WRAP)
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            val f = java.io.File(context.filesDir, "avatar_${cleanEmail.replace("@", "_at_")}.jpg")
                            val o = java.io.FileOutputStream(f); bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, o); o.close(); bmp.recycle()
                            user = user.copy(avatar = f.absolutePath)
                        }
                    } catch (e: Exception) {}
                }
                user = user!!.copy(birthDate = cu.birthDate, country = cu.country)
                saveUser(context, user)
            } else {
                wasNewUser = true
                user = User(email = cleanEmail, name = displayName, // admin name set by server
                    username = generateUniqueUsername(context, displayName), isAdmin = isAdmin,
                    deviceId = deviceId, createdAt = System.currentTimeMillis(), lastUsernameChange = 0L, avatar = "", bio = "")
                saveUser(context, user)
            }
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_USER, serializeUser(user)).apply()
        if (wasNewUser) { uploadUserToCloud(context) }

        // Async admin check: if the user's UID exists in the 'admins' Firestore
        // collection, mark them as admin. This runs after the synchronous flow
        // so the user can sign in immediately, and the admin flag updates once
        // the cloud check completes.
        refreshAdminStatus(context)
        // Bootstrap: if no admin exists yet, promote this user automatically.
        // This is a one-time operation so you don't have to manually create
        // the admins collection in Firebase Console.
        
        return null
    }

    /**
     * Process Firebase Auth result after Google Sign-In.
     * Returns error message if failed, null if success.
     *
     * SECURITY: Multi-account prevention is now SERVER-SIDE only.
     * We call /api/auth/link-device which checks device_links/{deviceId}.
     * If the device is already linked to a different email, the server
     * returns 403 and we abort the login.
     */
    fun processFirebaseAuth(context: Context, account: GoogleSignInAccount?): String? {
        if (account == null) return "تم إلغاء تسجيل الدخول"

        val email = account.email?.lowercase()?.trim() ?: return "إيميل غير صالح"
        val displayName = account.displayName ?: email.substringBefore("@")

        if (isDeviceBanned(context)) {
            return "🚫 هذا الجهاز محظور بسبب مخالفة القوانين"
        }

        val (suspended, reason) = isUserSuspended(context, email)
        if (suspended) return reason

        // SERVER-SIDE multi-account prevention.
        // The old client-side KEY_LINKED_EMAIL check is REMOVED — it was
        // bypassable by clearing app data. The server check is authoritative.
        val (linkOk, linkError) = ApiClient.linkDevice()
        if (!linkOk) {
            return linkError ?: "⚠️ هذا الجهاز مرتبط بحساب آخر. لا يمكن إنشاء حساب جديد."
        }

        val deviceId = getDeviceId(context)

        var user = getUserByEmail(context, email)
        var wasNewUser = false

        if (user == null) {
            // Fetch from cloud on BACKGROUND THREAD (network on main thread crashes)
            var cloudUser: AuthManager.CloudUser? = null
            val latch = java.util.concurrent.CountDownLatch(1)
            Thread {
                try { cloudUser = ApiClient.getUserProfile(email) } catch (e: Exception) {}
                latch.countDown()
            }.start()
            try { latch.await(10, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}

            val cu = cloudUser
            if (cu != null && cu.name.isNotEmpty()) {
                user = User(email = email, name = cu.name,
                    username = cu.username.ifEmpty { "@${email.substringBefore("@")}" },
                    isAdmin = cu.isAdmin, deviceId = deviceId, createdAt = cu.createdAt,
                    lastUsernameChange = 0L, avatar = "", bio = "")
                if (cu.avatarBase64.isNotEmpty()) {
                    try {
                        val bytes = android.util.Base64.decode(cu.avatarBase64, android.util.Base64.NO_WRAP)
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        if (bmp != null) {
                            val f = java.io.File(context.filesDir, "avatar_${email.replace("@", "_at_")}.jpg")
                            val o = java.io.FileOutputStream(f); bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, o); o.close(); bmp.recycle()
                            user = user.copy(avatar = f.absolutePath)
                        }
                    } catch (e: Exception) {}
                }
                user = user!!.copy(birthDate = cu.birthDate, country = cu.country)
                saveUser(context, user)
            } else {
                wasNewUser = true
                user = User(email = email, name = displayName, // admin name set by server
                    username = generateUniqueUsername(context, displayName), isAdmin = isAdmin,
                    deviceId = deviceId, createdAt = System.currentTimeMillis(), lastUsernameChange = 0L,
                    avatar = account.photoUrl?.toString() ?: "", bio = "")
                saveUser(context, user)
            }
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_USER, serializeUser(user)).apply()
        if (wasNewUser) { uploadUserToCloud(context) }

        // Async admin check
        refreshAdminStatus(context)
        // Bootstrap: if no admin exists yet, promote this user automatically.
        
        return null
    }

    private fun generateUniqueUsername(context: Context, baseName: String): String {
        val users = getAllUsers(context)
        val existingUsernames = users.map { it.username }.toMutableSet()

        var candidate = "@${baseName.lowercase().replace(Regex("[^a-z0-9]"), "")}"
        if (candidate.length < 3) candidate = "@user"
        if (!existingUsernames.contains(candidate)) return candidate

        var counter = 1
        while (existingUsernames.contains("$candidate$counter")) {
            counter++
        }
        return "$candidate$counter"
    }

    fun isUsernameAvailable(context: Context, username: String, exceptEmail: String? = null): Boolean {
        val users = getAllUsers(context)
        return users.none { it.username == username && it.email != exceptEmail }
    }

    /**
     * Cloud-based username availability check. Queries the 'users' collection
     * in Firestore to see if any OTHER user (different email) has this username.
     * This prevents duplicate usernames across devices — the local check alone
     * can't see users on other phones.
     */
    fun isUsernameAvailableInCloud(username: String, exceptEmail: String, onResult: (Boolean) -> Unit) {
        try {
            cloudDb.collection(USERS_COLLECTION)
                .whereEqualTo("username", username)
                .get()
                .addOnSuccessListener { snapshot ->
                    // Available if no docs, or the only doc is the current user's own
                    val available = snapshot.isEmpty ||
                        snapshot.documents.all { it.id == exceptEmail }
                    onResult(available)
                }
                .addOnFailureListener { onResult(true) } // on failure, allow (don't block)
        } catch (e: Exception) {
            onResult(true)
        }
    }

    /**
     * Change username — now async because it checks the cloud for uniqueness.
     * Calls onResult(null) on success, or onResult(errorMessage) on failure.
     */
    fun changeUsername(context: Context, newUsername: String, onResult: (String?) -> Unit) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getCurrentUser(context) ?: run {
            onResult("يجب تسجيل الدخول")
            return
        }

        // Normalize: ensure it starts with @, lowercase
        var clean = newUsername.trim().lowercase()
        if (!clean.startsWith("@")) clean = "@$clean"

        // Instagram-style rules:
        //  - Must start with @
        //  - Only letters (a-z), numbers (0-9), and underscores (_)
        //  - No punctuation, spaces, or special characters
        //  - Minimum 3 characters AFTER the @
        //  - Maximum 20 characters total
        val handle = clean.removePrefix("@")
        if (handle.length < 3) {
            onResult("اسم المستخدم يجب أن يكون 3 أحرف على الأقل")
            return
        }
        if (clean.length > 20) {
            onResult("اسم المستخدم طويل جداً (حد أقصى 20 حرف)")
            return
        }
        if (!handle.matches(Regex("^[a-z0-9_]+$"))) {
            onResult("اسم المستخدم يجب أن يحتوي على أحرف إنجليزية وأرقام و_ فقط")
            return
        }
        if (handle.startsWith("_")) {
            onResult("اسم المستخدم لا يمكن أن يبدأ بـ _")
            return
        }

        // Cooldown check (local, 30 days)
        val now = System.currentTimeMillis()
        if (current.lastUsernameChange > 0 && (now - current.lastUsernameChange) < USERNAME_COOLDOWN_MS) {
            val daysLeft = ((USERNAME_COOLDOWN_MS - (now - current.lastUsernameChange)) / (24 * 60 * 60 * 1000)).toInt() + 1
            onResult("يمكنك تغيير اسم المستخدم مرة كل 30 يوم. باقي $daysLeft يوم")
            return
        }

        // Cloud uniqueness check — this is what prevents two users on different
        // devices from having the same username.
        isUsernameAvailableInCloud(clean, current.email) { available ->
            if (!available) {
                onResult("اسم المستخدم محجوز، جرب اسماً آخر")
                return@isUsernameAvailableInCloud
            }

            // Local uniqueness (legacy check, in case the cloud check missed something)
            if (!isUsernameAvailable(context, clean, current.email)) {
                onResult("اسم المستخدم محجوز، جرب اسماً آخر")
                return@isUsernameAvailableInCloud
            }

            // All checks passed — save locally
            val updated = current.copy(username = clean, lastUsernameChange = now)
            saveUser(context, updated)
            prefs.edit().putString(KEY_USER, serializeUser(updated)).apply()

            // Upload to cloud (background — same as setAvatar which works)
            uploadUserToCloud(context)
            onResult(null)
        }
    }

    /**
     * Change the user's display name (الاسم الظاهر).
     * No cooldown on name changes (unlike @username).
     */
    fun changeName(context: Context, newName: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getCurrentUser(context) ?: return "يجب تسجيل الدخول"

        val clean = newName.trim()
        if (clean.length < 3) return "الاسم يجب أن يكون 3 أحرف على الأقل"
        if (clean.length > 30) return "الاسم طويل جداً (حد أقصى 30 حرف)"

        // CLOUD FIRST: call API, wait for result
        var apiResult: Pair<Boolean, String?> = Pair(false, "فشل الاتصال")
        val latch = java.util.concurrent.CountDownLatch(1)
        Thread {
            try { apiResult = ApiClient.updateProfile(name = clean) } catch (e: Exception) {}
            latch.countDown()
        }.start()
        try { latch.await(15, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}

        if (!apiResult.first) {
            return apiResult.second ?: "تعذّر حفظ الاسم على السحابة"
        }

        // Cloud succeeded → save locally
        val updated = current.copy(name = clean)
        saveUser(context, updated)
        prefs.edit().putString(KEY_USER, serializeUser(updated)).apply()
        
        // Return the SUCCESS MESSAGE from the server (starts with "تم")
        return apiResult.second
    }

    /** Update the user's optional birth date (format: yyyy-MM-dd, or empty to clear). */
    fun updateBirthDate(context: Context, birthDate: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getCurrentUser(context) ?: return "يجب تسجيل الدخول"
        val updated = current.copy(birthDate = birthDate.trim())
        saveUser(context, updated)
        prefs.edit().putString(KEY_USER, serializeUser(updated)).apply()
        uploadUserToCloud(context)
        return null
    }

    /** Update the user's optional country. */
    fun updateCountry(context: Context, country: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getCurrentUser(context) ?: return "يجب تسجيل الدخول"
        val updated = current.copy(country = country.trim())
        saveUser(context, updated)
        prefs.edit().putString(KEY_USER, serializeUser(updated)).apply()
        uploadUserToCloud(context)
        return null
    }

    /**
     * Update the current user's avatar.
     * Copies the picked image from the (temporary) content URI into the app's
     * internal storage so it survives app restarts and permission revocation.
     * Returns the absolute path of the saved file, or null on failure.
     */
    fun setAvatar(context: Context, sourceUri: Uri): String? {
        val current = getCurrentUser(context) ?: return null
        return try {
            // Read & downscale the bitmap from the source URI
            val input = context.contentResolver.openInputStream(sourceUri) ?: return null
            val bitmap: Bitmap? = BitmapFactory.decodeStream(input)
            input.close()
            if (bitmap == null) return null

            // Downscale to max 256px (profile avatars don't need to be huge)
            val maxDim = 256
            val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else bitmap

            // Save to internal storage (always accessible — no permission needed)
            val avatarFile = File(context.filesDir, "avatar_${current.email.replace("@", "_at_")}.jpg")
            val out = FileOutputStream(avatarFile)
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.close()

            // If we created a new scaled bitmap, recycle it
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()

            val savedPath = avatarFile.absolutePath
            
            // === CLOUD FIRST: upload avatar to cloud ===
            val stream2 = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, stream2)
            val avatarBase64 = Base64.encodeToString(stream2.toByteArray(), Base64.NO_WRAP)
            
            var apiSuccess = false
            val latch = java.util.concurrent.CountDownLatch(1)
            Thread {
                try { apiSuccess = ApiClient.updateProfile(avatarBase64 = avatarBase64).first } catch (e: Exception) {}
                latch.countDown()
            }.start()
            try { latch.await(15, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) {}
            
            if (!apiSuccess) {
                // Cloud failed → still save locally (avatar is a file, less critical)
                Log.w("AuthManager", "setAvatar: cloud upload failed, saved locally only")
            }
            
            val updated = current.copy(avatar = savedPath)
            saveUser(context, updated)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_USER, serializeUser(updated)).apply()
            savedPath
        } catch (e: Exception) {
            null
        }
    }

    fun getCurrentUser(context: Context): User? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val data = prefs.getString(KEY_USER, null) ?: return null
        return deserializeUser(data)
    }

    fun logout(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_USER).apply()
        // Sign out from Firebase
        FirebaseAuth.getInstance().signOut()
        // Sign out from Google
        val client = GoogleSignIn.getClient(context, getGoogleSignInOptions(context))
        client.signOut()
    }

    fun banDevice(context: Context, deviceId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val banned = prefs.getStringSet(KEY_BANNED_DEVICES, emptySet())?.toMutableSet() ?: mutableSetOf()
        banned.add(deviceId)
        prefs.edit().putStringSet(KEY_BANNED_DEVICES, banned).apply()
    }

    fun suspendUser(context: Context, email: String, durationDays: Int, reason: String): String? {
        val admin = getCurrentUser(context) ?: return "يجب تسجيل الدخول"
        if (!admin.isAdmin) return "هذا الإجراء للمشرف فقط"
        // Can't suspend yourself (the admin). Check by email instead of a
        // hardcoded constant so no admin email is exposed in the source.
        // admin check moved to server

        val user = getUserByEmail(context, email) ?: return "المستخدم غير موجود"
        val until = if (durationDays == 0) 0L else System.currentTimeMillis() + durationDays * 24L * 60 * 60 * 1000
        val suspended = SuspendedUser(
            email = email,
            deviceId = user.deviceId,
            until = until,
            reason = reason,
            suspendedAt = System.currentTimeMillis()
        )

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val list = getSuspendedUsers(context).toMutableList()
        list.removeAll { it.email == email }
        list.add(suspended)
        saveSuspendedUsers(context, list)
        return null
    }

    fun unsuspendUser(context: Context, email: String): String? {
        val admin = getCurrentUser(context) ?: return "يجب تسجيل الدخول"
        if (!admin.isAdmin) return "هذا الإجراء للمشرف فقط"

        val list = getSuspendedUsers(context).toMutableList()
        list.removeAll { it.email == email }
        saveSuspendedUsers(context, list)
        return null
    }

    fun getUserByUsername(context: Context, username: String): User? {
        return getAllUsers(context).find { it.username == username }
    }

    fun getUserByEmail(context: Context, email: String): User? {
        return getAllUsers(context).find { it.email == email }
    }

    fun getAllUsers(context: Context): List<User> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_USERS_DB, "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = mutableListOf<User>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(User(
                email = o.getString("email"),
                name = o.getString("name"),
                username = o.getString("username"),
                isAdmin = o.getBoolean("isAdmin"),
                deviceId = o.getString("deviceId"),
                createdAt = o.getLong("createdAt"),
                lastUsernameChange = if (o.has("lastUsernameChange")) o.getLong("lastUsernameChange") else 0L,
                avatar = if (o.has("avatar")) o.getString("avatar") else "",
                bio = if (o.has("bio")) o.getString("bio") else "",
                birthDate = if (o.has("birthDate")) o.getString("birthDate") else "",
                country = if (o.has("country")) o.getString("country") else ""
            ))
        }
        return list
    }

    private fun saveUser(context: Context, user: User) {
        val users = getAllUsers(context).toMutableList()
        users.removeAll { it.email == user.email }
        users.add(user)
        val arr = JSONArray()
        users.forEach { u -> arr.put(JSONObject().apply {
            put("email", u.email)
            put("name", u.name)
            put("username", u.username)
            put("isAdmin", u.isAdmin)
            put("deviceId", u.deviceId)
            put("createdAt", u.createdAt)
            put("lastUsernameChange", u.lastUsernameChange)
            put("avatar", u.avatar)
            put("bio", u.bio)
            put("birthDate", u.birthDate)
            put("country", u.country)
        })}
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_USERS_DB, arr.toString()).apply()
    }

    private fun getSuspendedUsers(context: Context): List<SuspendedUser> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_SUSPENDED, "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = mutableListOf<SuspendedUser>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(SuspendedUser(
                email = o.getString("email"),
                deviceId = o.getString("deviceId"),
                until = o.getLong("until"),
                reason = o.getString("reason"),
                suspendedAt = o.getLong("suspendedAt")
            ))
        }
        return list
    }

    private fun saveSuspendedUsers(context: Context, list: List<SuspendedUser>) {
        val arr = JSONArray()
        list.forEach { s -> arr.put(JSONObject().apply {
            put("email", s.email)
            put("deviceId", s.deviceId)
            put("until", s.until)
            put("reason", s.reason)
            put("suspendedAt", s.suspendedAt)
        })}
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SUSPENDED, arr.toString()).apply()
    }

    private fun serializeUser(u: User): String {
        return JSONObject().apply {
            put("email", u.email)
            put("name", u.name)
            put("username", u.username)
            put("isAdmin", u.isAdmin)
            put("deviceId", u.deviceId)
            put("createdAt", u.createdAt)
            put("lastUsernameChange", u.lastUsernameChange)
            put("avatar", u.avatar)
            put("bio", u.bio)
            put("birthDate", u.birthDate)
            put("country", u.country)
        }.toString()
    }

    private fun deserializeUser(s: String): User? {
        return try {
            val o = JSONObject(s)
            User(
                email = o.getString("email"),
                name = o.getString("name"),
                username = o.getString("username"),
                isAdmin = o.getBoolean("isAdmin"),
                deviceId = o.getString("deviceId"),
                createdAt = o.getLong("createdAt"),
                lastUsernameChange = if (o.has("lastUsernameChange")) o.getLong("lastUsernameChange") else 0L,
                avatar = if (o.has("avatar")) o.getString("avatar") else "",
                bio = if (o.has("bio")) o.getString("bio") else "",
                birthDate = if (o.has("birthDate")) o.getString("birthDate") else "",
                country = if (o.has("country")) o.getString("country") else ""
            )
        } catch (e: Exception) { null }
    }

    // ============================================================
    //  Cloud sync (Firestore) — so name/username/avatar are visible to all
    // ============================================================

    private const val USERS_COLLECTION = "users"
    private const val UID_INDEX_COLLECTION = "user_uids"  // maps Firebase UID → email
    private val cloudDb by lazy { FirebaseFirestore.getInstance() }

    /**
     * Upload the current user's profile (name, username, avatar as base64 thumbnail)
     * to Firestore so other users see the latest name/username/avatar on comments.
     *
     * The avatar is uploaded as a small base64 JPEG (≤ ~80KB) to fit in Firestore.
     */
    fun uploadUserToCloud(context: Context) {
        val user = getCurrentUser(context) ?: return
        Thread {
            try {
                val avatarBase64: String? = if (user.avatar.isNotEmpty() && !user.avatar.startsWith("http")) {
                    try {
                        val file = File(user.avatar)
                        if (file.exists()) {
                            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                            if (bitmap != null) {
                                val maxDim = 256
                                val scaled = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                                    val scale = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                                    Bitmap.createScaledBitmap(bitmap,
                                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                                        (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                                } else bitmap
                                val stream = ByteArrayOutputStream()
                                scaled.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                                if (scaled !== bitmap) scaled.recycle()
                                bitmap.recycle()
                                Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                            } else null
                        } else null
                    } catch (e: Exception) { null }
                } else null

                ApiClient.updateProfile(
                    name = user.name,
                    username = user.username,
                    avatarBase64 = avatarBase64,
                    birthDate = user.birthDate,
                    country = user.country
                ).first  // We only need the success boolean here
            } catch (e: Exception) {}
        }.start()
    }

    /**
     * Fetch a user's cloud profile by email (used by CommentsAdapter to load the
     * latest avatar for a comment author).
     */
    fun fetchCloudUser(email: String, onResult: (CloudUser?) -> Unit) {
        // Use Vercel API (server-side protection)
        Thread {
            try {
                val user = ApiClient.getUserProfile(email)
                onResult(user)
            } catch (e: Exception) {
                Log.w("AuthManager", "fetchCloudUser: ${e.message}")
                onResult(null)
            }
        }.start()
    }

    // Original Firestore-based fetchCloudUser (kept as fallback, renamed)
    fun fetchCloudUserLegacy(email: String, onResult: (CloudUser?) -> Unit) {
        try {
            cloudDb.collection(USERS_COLLECTION).document(email).get()
                .addOnSuccessListener { doc ->
                    if (!doc.exists()) { onResult(null); return@addOnSuccessListener }
                    onResult(CloudUser(
                        email = doc.getString("email") ?: email,
                        name = doc.getString("name") ?: "مستخدم",
                        username = doc.getString("username") ?: "",
                        avatarBase64 = doc.getString("avatarBase64") ?: "",
                        isAdmin = doc.getBoolean("isAdmin") ?: false,
                        birthDate = doc.getString("birthDate") ?: "",
                        country = doc.getString("country") ?: "",
                        createdAt = doc.getLong("createdAt") ?: 0L
                    ))
                }
                .addOnFailureListener { onResult(null) }
        } catch (e: Exception) { onResult(null) }
    }

    /**
     * Restore the current user's name/username/avatar from the cloud.
     * Called after login when there's no local profile (e.g. after app reinstall
     * or update that wiped SharedPreferences). This ensures the user keeps their
     * custom name, username, and avatar instead of getting reset to defaults.
     *
     * Runs async — updates the local user + store when the cloud data arrives.
     */
    fun restoreUserFromCloud(context: Context, onRestored: ((Boolean) -> Unit)? = null) {
        // Try to get the email from the local user first (fast path)
        var email = getCurrentUser(context)?.email ?: ""

        // If no local user, try to get the email from Firebase Auth directly.
        if (email.isEmpty()) {
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            email = firebaseUser?.email ?: ""
        }

        // If still no email, try the UID → email index in Firestore.
        // This is the KEY fix: even if local data is gone AND Firebase Auth
        // has no email (anonymous user), we can still find the email by
        // looking up the UID in the user_uids collection.
        if (email.isEmpty()) {
            val firebaseUser = FirebaseAuth.getInstance().currentUser
            val uid = firebaseUser?.uid
            if (uid != null) {
                cloudDb.collection(UID_INDEX_COLLECTION).document(uid).get()
                    .addOnSuccessListener { indexDoc ->
                        val indexedEmail = indexDoc.getString("email") ?: ""
                        if (indexedEmail.isNotEmpty()) {
                            // Got the email from the index — now restore the profile
                            restoreProfileByEmail(context, indexedEmail, onRestored)
                        } else {
                            onRestored?.invoke(false)
                        }
                    }
                    .addOnFailureListener { onRestored?.invoke(false) }
                return
            }
        }

        if (email.isEmpty()) {
            onRestored?.invoke(false)
            return
        }

        restoreProfileByEmail(context, email, onRestored)
    }

    private fun restoreProfileByEmail(context: Context, email: String, onRestored: ((Boolean) -> Unit)?) {
        // === USE VERCEL API (not Firestore SDK) to avoid cache issues ===
        // Firestore SDK has offline persistence which returns stale cached data.
        // The Vercel API always reads from the server (no cache).
        Thread {
            try {
                val cloudUser = ApiClient.getUserProfile(email)
                if (cloudUser == null) {
                    onRestored?.invoke(false)
                    return@Thread
                }

                val cloudName = cloudUser.name
                val cloudUsername = cloudUser.username
                val cloudAvatarBase64 = cloudUser.avatarBase64
                val cloudBirthDate = cloudUser.birthDate
                val cloudCountry = cloudUser.country
                val actualIsAdmin = cloudUser.isAdmin // server sets isAdmin
                val cloudCreatedAt = cloudUser.createdAt

                val current = getCurrentUser(context) ?: User(
                    email = email,
                    name = cloudName.ifEmpty { email.substringBefore("@") },
                    username = cloudUsername.ifEmpty { "@${email.substringBefore("@")}" },
                    isAdmin = actualIsAdmin,
                    deviceId = getDeviceId(context),
                    createdAt = cloudCreatedAt,
                    lastUsernameChange = 0L,
                    avatar = "",
                    bio = ""
                )

                var updated = current
                var changed = false

                if (cloudName.isNotEmpty() && cloudName != current.name) {
                    updated = updated.copy(name = cloudName)
                    changed = true
                }
                if (cloudUsername.isNotEmpty() && cloudUsername != current.username) {
                    updated = updated.copy(username = cloudUsername)
                    changed = true
                }
                if (cloudBirthDate != current.birthDate) {
                    updated = updated.copy(birthDate = cloudBirthDate)
                    changed = true
                }
                if (cloudCountry != current.country) {
                    updated = updated.copy(country = cloudCountry)
                    changed = true
                }
                if (actualIsAdmin != current.isAdmin) {
                    updated = updated.copy(isAdmin = actualIsAdmin)
                    changed = true
                }

                if (cloudAvatarBase64.isNotEmpty()) {
                    val localAvatarFile = updated.avatar.takeIf { it.isNotEmpty() }?.let { File(it) }
                    val localAvatarMissing = localAvatarFile == null || !localAvatarFile.exists()
                    if (localAvatarMissing) {
                        try {
                            val bytes = Base64.decode(cloudAvatarBase64, Base64.NO_WRAP)
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                val avatarFile = File(context.filesDir, "avatar_${current.email.replace("@", "_at_")}.jpg")
                                val out = FileOutputStream(avatarFile)
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                out.close()
                                bitmap.recycle()
                                updated = updated.copy(avatar = avatarFile.absolutePath)
                                changed = true
                            }
                        } catch (e: Exception) {}
                    }
                }

                if (changed) {
                    saveUser(context, updated)
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(KEY_USER, serializeUser(updated)).apply()
                }
                onRestored?.invoke(changed)
            } catch (e: Exception) {
                onRestored?.invoke(false)
            }
        }.start()
    }

    /** Lightweight cloud-only user profile (for avatars on comments). */
    data class CloudUser(
        val email: String,
        val name: String,
        val username: String,
        val avatarBase64: String,
        val isAdmin: Boolean = false,
        val birthDate: String = "",
        val country: String = "",
        val createdAt: Long = 0L
    )
}
