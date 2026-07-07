package com.yazan.manga.data

/**
 * Repository that wraps API calls with simple error handling.
 * Returns Result<T> so the UI can handle success/failure cleanly.
 */
class MangaRepository {

    private val api = MangaApiClient.api

    suspend fun getLatestManga(page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val response = api.getMangaList(type = "latest", page = page)
            Result.success(response.items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPopularManga(page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val response = api.getMangaList(type = "top", page = page)
            Result.success(response.items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchManga(query: String, page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val response = api.getMangaList(type = "search", query = query, page = page)
            Result.success(response.items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMangaDetails(id: String): Result<MangaDetails> {
        return try {
            val details = api.getMangaDetails(id)
            Result.success(details)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getChapterPages(chapter: MangaChapter): Result<List<ChapterPage>> {
        return try {
            val response = api.getChapterPages(chapter)
            Result.success(response.pages)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
