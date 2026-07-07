package com.yazan.manga.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Retrofit API interface for the manga website's API.
 * Base URL: https://manga-app-yazan.netlify.app
 */
interface MangaApiService {

    // GET /api/manga/list?type=latest&page=1
    @GET("api/manga/list")
    suspend fun getMangaList(
        @Query("type") type: String = "latest",
        @Query("page") page: Int = 1,
        @Query("query") query: String = ""
    ): MangaListResponse

    // GET /api/manga/details?id=xxx
    @GET("api/manga/details")
    suspend fun getMangaDetails(
        @Query("id") id: String
    ): MangaDetails

    // POST /api/manga/chapter/pages  (body = chapter JSON)
    @POST("api/manga/chapter/pages")
    suspend fun getChapterPages(
        @Body chapter: MangaChapter
    ): ChapterPagesResponse
}
