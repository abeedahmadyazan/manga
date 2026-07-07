package com.yazan.manga.data

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * MangaDex API interface (Arabic + English manga, including One Piece).
 * Base URL: https://api.mangadex.org
 */
interface MangaApiService {

    // Latest Arabic manga — newest chapters uploaded first
    @GET("manga")
    suspend fun getLatestArabic(
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("availableTranslatedLanguage[]") lang: String = "ar",
        @Query("order[latestUploadedChapter]") order: String = "desc",
        @Query("includes[]") includes: String = "cover_art",
        @Query("contentRating[]") safe: String = "safe",
        @Query("contentRating[]") suggestive: String = "suggestive"
    ): MangaDexListResponse

    // Latest English manga — newest chapters uploaded first
    @GET("manga")
    suspend fun getLatestEnglish(
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("availableTranslatedLanguage[]") lang: String = "en",
        @Query("order[latestUploadedChapter]") order: String = "desc",
        @Query("includes[]") includes: String = "cover_art",
        @Query("contentRating[]") safe: String = "safe",
        @Query("contentRating[]") suggestive: String = "suggestive"
    ): MangaDexListResponse

    // Popular Arabic manga — most followed first
    @GET("manga")
    suspend fun getPopularArabic(
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("availableTranslatedLanguage[]") lang: String = "ar",
        @Query("order[followedCount]") order: String = "desc",
        @Query("includes[]") includes: String = "cover_art",
        @Query("contentRating[]") safe: String = "safe",
        @Query("contentRating[]") suggestive: String = "suggestive"
    ): MangaDexListResponse

    // Search Arabic manga by title
    @GET("manga")
    suspend fun searchArabic(
        @Query("title") title: String,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("availableTranslatedLanguage[]") lang: String = "ar",
        @Query("order[relevance]") order: String = "desc",
        @Query("includes[]") includes: String = "cover_art",
        @Query("contentRating[]") safe: String = "safe",
        @Query("contentRating[]") suggestive: String = "suggestive"
    ): MangaDexListResponse

    // Manga details with author + cover
    @GET("manga/{id}")
    suspend fun getMangaDetails(
        @Path("id") id: String,
        @Query("includes[]") author: String = "author",
        @Query("includes[]") artist: String = "artist",
        @Query("includes[]") cover: String = "cover_art"
    ): MangaDexMangaResponse

    // Manga chapters (Arabic feed)
    @GET("manga/{id}/feed")
    suspend fun getMangaChapters(
        @Path("id") id: String,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0,
        @Query("translatedLanguage[]") lang: String = "ar",
        @Query("order[chapter]") order: String = "desc",
        @Query("contentRating[]") safe: String = "safe",
        @Query("contentRating[]") suggestive: String = "suggestive"
    ): MangaDexChaptersResponse

    // Chapter pages (at-home server)
    @GET("at-home/server/{chapterId}")
    suspend fun getChapterPages(
        @Path("chapterId") chapterId: String
    ): MangaDexChapterPagesResponse
}
