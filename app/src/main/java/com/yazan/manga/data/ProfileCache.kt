package com.yazan.manga.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * In-memory + disk cache for user profiles (CloudUser).
 *
 * Purpose:
 *  - When opening a user's profile from a comment, the OLD code always
 *    fetched from the API (~1-3 seconds latency). This cache returns the
 *    last-known profile INSTANTLY, then refreshes in the background.
 *  - Also used by comment/reply adapters to fetch avatars without
 *    hitting the API on every scroll.
 *
 * Storage:
 *  - Memory: HashMap<email, CloudUser> — survives as long as the
 *    app process is alive.
 *  - Disk: SharedPreferences — survives process death.
 */
object ProfileCache {
    private const val PREFS_NAME = "profile_cache"
    private const val KEY_PREFIX = "profile_"
    private val gson = Gson()
    private val type = object : TypeToken<AuthManager.CloudUser>() {}.type

    // In-memory cache: email → CloudUser
    private val memoryCache = mutableMapOf<String, AuthManager.CloudUser>()

    /**
     * Save a profile to both memory + disk.
     */
    fun save(context: Context, email: String, user: AuthManager.CloudUser) {
        memoryCache[email] = user
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = gson.toJson(user, type)
            prefs.edit().putString(KEY_PREFIX + email, json).apply()
        } catch (e: Exception) {}
    }

    /**
     * Get cached profile. Tries memory first, falls back to disk.
     */
    fun get(context: Context, email: String): AuthManager.CloudUser? {
        memoryCache[email]?.let { return it }
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_PREFIX + email, null) ?: return null
            val user = gson.fromJson<AuthManager.CloudUser>(json, type)
            if (user != null) memoryCache[email] = user
            user
        } catch (e: Exception) {
            null
        }
    }
}
