package com.yazan.manga.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

/**
 * In-memory + disk cache for comments per context (chapter/manga).
 *
 * Purpose:
 *  - When the user returns to a comments screen from the background,
 *    the activity may have been recreated (process death) with an empty
 *    list. This cache restores the last-seen comments INSTANTLY so the
 *    user doesn't see an empty list while waiting for the API to respond.
 *  - The cache is short-lived (last-seen only). It's always replaced by
 *    fresh API data on the next poll.
 *
 * Storage:
 *  - Memory: HashMap<contextId, List<Comment>> — survives as long as
 *    the app process is alive (covers most background→foreground cases).
 *  - Disk: SharedPreferences — survives process death. Keyed by contextId.
 */
object CommentsCache {
    private const val PREFS_NAME = "comments_cache"
    private const val KEY_PREFIX = "comments_"
    private val gson = Gson()
    private val type = object : TypeToken<List<CloudCommentsManager.Comment>>() {}.type

    // In-memory cache: contextId → comments
    private val memoryCache = mutableMapOf<String, List<CloudCommentsManager.Comment>>()

    /**
     * Save comments for a context to both memory + disk.
     */
    fun save(context: Context, contextId: String, comments: List<CloudCommentsManager.Comment>) {
        // Memory
        memoryCache[contextId] = comments
        // Disk (async via apply)
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = gson.toJson(comments, type)
            prefs.edit().putString(KEY_PREFIX + contextId, json).apply()
        } catch (e: Exception) {}
    }

    /**
     * Get cached comments for a context. Tries memory first (instant),
     * falls back to disk.
     */
    fun get(context: Context, contextId: String): List<CloudCommentsManager.Comment> {
        // Memory
        memoryCache[contextId]?.let { return it }
        // Disk
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_PREFIX + contextId, null) ?: return emptyList()
            val list = gson.fromJson<List<CloudCommentsManager.Comment>>(json, type) ?: emptyList()
            memoryCache[contextId] = list
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Clear cached comments for a context (e.g. when the user leaves).
     */
    fun clear(context: Context, contextId: String) {
        memoryCache.remove(contextId)
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_PREFIX + contextId).apply()
        } catch (e: Exception) {}
    }
}
