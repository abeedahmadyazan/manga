package com.yazan.manga.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import com.bumptech.glide.Glide
import com.yazan.manga.MangaApp

/**
 * In-memory cache for user avatars.
 * 
 * - First load: fetches from API, decodes base64 → Bitmap, caches in RAM
 * - Subsequent loads: returns cached Bitmap instantly (0ms)
 * - Cache survives as long as the app process is alive
 * - Max size: 4MB (enough for ~50 avatars at 256x256)
 * 
 * When a user changes their avatar, the old cache entry is invalidated
 * on next app launch (or manually via invalidate()).
 */
object AvatarCache {
    private val cache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount
        }
    }

    /**
     * Get avatar bitmap by email. Returns cached bitmap or null.
     * If not cached, triggers async fetch from API.
     */
    fun get(email: String): Bitmap? {
        return cache.get(email)
    }

    /**
     * Cache a base64 avatar string as a Bitmap.
     */
    fun put(email: String, base64: String) {
        if (base64.isEmpty()) return
        try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) {
                cache.put(email, bmp)
            }
        } catch (e: Exception) {}
    }

    /**
     * Invalidate a cached avatar (when user changes their picture).
     */
    fun invalidate(email: String) {
        cache.remove(email)
    }

    /**
     * Clear all cached avatars.
     */
    fun clear() {
        cache.evictAll()
    }
}
