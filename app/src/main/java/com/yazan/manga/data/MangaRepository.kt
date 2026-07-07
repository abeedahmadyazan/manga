package com.yazan.manga.data

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class MangaRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val TAG = "MangaRepository"

    // ===== MangaDex API for listing =====
    suspend fun getLatestManga(page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val offset = (page - 1) * 20
            val url = "https://api.mangadex.org/manga?limit=20&offset=$offset" +
                "&availableTranslatedLanguage[]=ar" +
                "&order[latestUploadedChapter]=desc" +
                "&includes[]=cover_art" +
                "&contentRating[]=safe&contentRating[]=suggestive"
            val items = fetchMangaDexList(url)
            Result.success(items)
        } catch (e: Exception) {
            Log.e(TAG, "getLatestManga error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getPopularManga(page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val offset = (page - 1) * 20
            val url = "https://api.mangadex.org/manga?limit=20&offset=$offset" +
                "&availableTranslatedLanguage[]=ar" +
                "&order[followedCount]=desc" +
                "&includes[]=cover_art" +
                "&contentRating[]=safe&contentRating[]=suggestive"
            val items = fetchMangaDexList(url)
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getArabicManga(page: Int = 1): Result<List<MangaListItem>> {
        return getLatestManga(page)
    }

    suspend fun getEnglishManga(page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val offset = (page - 1) * 20
            val url = "https://api.mangadex.org/manga?limit=20&offset=$offset" +
                "&availableTranslatedLanguage[]=en" +
                "&order[latestUploadedChapter]=desc" +
                "&includes[]=cover_art" +
                "&contentRating[]=safe&contentRating[]=suggestive"
            val items = fetchMangaDexList(url)
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchManga(query: String, page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val offset = (page - 1) * 20
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://api.mangadex.org/manga?title=$encoded&limit=20&offset=$offset" +
                "&availableTranslatedLanguage[]=ar" +
                "&order[relevance]=desc" +
                "&includes[]=cover_art" +
                "&contentRating[]=safe&contentRating[]=suggestive"
            val items = fetchMangaDexList(url)
            Result.success(items)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchMangaDexList(url: String): List<MangaListItem> {
        val req = Request.Builder().url(url)
            .header("User-Agent", "MangaApp/1.0 (Android)")
            .header("Accept", "application/json")
            .build()
        
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val json = JsonParser.parseString(body).asJsonObject
            val data = json.getAsJsonArray("data") ?: return emptyList()
            
            val items = mutableListOf<MangaListItem>()
            for (i in 0 until data.size()) {
                val m = data[i].asJsonObject
                val id = m.get("id").asString
                val attrs = m.getAsJsonObject("attributes")
                val titleObj = attrs.getAsJsonObject("title")
                var title = titleObj?.get("ar")?.asString
                    ?: titleObj?.get("en")?.asString
                    ?: titleObj?.entrySet()?.firstOrNull()?.value?.asString
                    ?: "بدون عنوان"
                
                var cover = ""
                val rels = m.getAsJsonArray("relationships")
                if (rels != null) {
                    for (j in 0 until rels.size()) {
                        val rel = rels[j].asJsonObject
                        if (rel.get("type").asString == "cover_art") {
                            val relAttrs = rel.getAsJsonObject("attributes")
                            val fileName = relAttrs?.get("fileName")?.asString
                            if (fileName != null) {
                                cover = "https://uploads.mangadex.org/covers/$id/$fileName.256.jpg"
                            }
                            break
                        }
                    }
                }
                
                items.add(MangaListItem(
                    id = id,
                    title = title,
                    cover = cover,
                    source = "mangadex",
                    status = attrs.get("status")?.asString ?: "ongoing"
                ))
            }
            return items
        }
    }

    // ===== Manga Details + Chapters =====
    suspend fun getMangaDetails(id: String): Result<MangaDetails> {
        return try {
            // Get manga details from MangaDex
            val detailsUrl = "https://api.mangadex.org/manga/$id?includes[]=cover_art&includes[]=author"
            val req = Request.Builder().url(detailsUrl)
                .header("User-Agent", "MangaApp/1.0 (Android)")
                .header("Accept", "application/json")
                .build()
            
            var title = "بدون عنوان"
            var cover = ""
            var author = ""
            var description = ""
            var status = "ongoing"
            var genres = listOf<String>()
            
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    val json = JsonParser.parseString(body).asJsonObject
                    val data = json.getAsJsonObject("data")
                    val attrs = data?.getAsJsonObject("attributes")
                    
                    val titleObj = attrs?.getAsJsonObject("title")
                    title = titleObj?.get("ar")?.asString
                        ?: titleObj?.get("en")?.asString
                        ?: titleObj?.entrySet()?.firstOrNull()?.value?.asString
                        ?: "بدون عنوان"
                    
                    val descObj = attrs?.getAsJsonObject("description")
                    description = descObj?.get("ar")?.asString
                        ?: descObj?.get("en")?.asString
                        ?: ""
                    
                    status = attrs?.get("status")?.asString ?: "ongoing"
                    
                    val rels = data?.getAsJsonArray("relationships")
                    if (rels != null) {
                        for (i in 0 until rels.size()) {
                            val rel = rels[i].asJsonObject
                            when (rel.get("type").asString) {
                                "cover_art" -> {
                                    val relAttrs = rel.getAsJsonObject("attributes")
                                    val fileName = relAttrs?.get("fileName")?.asString
                                    if (fileName != null) {
                                        cover = "https://uploads.mangadex.org/covers/$id/$fileName.512.jpg"
                                    }
                                }
                                "author" -> {
                                    val relAttrs = rel.getAsJsonObject("attributes")
                                    author = relAttrs?.get("name")?.asString ?: ""
                                }
                            }
                        }
                    }
                    
                    // Genres
                    val tags = attrs?.getAsJsonArray("tags")
                    if (tags != null) {
                        val genreList = mutableListOf<String>()
                        for (i in 0 until tags.size()) {
                            val tag = tags[i].asJsonObject
                            val tagAttrs = tag.getAsJsonObject("attributes")
                            val nameObj = tagAttrs?.getAsJsonObject("name")
                            val name = nameObj?.get("en")?.asString
                            if (name != null) genreList.add(name)
                        }
                        genres = genreList
                    }
                }
            }
            
            // Get chapters from MangaDex (ALL languages, prefer Arabic)
            val chapters = getMangaDexChapters(id)
            
            Result.success(MangaDetails(
                id = id,
                title = title,
                cover = cover,
                description = description,
                author = author,
                artist = author,
                status = status,
                genres = genres,
                chapters = chapters,
                source = "mangadex",
                latestChapter = null
            ))
        } catch (e: Exception) {
            Log.e(TAG, "getMangaDetails error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun getMangaDetailsAllLanguages(id: String): Result<MangaDetails> {
        return getMangaDetails(id)
    }

    private fun getMangaDexChapters(id: String): List<MangaChapter> {
        val allChapters = mutableListOf<Pair<String, Map<String, Any>>>()
        var offset = 0
        val limit = 100
        var total = Int.MAX_VALUE
        
        while (offset < total && offset < 5000) {
            val url = "https://api.mangadex.org/manga/$id/feed?limit=$limit&offset=$offset" +
                "&order[chapter]=desc&contentRating[]=safe&contentRating[]=suggestive"
            val req = Request.Builder().url(url)
                .header("User-Agent", "MangaApp/1.0 (Android)")
                .header("Accept", "application/json")
                .build()
            
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) break
                    val body = resp.body?.string() ?: break
                    val json = JsonParser.parseString(body).asJsonObject
                    val data = json.getAsJsonArray("data") ?: break
                    total = json.get("total")?.asInt ?: break
                    
                    for (i in 0 until data.size()) {
                        val ch = data[i].asJsonObject
                        val chId = ch.get("id").asString
                        val attrs = ch.getAsJsonObject("attributes")
                        val num = attrs.get("chapter")?.asString ?: continue
                        val lang = attrs.get("translatedLanguage")?.asString ?: ""
                        val title = attrs.get("title")?.asString ?: ""
                        val publishAt = attrs.get("publishAt")?.asString ?: ""
                        
                        allChapters.add(chId to mapOf(
                            "num" to num,
                            "lang" to lang,
                            "title" to title,
                            "date" to publishAt
                        ))
                    }
                    
                    if (data.size() < limit) break
                }
            } catch (e: Exception) {
                break
            }
            offset += limit
        }
        
        // Dedupe by chapter number, prefer Arabic > English > any
        return allChapters
            .groupBy { (it.second["num"] as String) }
            .mapValues { (_, list) ->
                list.firstOrNull { it.second["lang"] == "ar" }
                    ?: list.firstOrNull { it.second["lang"] == "en" }
                    ?: list.first()
            }
            .values
            .sortedByDescending { (it.second["num"] as String).toFloatOrNull() ?: 0f }
            .map { (chId, info) ->
                MangaChapter(
                    id = chId,
                    number = info["num"] as String,
                    title = info["title"] as String,
                    date = formatDate(info["date"] as String),
                    source = "mangadex"
                )
            }
    }

    // ===== Chapter Pages =====
    suspend fun getChapterPages(chapter: MangaChapter): Result<List<ChapterPage>> {
        return try {
            val url = "https://api.mangadex.org/at-home/server/${chapter.id}"
            val req = Request.Builder().url(url)
                .header("User-Agent", "MangaApp/1.0 (Android)")
                .header("Accept", "application/json")
                .build()
            
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Result.failure(Exception("HTTP ${resp.code}"))
                val body = resp.body?.string() ?: return Result.failure(Exception("Empty response"))
                val json = JsonParser.parseString(body).asJsonObject
                val baseUrl = json.get("baseUrl").asString
                val chapterObj = json.getAsJsonObject("chapter")
                val hash = chapterObj.get("hash").asString
                val dataSaver = chapterObj.getAsJsonArray("dataSaver")
                
                val pages = mutableListOf<ChapterPage>()
                for (i in 0 until dataSaver.size()) {
                    val fileName = dataSaver[i].asString
                    pages.add(ChapterPage(
                        index = i,
                        url = "$baseUrl/data-saver/$hash/$fileName"
                    ))
                }
                Result.success(pages)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getChapterPages error: ${e.message}")
            Result.failure(e)
        }
    }

    // ===== 3asq chapters (fallback - try on phone) =====
    suspend fun get3asqChapters(slug: String): Result<List<MangaChapter>> {
        return try {
            val chapters = com.yazan.manga.data.fetch3asqChapters(slug)
            Result.success(chapters)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun formatDate(iso: String): String {
        if (iso.isEmpty()) return ""
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val date = sdf.parse(iso) ?: return iso.take(10)
            val out = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            out.format(date)
        } catch (e: Exception) {
            iso.take(10)
        }
    }
}
