package com.yazan.manga.data

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * MangaHere — English manga source.
 *
 * Why MangaHere:
 *  - No Cloudflare (unlike 3asq.pro for old chapters)
 *  - Chapter listing works cleanly (279 chapters for JJK)
 *  - URL scheme:
 *      Manga page:   https://www.mangahere.cc/manga/{slug}/
 *      Chapter:      https://www.mangahere.cc/manga/{slug}/c{num:03d}/1.html
 *                    (chapters are paginated: page 1, page 2, ... per chapter)
 *  - Search:        https://www.mangahere.cc/search.php?keyword={query}
 *
 * Limitations:
 *  - English only
 *  - Chapter pages are loaded via JS — the HTML returned for /c001/1.html
 *    does NOT contain direct image URLs (they're injected by JS at runtime).
 *    We use MangaHere only for chapter LISTING, then fall back to MangaPill
 *    for the actual page images.
 */
object MangaHereClient {

    private const val BASE_URL = "https://www.mangahere.cc"
    private const val TAG = "MangaHere"

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
     * Find the MangaHere slug for a manga by title. Returns the slug
     * (e.g. "jujutsu_kaisen") or null if no match.
     */
    fun findMangaSlug(query: String): String? {
        if (query.isBlank()) return null
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "+")
            val req = Request.Builder()
                .url("$BASE_URL/search.php?keyword=$encoded")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string() ?: return null
                // Results look like: <a href="/manga/jujutsu_kaisen/" ...>
                val pattern = Pattern.compile("\"/manga/([a-z0-9_]+)/\"")
                val matcher = pattern.matcher(html)
                if (matcher.find()) matcher.group(1) else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "findMangaSlug($query) failed: ${e.message}")
            null
        }
    }

    /**
     * Fetch the list of chapters for a MangaHere manga slug.
     * Returns a list of (number, urlPath) pairs.
     *  - number: e.g. "001" or "271.5"
     *  - urlPath: e.g. "/manga/jujutsu_kaisen/c001/1.html"
     */
    fun fetchChapterList(slug: String): List<Pair<String, String>> {
        if (slug.isBlank()) return emptyList()
        return try {
            val req = Request.Builder()
                .url("$BASE_URL/manga/$slug/")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val html = resp.body?.string() ?: return emptyList()
                // Pattern: href="/manga/{slug}/c{num}/1.html" title="...">
                val pattern = Pattern.compile(
                    "href=\"(/manga/[a-z0-9_]+/c(\\d+(?:\\.\\d+)?)/1\\.html)\"\\s+title=\"([^\"]*)\""
                )
                val matcher = pattern.matcher(html)
                val result = mutableListOf<Pair<String, String>>()
                val seen = mutableSetOf<String>()
                while (matcher.find()) {
                    val urlPath = matcher.group(1) ?: continue
                    val num = matcher.group(2) ?: continue
                    // Normalize "001" → "1", but keep "271.5" as-is
                    val normalizedNum = num.toIntOrNull()?.toString() ?: num
                    if (seen.add(normalizedNum)) {
                        result.add(normalizedNum to urlPath)
                    }
                }
                // Sort by chapter number descending (newest first)
                result.sortedByDescending { it.first.toFloatOrNull() ?: 0f }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchChapterList($slug) failed: ${e.message}")
            emptyList()
        }
    }
}
