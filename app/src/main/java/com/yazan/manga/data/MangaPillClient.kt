package com.yazan.manga.data

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * MangaPill — English manga source used as a fallback when 3asq and MangaDex
 * both fail to provide chapter pages.
 *
 * Why MangaPill:
 *  - No Cloudflare protection (3asq.online returns "Just a moment..." for old chapters)
 *  - Direct <img src="..."> URLs in chapter HTML (no lazy-load tricks)
 *  - Clean URL scheme:
 *      Manga page:   https://mangapill.com/manga/{id}/{slug}
 *      Chapter:      https://mangapill.com/chapters/{id}-{chapterId}/{slug}-chapter-{num}
 *  - Search:        https://mangapill.com/search?q={query}
 *
 * Limitations:
 *  - English only (no Arabic scanlations). We use this only when no Arabic
 *    source has the chapter, so English is better than nothing.
 */
object MangaPillClient {

    private const val BASE_URL = "https://mangapill.com"
    private const val TAG = "MangaPill"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    // =============================================================
    //  Public API
    // =============================================================

    /**
     * Search MangaPill by title (English). Returns the manga's id (the numeric
     * part of its URL) if a result is found, otherwise null.
     */
    fun findMangaId(query: String): String? {
        if (query.isBlank()) return null
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder()
                .url("$BASE_URL/search?q=$encoded")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string() ?: return null
                // First /manga/{id}/{slug} match
                val pattern = Pattern.compile("\"/manga/(\\d+)/[^\"]+\"")
                val matcher = pattern.matcher(html)
                if (matcher.find()) matcher.group(1) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "findMangaId($query) failed: ${e.message}")
            null
        }
    }

    /**
     * Fetch the list of chapters for a MangaPill manga id.
     * Returns a list of (number, urlPath) pairs — number can be like "271" or "271.5".
     */
    fun fetchChapterList(mangaId: String): List<Pair<String, String>> {
        if (mangaId.isBlank()) return emptyList()
        return try {
            // We don't know the slug, but MangaPill accepts /manga/{id}/anything
            // and redirects to the canonical URL. Listing works without slug.
            val req = Request.Builder()
                .url("$BASE_URL/manga/$mangaId/_")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val html = resp.body?.string() ?: return emptyList()
                // Each chapter: href="/chapters/{id}-{chapterId}/{slug}-chapter-{num}"
                // Capture: group 1 = full path "/chapters/...chapter-{num}"
                //          group 2 = chapter number
                val pattern = Pattern.compile("\"(/chapters/\\d+-\\d+/[^\"]*?chapter-[0-9.]+)\"")
                val matcher = pattern.matcher(html)
                val result = mutableListOf<Pair<String, String>>()
                val seen = mutableSetOf<String>()
                while (matcher.find()) {
                    val urlPath = matcher.group(1)
                    val num = matcher.group(2) ?: continue
                    // Extract just the number from the URL suffix
                    val numFromUrl = urlPath.substringAfterLast("chapter-")
                    if (seen.add(numFromUrl)) {
                        result.add(numFromUrl to urlPath)
                    }
                }
                // Sort by chapter number descending (newest first)
                result.sortedByDescending { it.first.toFloatOrNull() ?: 0f }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchChapterList($mangaId) failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch the page image URLs for a given chapter URL path (e.g.
     * "/chapters/2085-10001000/jujutsu-kaisen-chapter-1").
     */
    fun fetchChapterPages(chapterUrlPath: String): List<String> {
        if (chapterUrlPath.isBlank()) return emptyList()
        return try {
            val req = Request.Builder()
                .url("$BASE_URL$chapterUrlPath")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val html = resp.body?.string() ?: return emptyList()
                // Pages: <img data-src="https://cdn.../file/mangap/..." src="...">
                // We match data-src since src is sometimes a placeholder.
                val pattern = Pattern.compile("data-src=\"(https://[^\"]+\\.(?:jpg|jpeg|png|webp))\"")
                val matcher = pattern.matcher(html)
                val result = mutableListOf<String>()
                val seen = mutableSetOf<String>()
                while (matcher.find()) {
                    val url = matcher.group(1)
                    if (seen.add(url)) result.add(url)
                }
                // Fallback to src= if data-src yielded nothing
                if (result.isEmpty()) {
                    val srcPattern = Pattern.compile("src=\"(https://cdn\\.[^\"]+\\.(?:jpg|jpeg|png|webp))\"")
                    val srcMatcher = srcPattern.matcher(html)
                    while (srcMatcher.find()) {
                        val url = srcMatcher.group(1)
                        if (seen.add(url)) result.add(url)
                    }
                }
                result
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchChapterPages($chapterUrlPath) failed: ${e.message}")
            emptyList()
        }
    }
}
