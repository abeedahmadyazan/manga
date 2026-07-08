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
 * - Admin ONLY for yznabyd@gmail.com
 * - Device fingerprint for security
 * - Banned devices check
 * - Multi-account prevention (one account per device)
 * - Unique username (changeable once per 30 days)
 * - Account suspension (temporary or permanent)
 */
object AuthManager {

    private const val ADMIN_EMAIL = "yznabyd@gmail.com"
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
        val bio: String
    )

    data class SuspendedUser(
        val email: String,
        val deviceId: String,
        val until: Long,
        val reason: String,
        val suspendedAt: Long
    )

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
     */
    fun processEmailAuth(context: Context, email: String, displayName: String): String? {
        val cleanEmail = email.lowercase().trim()

        if (isDeviceBanned(context)) {
            return "🚫 هذا الجهاز محظور بسبب مخالفة القوانين"
        }

        val (suspended, reason) = isUserSuspended(context, cleanEmail)
        if (suspended) return reason

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val linkedEmail = prefs.getString(KEY_LINKED_EMAIL, null)
        if (linkedEmail != null && linkedEmail != cleanEmail) {
            return "⚠️ هذا الجهاز مرتبط بحساب آخر ($linkedEmail)"
        }

        val deviceId = getDeviceId(context)
        val isAdmin = cleanEmail == ADMIN_EMAIL

        var user = getUserByEmail(context, cleanEmail)
        val wasNewUser = (user == null)
        if (user == null) {
            val uniqueUsername = generateUniqueUsername(context, displayName)
            user = User(
                email = cleanEmail,
                name = if (isAdmin) "يزان" else displayName,
                username = uniqueUsername,
                isAdmin = isAdmin,
                deviceId = deviceId,
                createdAt = System.currentTimeMillis(),
                lastUsernameChange = 0L,
                avatar = "",
                bio = ""
            )
            saveUser(context, user)
        }

        prefs.edit()
            .putString(KEY_USER, serializeUser(user))
            .putString(KEY_LINKED_EMAIL, cleanEmail)
            .apply()

        // Restore from cloud FIRST (async), then upload the (now-correct) local
        // profile. We can't just call them in sequence because both are async —
        // instead, restoreUserFromCloud calls uploadUserToCloud itself once the
        // cloud data is applied, so we never overwrite the cloud with stale data.
        restoreUserFromCloud(context) {
            // After restore, upload the merged local profile back to the cloud
            uploadUserToCloud(context)
        }

        return null
    }

    /**
     * Process Firebase Auth result after Google Sign-In.
     * Returns error message if failed, null if success.
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

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val linkedEmail = prefs.getString(KEY_LINKED_EMAIL, null)
        if (linkedEmail != null && linkedEmail != email) {
            return "⚠️ هذا الجهاز مرتبط بحساب آخر ($linkedEmail). لا يمكن إنشاء حساب جديد."
        }

        val deviceId = getDeviceId(context)
        val isAdmin = email == ADMIN_EMAIL

        var user = getUserByEmail(context, email)
        val wasNewUser = (user == null)
        if (user == null) {
            val baseName = displayName
            val uniqueUsername = generateUniqueUsername(context, baseName)
            user = User(
                email = email,
                name = if (isAdmin) "يزان" else baseName,
                username = uniqueUsername,
                isAdmin = isAdmin,
                deviceId = deviceId,
                createdAt = System.currentTimeMillis(),
                lastUsernameChange = 0L,
                avatar = account.photoUrl?.toString() ?: "",
                bio = ""
            )
            saveUser(context, user)
        }

        prefs.edit()
            .putString(KEY_USER, serializeUser(user))
            .putString(KEY_LINKED_EMAIL, email)
            .apply()

        // Restore from cloud FIRST (async), then upload the (now-correct) local
        // profile. restoreUserFromCloud calls uploadUserToCloud itself once the
        // cloud data is applied, so we never overwrite the cloud with stale data.
        restoreUserFromCloud(context) {
            uploadUserToCloud(context)
        }

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

            // All checks passed — save
            val updated = current.copy(username = clean, lastUsernameChange = now)
            saveUser(context, updated)
            prefs.edit().putString(KEY_USER, serializeUser(updated)).apply()
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

        // Admin badge is shown separately (the green pill), so we don't
        // add '(مشرف)' to the name itself.
        val finalName = clean

        val updated = current.copy(name = finalName)
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
            val updated = current.copy(avatar = savedPath)
            saveUser(context, updated)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_USER, serializeUser(updated)).apply()
            uploadUserToCloud(context)
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
        if (email == ADMIN_EMAIL) return "لا يمكن إيقاف حساب المشرف"

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
                bio = if (o.has("bio")) o.getString("bio") else ""
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
                bio = if (o.has("bio")) o.getString("bio") else ""
            )
        } catch (e: Exception) { null }
    }

    // ============================================================
    //  Cloud sync (Firestore) — so name/username/avatar are visible to all
    // ============================================================

    private const val USERS_COLLECTION = "users"
    private val cloudDb by lazy { FirebaseFirestore.getInstance() }

    /**
     * Upload the current user's profile (name, username, avatar as base64 thumbnail)
     * to Firestore so other users see the latest name/username/avatar on comments.
     *
     * The avatar is uploaded as a small base64 JPEG (≤ ~80KB) to fit in Firestore.
     */
    fun uploadUserToCloud(context: Context) {
        val user = getCurrentUser(context) ?: return
        try {
            // Read the local avatar file (if any) and encode it as base64.
            // Only local file paths are supported — HTTP URLs (e.g. from Google
            // Sign-In) can't be read directly and are skipped.
            val avatarBase64: String? = if (user.avatar.isNotEmpty() && !user.avatar.startsWith("http")) {
                try {
                    val file = File(user.avatar)
                    if (file.exists()) {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        if (bitmap != null) {
                            // Downscale to max 256px to keep the base64 small
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
                            val stream = ByteArrayOutputStream()
                            scaled.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                            if (scaled !== bitmap) scaled.recycle()
                            bitmap.recycle()
                            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                        } else null
                    } else null
                } catch (e: Exception) {
                    Log.w("AuthManager", "uploadUserToCloud: avatar encode failed", e)
                    null
                }
            } else null

            val data = hashMapOf(
                "email" to user.email,
                "name" to user.name,
                "username" to user.username,
                "isAdmin" to user.isAdmin,
                "avatarBase64" to (avatarBase64 ?: ""),
                "lastUpdated" to System.currentTimeMillis()
            )
            cloudDb.collection(USERS_COLLECTION).document(user.email).set(data)
                .addOnFailureListener { Log.w("AuthManager", "uploadUserToCloud failed", it) }
        } catch (e: Exception) {
            Log.w("AuthManager", "uploadUserToCloud exception", e)
        }
    }

    /**
     * Fetch a user's cloud profile by email (used by CommentsAdapter to load the
     * latest avatar for a comment author).
     */
    fun fetchCloudUser(email: String, onResult: (CloudUser?) -> Unit) {
        try {
            cloudDb.collection(USERS_COLLECTION).document(email).get()
                .addOnSuccessListener { doc ->
                    if (!doc.exists()) { onResult(null); return@addOnSuccessListener }
                    onResult(CloudUser(
                        email = doc.getString("email") ?: email,
                        name = doc.getString("name") ?: "مستخدم",
                        username = doc.getString("username") ?: "",
                        avatarBase64 = doc.getString("avatarBase64") ?: ""
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
        val current = getCurrentUser(context) ?: run {
            onRestored?.invoke(false)
            return
        }
        try {
            cloudDb.collection(USERS_COLLECTION).document(current.email).get()
                .addOnSuccessListener { doc ->
                    if (!doc.exists()) {
                        onRestored?.invoke(false)
                        return@addOnSuccessListener
                    }

                    val cloudName = doc.getString("name")
                    val cloudUsername = doc.getString("username")
                    val cloudAvatarBase64 = doc.getString("avatarBase64") ?: ""

                    var updated = current
                    var changed = false

                    // Always restore the name from the cloud (cloud is source of truth)
                    if (!cloudName.isNullOrEmpty() && cloudName != current.name) {
                        updated = updated.copy(name = cloudName)
                        changed = true
                    }

                    // Always restore the username from the cloud
                    if (!cloudUsername.isNullOrEmpty() && cloudUsername != current.username) {
                        updated = updated.copy(username = cloudUsername)
                        changed = true
                    }

                    // Restore avatar: decode base64 → save to internal storage → set path.
                    // We overwrite the local avatar if the cloud has one AND either:
                    //  - the local avatar path is empty, OR
                    //  - the local avatar file doesn't exist anymore (e.g. after reinstall)
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
                            } catch (e: Exception) {
                                Log.w("AuthManager", "restoreUserFromCloud: avatar decode failed", e)
                            }
                        }
                    }

                    // If anything changed, save locally + update the current-user cache
                    if (changed) {
                        saveUser(context, updated)
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putString(KEY_USER, serializeUser(updated)).apply()
                        Log.d("AuthManager", "restoreUserFromCloud: restored name/username/avatar")
                    }
                    onRestored?.invoke(changed)
                }
                .addOnFailureListener {
                    Log.w("AuthManager", "restoreUserFromCloud failed", it)
                    onRestored?.invoke(false)
                }
        } catch (e: Exception) {
            Log.w("AuthManager", "restoreUserFromCloud exception", e)
            onRestored?.invoke(false)
        }
    }

    /** Lightweight cloud-only user profile (for avatars on comments). */
    data class CloudUser(
        val email: String,
        val name: String,
        val username: String,
        val avatarBase64: String
    )
}
