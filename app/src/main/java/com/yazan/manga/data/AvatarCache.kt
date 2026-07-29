package com.yazan.manga.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache

/**
 * In-memory cache for user avatars with change detection.
 *
 * - First load: fetches from API, decodes base64 → Bitmap, caches in RAM
 * - Subsequent loads: returns cached Bitmap instantly (0ms)
 * - **Change detection**: stores a hash of the base64 string so we can
 *   detect when the user changed their avatar and update the cache.
 *   This fixes the "old avatar showing" bug where the cache would
 *   return a stale bitmap even after the user uploaded a new picture.
 *
 * Cache survives as long as the app process is alive.
 * Max size: 4MB (enough for ~50 avatars at 256x256).
 */
object AvatarCache {
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    /**
     * Hash of the base64 string for each email, used to detect when
     * the avatar has changed. Maps email → base64.hashCode().
     */
    private val hashCache = mutableMapOf<String, Int>()

    /**
     * Get avatar bitmap by email. Returns cached bitmap or null.
     * Does NOT trigger a fetch — caller is responsible for fetching.
     */
    fun get(email: String): Bitmap? {
        return cache.get(email)
    }

    /**
     * Cache a base64 avatar string as a Bitmap.
     * Only updates if the base64 has changed (detected via hashCode).
     * Returns true if the cache was actually updated.
     */
    fun put(email: String, base64: String): Boolean {
        if (base64.isEmpty()) return false
        val newHash = base64.hashCode()
        // Skip if we already have this exact avatar cached
        if (hashCache[email] == newHash && cache.get(email) != null) return false
        try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                cache.put(email, bmp)
                hashCache[email] = newHash
                return true
            }
        } catch (e: Exception) {}
        return false
    }

    /**
     * Check if the given base64 differs from what's cached.
     * Returns true if the avatar has changed (or was never cached).
     */
    fun hasChanged(email: String, base64: String): Boolean {
        if (base64.isEmpty()) return false
        return hashCache[email] != base64.hashCode()
    }

    /**
     * Invalidate a cached avatar (when user changes their picture).
     */
    fun invalidate(email: String) {
        cache.remove(email)
        hashCache.remove(email)
    }

    /**
     * Clear all cached avatars.
     */
    fun clear() {
        cache.evictAll()
        hashCache.clear()
    }
}
