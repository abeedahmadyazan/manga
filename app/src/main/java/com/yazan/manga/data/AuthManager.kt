package com.yazan.manga.data

import android.content.Context
import android.provider.Settings
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
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
        if (user == null) {
            val uniqueUsername = generateUniqueUsername(context, displayName)
            user = User(
                email = cleanEmail,
                name = if (isAdmin) "يزان (مشرف)" else displayName,
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
        if (user == null) {
            val baseName = displayName
            val uniqueUsername = generateUniqueUsername(context, baseName)
            user = User(
                email = email,
                name = if (isAdmin) "يزان (مشرف)" else baseName,
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

    fun changeUsername(context: Context, newUsername: String): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = getCurrentUser(context) ?: return "يجب تسجيل الدخول"

        val clean = newUsername.trim()
        if (!clean.startsWith("@") || clean.length < 3 || clean.length > 20) {
            return "اسم المستخدم يجب أن يبدأ بـ @ ويكون 3-20 حرف"
        }
        if (!clean.matches(Regex("^@[a-zA-Z0-9_]+$"))) {
            return "اسم المستخدم يجب أن يحتوي على أحرف إنجليزية وأرقام فقط"
        }

        if (!isUsernameAvailable(context, clean, current.email)) {
            return "اسم المستخدم محجوز، جرب اسماً آخر"
        }

        val now = System.currentTimeMillis()
        if (current.lastUsernameChange > 0 && (now - current.lastUsernameChange) < USERNAME_COOLDOWN_MS) {
            val daysLeft = ((USERNAME_COOLDOWN_MS - (now - current.lastUsernameChange)) / (24 * 60 * 60 * 1000)).toInt() + 1
            return "يمكنك تغيير اسم المستخدم مرة كل 30 يوم. باقي $daysLeft يوم"
        }

        val updated = current.copy(username = clean, lastUsernameChange = now)
        saveUser(context, updated)
        prefs.edit().putString(KEY_USER, serializeUser(updated)).apply()
        return null
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
}
