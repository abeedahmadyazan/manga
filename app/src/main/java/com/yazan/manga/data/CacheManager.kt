package com.yazan.manga.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * CacheManager — disk-based cache for manga lists, manga details, and
 * chapter pages. Cuts Firestore / network usage dramatically on repeat
 * visits and lets the user browse their last-viewed manga offline.
 *
 * Storage layout (app-internal, no permission):
 *   /data/data/com.yazan.manga/files/cache/
 *       manga_list_latest_1.json   (page 1 of latest tab)
 *       manga_list_popular_1.json  (page 1 of popular tab)
 *       manga_search_{query_hash}.json
 *       manga_details_{mangaId}.json
 *
 * TTL:
 *  - Lists/search: 1 hour (manga updates happen frequently)
 *  - Manga details: 24 hours (chapters list is more stable)
 *  - Chapters page URLs: never expire (they're only used to fetch images,
 *    and we want the user to be able to open the reader offline if the
 *    pages were cached by Glide)
 *
 * All cache files are also size-bounded: if the total cache exceeds 50 MB,
 * oldest files are evicted first.
 */
object CacheManager {

    private const val TAG = "CacheManager"
    private const val CACHE_DIR = "cache"
    private const val MAX_CACHE_BYTES = 50L * 1024 * 1024  // 50 MB

    private const val TTL_LIST_MS = 60 * 60 * 1000L          // 1 hour
    private const val TTL_DETAILS_MS = 24 * 60 * 60 * 1000L  // 24 hours

    private val gson = Gson()

    private fun cacheDir(context: Context): File =
        File(context.filesDir, CACHE_DIR).apply { if (!exists()) mkdirs() }

    private fun file(context: Context, key: String): File =
        File(cacheDir(context), "$key.json")

    /**
     * Clear ALL cached data. Called on app startup to wipe old MangaDex
     * cached items that would cause crashes when tapped (their IDs are
     * MangaDex UUIDs, not 3asq-* IDs).
     */
    fun clearAllCache(context: Context) {
        try {
            cacheDir(context).deleteRecursively()
            cacheDir(context).mkdirs()
            Log.d(TAG, "All cache cleared")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear cache: ${e.message}")
        }
    }

    // =============================================================
    //  Generic get/set with TTL
    // =============================================================

    private fun <T> readCached(context: Context, key: String, ttl: Long, clazz: Class<T>): T? {
        val f = file(context, key)
        if (!f.exists()) return null
        val age = System.currentTimeMillis() - f.lastModified()
        if (age > ttl) {
            f.delete()
            return null
        }
        return try {
            val json = f.readText()
            gson.fromJson(json, clazz)
        } catch (e: Exception) {
            Log.w(TAG, "Cache read failed for $key: ${e.message}")
            f.delete()
            null
        }
    }

    private fun <T> writeCached(context: Context, key: String, data: T) {
        try {
            val f = file(context, key)
            f.writeText(gson.toJson(data))
            enforceMaxSize(context)
        } catch (e: Exception) {
            Log.w(TAG, "Cache write failed for $key: ${e.message}")
        }
    }

    /** Evict oldest files until total cache size is under MAX_CACHE_BYTES. */
    private fun enforceMaxSize(context: Context) {
        val dir = cacheDir(context)
        val files = dir.listFiles()?.toMutableList() ?: return
        var totalSize = files.sumOf { it.length() }
        if (totalSize <= MAX_CACHE_BYTES) return
        // Sort by last-modified ascending (oldest first)
        files.sortBy { it.lastModified() }
        for (f in files) {
            if (totalSize <= MAX_CACHE_BYTES) break
            totalSize -= f.length()
            f.delete()
        }
    }

    // =============================================================
    //  Public API — Manga lists
    // =============================================================

    fun cacheMangaList(context: Context, tab: String, page: Int, items: List<MangaListItem>) {
        writeCached(context, "manga_list_${tab}_$page", items)
    }

    fun getCachedMangaList(context: Context, tab: String, page: Int): List<MangaListItem>? {
        val type = object : TypeToken<List<MangaListItem>>() {}.type
        val f = file(context, "manga_list_${tab}_$page")
        if (!f.exists()) return null
        val age = System.currentTimeMillis() - f.lastModified()
        if (age > TTL_LIST_MS) {
            f.delete()
            return null
        }
        return try {
            gson.fromJson(f.readText(), type)
        } catch (e: Exception) { null }
    }

    fun cacheSearchResults(context: Context, query: String, items: List<MangaListItem>) {
        val key = "manga_search_${query.hashCode()}"
        writeCached(context, key, items)
    }

    fun getCachedSearchResults(context: Context, query: String): List<MangaListItem>? {
        val key = "manga_search_${query.hashCode()}"
        val f = file(context, key)
        if (!f.exists()) return null
        val age = System.currentTimeMillis() - f.lastModified()
        if (age > TTL_LIST_MS) {
            f.delete()
            return null
        }
        return try {
            val type = object : TypeToken<List<MangaListItem>>() {}.type
            gson.fromJson(f.readText(), type)
        } catch (e: Exception) { null }
    }

    // =============================================================
    //  Public API — Manga details
    // =============================================================

    fun cacheMangaDetails(context: Context, mangaId: String, details: MangaDetails) {
        writeCached(context, "manga_details_$mangaId", details)
    }

    fun getCachedMangaDetails(context: Context, mangaId: String): MangaDetails? {
        return readCached(context, "manga_details_$mangaId", TTL_DETAILS_MS, MangaDetails::class.java)
    }

    // =============================================================
    //  Maintenance
    // =============================================================

    /** Clear the entire cache. Called from Settings → "Clear cache". */
    fun clearAll(context: Context) {
        cacheDir(context).listFiles()?.forEach { it.delete() }
    }

    /** Total cache size in bytes (for the Settings screen). */
    fun cacheSizeBytes(context: Context): Long {
        return cacheDir(context).listFiles()?.sumOf { it.length() } ?: 0
    }
}
