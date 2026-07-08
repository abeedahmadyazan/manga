package com.yazan.manga.data

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * DownloadManager — handles downloading manga chapter pages to local storage
 * so the user can read them offline.
 *
 * Storage layout (app-internal, no permission needed on Android 10+):
 *   /data/data/com.yazan.manga/files/downloads/
 *       {mangaId}/
 *           {chapterNumber}/
 *               0.jpg
 *               1.jpg
 *               ...
 *               .complete  ← marker file written after all pages finish
 *
 * Because everything lives in app-internal storage (Context.getFilesDir()),
 * we never need WRITE_EXTERNAL_STORAGE permission on any Android version.
 * The OS cleans up if the user uninstalls the app.
 */
object DownloadManager {

    private const val TAG = "DownloadManager"
    private const val DOWNLOADS_DIR = "downloads"
    private const val COMPLETE_MARKER = ".complete"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // =============================================================
    //  Path helpers
    // =============================================================

    /** Root dir for all downloads. */
    private fun rootDir(context: Context): File =
        File(context.filesDir, DOWNLOADS_DIR).apply { if (!exists()) mkdirs() }

    /** Dir for one manga's chapters. */
    fun mangaDir(context: Context, mangaId: String): File =
        File(rootDir(context), sanitize(mangaId)).apply { if (!exists()) mkdirs() }

    /** Dir for one chapter's pages. */
    private fun chapterDir(context: Context, mangaId: String, chapterNumber: String): File =
        File(mangaDir(context, mangaId), sanitize(chapterNumber)).apply { if (!exists()) mkdirs() }

    /** Page file path. */
    private fun pageFile(context: Context, mangaId: String, chapterNumber: String, index: Int): File =
        File(chapterDir(context, mangaId, chapterNumber), "$index.jpg")

    /** Strip path-unsafe characters. */
    private fun sanitize(s: String): String =
        s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifEmpty { "unknown" }

    // =============================================================
    //  State queries
    // =============================================================

    /** Returns true if the chapter has been fully downloaded. */
    fun isChapterDownloaded(context: Context, mangaId: String, chapterNumber: String): Boolean {
        val dir = chapterDir(context, mangaId, chapterNumber)
        val marker = File(dir, COMPLETE_MARKER)
        return marker.exists()
    }

    /**
     * Returns the list of local page file paths for a downloaded chapter,
     * or empty list if the chapter isn't downloaded yet.
     */
    fun getDownloadedPages(context: Context, mangaId: String, chapterNumber: String): List<String> {
        val dir = chapterDir(context, mangaId, chapterNumber)
        if (!File(dir, COMPLETE_MARKER).exists()) return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
            ?.sortedBy { it.nameWithoutExtension.toIntOrNull() ?: 0 }
            ?: emptyList()
        return files.map { it.absolutePath }
    }

    /**
     * Returns a list of (chapterNumber, mangaId, mangaTitle?) for every
     * downloaded chapter in the system. Used by the Downloads screen.
     */
    fun listAllDownloads(context: Context): List<DownloadedChapter> {
        val result = mutableListOf<DownloadedChapter>()
        val mangaDirs = rootDir(context).listFiles { f -> f.isDirectory } ?: return emptyList()
        for (mangaDir in mangaDirs) {
            val mangaId = mangaDir.name
            val chapterDirs = mangaDir.listFiles { f -> f.isDirectory } ?: continue
            for (chapterDir in chapterDirs) {
                val marker = File(chapterDir, COMPLETE_MARKER)
                if (!marker.exists()) continue
                val pages = chapterDir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") }
                val pageCount = pages?.size ?: 0
                if (pageCount == 0) continue
                result.add(DownloadedChapter(
                    mangaId = mangaId,
                    chapterNumber = chapterDir.name,
                    pageCount = pageCount,
                    downloadedAt = chapterDir.lastModified()
                ))
            }
        }
        return result.sortedByDescending { it.downloadedAt }
    }

    /** Delete one chapter's download. */
    fun deleteChapter(context: Context, mangaId: String, chapterNumber: String): Boolean {
        val dir = chapterDir(context, mangaId, chapterNumber)
        return dir.deleteRecursively()
    }

    // =============================================================
    //  Download
    // =============================================================

    /**
     * Download all pages of one chapter. Calls [onProgress] with the index of
     * each page as it completes (0-based), and [onPageProgress] with bytes
     * written/total for the current page.
     *
     * Returns the number of pages actually downloaded. Throws on network
     * errors so the caller can surface them.
     */
    suspend fun downloadChapter(
        context: Context,
        mangaId: String,
        chapterNumber: String,
        pageUrls: List<String>,
        onProgress: ((downloaded: Int, total: Int) -> Unit)? = null
    ): Int {
        if (pageUrls.isEmpty()) return 0
        val dir = chapterDir(context, mangaId, chapterNumber)

        // Remove any previous incomplete marker
        File(dir, COMPLETE_MARKER).delete()

        var downloaded = 0
        for ((index, url) in pageUrls.withIndex()) {
            val target = pageFile(context, mangaId, chapterNumber, index)
            if (target.exists() && target.length() > 0) {
                // Already downloaded (resume support) — skip
                downloaded++
                onProgress?.invoke(downloaded, pageUrls.size)
                continue
            }
            try {
                fetchImage(url, target)
                downloaded++
                onProgress?.invoke(downloaded, pageUrls.size)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to download page $index: ${e.message}")
                throw e
            }
        }

        // All pages done — write the marker file
        if (downloaded == pageUrls.size) {
            File(dir, COMPLETE_MARKER).writeText("ok")
        }
        return downloaded
    }

    /** Fetch one image URL into target file. */
    private fun fetchImage(url: String, target: File) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Accept", "image/*,*/*")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
            val body = resp.body ?: throw Exception("Empty body")
            val bytes = body.bytes()
            if (bytes.isEmpty()) throw Exception("Empty image")
            target.writeBytes(bytes)
        }
    }

    // =============================================================
    //  Model
    // =============================================================

    data class DownloadedChapter(
        val mangaId: String,
        val chapterNumber: String,
        val pageCount: Int,
        val downloadedAt: Long
    )
}
