package com.yazan.manga.data

/**
 * MangaRepository — 3asq-only data layer for the native Android app.
 *
 * Every public method runs the network call on a background thread (caller
 * is responsible for using Dispatchers.IO). All sources are Arabic (3asq).
 */
class MangaRepository {

    /**
     * Latest updated Arabic manga on 3asq (homepage listing).
     */
    suspend fun getLatestArabic(page: Int = 1): Result<List<MangaListItem>> {
        return runCatching { AsqClient.fetchLatest(page) }
    }

    /**
     * Popular Arabic manga — uses 3asq's homepage (slider + listing).
     */
    suspend fun getPopularArabic(page: Int = 1): Result<List<MangaListItem>> {
        return runCatching { AsqClient.fetchPopular(page) }
    }

    /**
     * Search 3asq by title.
     */
    suspend fun searchManga(query: String): Result<List<MangaListItem>> {
        return runCatching { AsqClient.search(query) }
    }

    /**
     * Full manga details + chapters list (1..latest) from 3asq.
     */
    suspend fun getMangaDetails(slug: String): Result<MangaDetails> {
        return try {
            val details = AsqClient.fetchMangaDetails(slug)
                ?: return Result.failure(Exception("فشل تحميل تفاصيل المانجا"))
            Result.success(details)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Page images for a chapter from 3asq.
     */
    suspend fun getChapterPages(slug: String, chapter: String): Result<List<ChapterPage>> {
        return runCatching { AsqClient.fetchChapterPages(slug, chapter) }
    }

    /**
     * Convenience overload that accepts a MangaChapter — extracts slug & number
     * from its ID ("3asq-{slug}-{number}") or externalUrl.
     */
    suspend fun getChapterPages(chapter: MangaChapter): Result<List<ChapterPage>> {
        // Try externalUrl first (always populated for 3asq)
        chapter.externalUrl?.let { url ->
            val match = Regex("/manga/([\\w-]+)/([^/]+)/?/?$").find(url)
            if (match != null) {
                val slug = match.groupValues[1]
                val num = match.groupValues[2]
                return getChapterPages(slug, num)
            }
        }
        // Fall back to parsing the chapter ID
        val parts = chapter.id.split("-")
        if (parts.size >= 3 && parts.first() == "3asq") {
            val number = parts.last()
            val slug = parts.drop(1).dropLast(1).joinToString("-")
            return getChapterPages(slug, number)
        }
        // Last resort: treat the chapter number as the URL suffix
        return getChapterPages(chapter.id.removePrefix("3asq-"), chapter.number)
    }
}
