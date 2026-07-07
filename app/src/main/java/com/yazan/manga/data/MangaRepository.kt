package com.yazan.manga.data

import android.util.Log
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

    private val TAG = "MangaRepo"
    private val UA = "MangaApp/1.0 (Android)"
    
    // 3asq API on Netlify (public, works from phone)
    private val ASQ_API = "https://3asq-api.netlify.app/.netlify/functions"

    // ===== Arabic manga list from 3asq API =====
    suspend fun getLatestManga(page: Int = 1): Result<List<MangaListItem>> {
        return try {
            // 3asq doesn't have a list API, so use MangaDex Arabic list
            val offset = (page - 1) * 20
            val url = "https://api.mangadex.org/manga?limit=20&offset=$offset&availableTranslatedLanguage[]=ar&order[latestUploadedChapter]=desc&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive"
            Result.success(fetchMangaDexList(url))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPopularManga(page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val offset = (page - 1) * 20
            val url = "https://api.mangadex.org/manga?limit=20&offset=$offset&availableTranslatedLanguage[]=ar&order[followedCount]=desc&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive"
            Result.success(fetchMangaDexList(url))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getArabicManga(page: Int = 1): Result<List<MangaListItem>> {
        return getLatestManga(page)
    }

    suspend fun getEnglishManga(page: Int = 1): Result<List<MangaListItem>> {
        return getLatestManga(page)
    }

    suspend fun searchManga(query: String, page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val offset = (page - 1) * 20
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://api.mangadex.org/manga?title=$encoded&limit=20&offset=$offset&availableTranslatedLanguage[]=ar&order[relevance]=desc&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive"
            Result.success(fetchMangaDexList(url))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun fetchMangaDexList(url: String): List<MangaListItem> {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val root = JsonParser.parseString(body)
            if (!root.isJsonObject) return emptyList()
            val data = root.asJsonObject.getAsJsonArray("data") ?: return emptyList()
            val items = mutableListOf<MangaListItem>()
            for (i in 0 until data.size()) {
                try {
                    val m = data[i].asJsonObject
                    val id = m.get("id").asString
                    val attrs = m.getAsJsonObject("attributes") ?: continue
                    val titleObj = attrs.getAsJsonObject("title") ?: continue
                    var title = titleObj.get("ar")?.asString
                    if (title == null) title = titleObj.get("en")?.asString
                    if (title == null) {
                        val altTitles = attrs.getAsJsonArray("altTitles")
                        if (altTitles != null) {
                            for (j in 0 until altTitles.size()) {
                                val alt = altTitles[j].asJsonObject
                                val arTitle = alt.get("ar")?.asString
                                if (arTitle != null) { title = arTitle; break }
                            }
                        }
                    }
                    if (title == null) title = titleObj.entrySet()?.firstOrNull()?.value?.asString ?: "بدون عنوان"
                    var cover = ""
                    val rels = m.getAsJsonArray("relationships")
                    if (rels != null) {
                        for (j in 0 until rels.size()) {
                            val rel = rels[j].asJsonObject
                            if (rel.get("type").asString == "cover_art") {
                                val relAttrs = rel.getAsJsonObject("attributes")
                                val fileName = relAttrs?.get("fileName")?.asString
                                if (fileName != null) cover = "https://uploads.mangadex.org/covers/$id/$fileName.256.jpg"
                                break
                            }
                        }
                    }
                    val status = attrs.get("status")?.asString ?: "ongoing"
                    // Store the English title as slug for 3asq lookup
                    val enTitle = titleObj.get("en")?.asString ?: title
                    val slug = enTitle.lowercase().replace(Regex("[^a-z0-9]+"), "-").replace(Regex("^-|-$"), "")
                    items.add(MangaListItem(id = id, title = title, cover = cover, source = "mangadex", status = status))
                    // Store slug in a companion map for later use
                    mangaSlugs[id] = slug
                } catch (e: Exception) { continue }
            }
            return items
        }
    }

    companion object {
        private val mangaSlugs = mutableMapOf<String, String>()
        
        fun getSlugForId(id: String): String? = mangaSlugs[id]
    }

    // ===== Manga Details =====
    suspend fun getMangaDetails(id: String): Result<MangaDetails> {
        return try {
            // 1. Get details from MangaDex
            val req = Request.Builder().url("https://api.mangadex.org/manga/$id?includes[]=cover_art&includes[]=author")
                .header("User-Agent", UA).header("Accept", "application/json").build()
            
            var title = "بدون عنوان"
            var enTitle = ""
            var cover = ""
            var author = ""
            var description = ""
            var status = "ongoing"
            var genres = listOf<String>()
            
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val body = resp.body?.string() ?: return@use
                val root = JsonParser.parseString(body)
                if (!root.isJsonObject) return@use
                val data = root.asJsonObject.getAsJsonObject("data") ?: return@use
                val attrs = data.getAsJsonObject("attributes") ?: return@use
                
                val titleObj = attrs.getAsJsonObject("title")
                if (titleObj != null) {
                    title = titleObj.get("ar")?.asString ?: titleObj.get("en")?.asString ?: "بدون عنوان"
                    enTitle = titleObj.get("en")?.asString ?: title
                }
                val descObj = attrs.getAsJsonObject("description")
                if (descObj != null) {
                    description = descObj.get("ar")?.asString ?: descObj.get("en")?.asString ?: ""
                }
                status = attrs.get("status")?.asString ?: "ongoing"
                val rels = data.getAsJsonArray("relationships")
                if (rels != null) {
                    for (i in 0 until rels.size()) {
                        try {
                            val rel = rels[i].asJsonObject
                            when (rel.get("type").asString) {
                                "cover_art" -> {
                                    val relAttrs = rel.getAsJsonObject("attributes")
                                    val fileName = relAttrs?.get("fileName")?.asString
                                    if (fileName != null) cover = "https://uploads.mangadex.org/covers/$id/$fileName.512.jpg"
                                }
                                "author" -> {
                                    val relAttrs = rel.getAsJsonObject("attributes")
                                    author = relAttrs?.get("name")?.asString ?: ""
                                }
                            }
                        } catch (e: Exception) { continue }
                    }
                }
                val tags = attrs.getAsJsonArray("tags")
                if (tags != null) {
                    val genreList = mutableListOf<String>()
                    for (i in 0 until tags.size()) {
                        try {
                            val tag = tags[i].asJsonObject
                            val tagAttrs = tag.getAsJsonObject("attributes")
                            val nameObj = tagAttrs?.getAsJsonObject("name")
                            val name = nameObj?.get("en")?.asString
                            if (name != null) genreList.add(name)
                        } catch (e: Exception) { continue }
                    }
                    genres = genreList
                }
            }
            
            // 2. Try to get Arabic chapters from 3asq
            val slug = getSlugForId(id) ?: guessSlug(enTitle, title)
            val asqChapters = try { get3asqChapters(slug) } catch (e: Exception) { null }
            
            // 3. If 3asq has chapters, use them (Arabic!)
            // Otherwise use MangaDex (might have Arabic chapters too)
            val chapters = if (!asqChapters.isNullOrEmpty()) {
                Log.d(TAG, "Using 3asq chapters: ${asqChapters.size} for slug=$slug")
                asqChapters
            } else {
                Log.d(TAG, "3asq failed, using MangaDex chapters for $id")
                getMangaDexChapters(id)
            }
            
            Result.success(MangaDetails(
                id = id, title = title, cover = cover, description = description,
                author = author, artist = author, status = status, genres = genres,
                chapters = chapters, source = if (!asqChapters.isNullOrEmpty()) "3asq" else "mangadex",
                latestChapter = null
            ))
        } catch (e: Exception) {
            Log.e(TAG, "getMangaDetails: ${e.message}")
            Result.failure(e)
        }
    }

    private fun guessSlug(enTitle: String, arTitle: String): String {
        val lower = (enTitle + " " + arTitle).lowercase()
        return when {
            lower.contains("one piece") || lower.contains("ون بيس") || lower.contains("ونبيس") -> "one-piece"
            lower.contains("solo leveling") || lower.contains("سولو") -> "solo-leveling"
            lower.contains("jujutsu") -> "jujutsu-kaisen"
            lower.contains("chainsaw") -> "chainsaw-man"
            lower.contains("kingdom") -> "kingdom"
            lower.contains("hunter") -> "hunter-x-hunter"
            lower.contains("naruto") -> "naruto"
            lower.contains("attack on titan") || lower.contains("هجوم") -> "attack-on-titan"
            lower.contains("demon slayer") || lower.contains("قاتل") -> "demon-slayer-kimetsu-no-yaiba"
            lower.contains("my hero") -> "my-hero-academia"
            lower.contains("conan") || lower.contains("كونان") -> "detective-conan"
            else -> enTitle.lowercase().replace(Regex("[^a-z0-9]+"), "-").replace(Regex("^-|-$"), "")
        }
    }

    // ===== 3asq chapters via Netlify API =====
    private fun get3asqChapters(slug: String): List<MangaChapter>? {
        return try {
            val req = Request.Builder().url("$ASQ_API/chapters?slug=$slug")
                .header("Accept", "application/json").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = JsonParser.parseString(body)
                if (!root.isJsonObject) return null
                val chaptersArr = root.asJsonObject.getAsJsonArray("chapters") ?: return null
                if (chaptersArr.size() == 0) return null
                val result = mutableListOf<MangaChapter>()
                for (i in 0 until chaptersArr.size()) {
                    val ch = chaptersArr[i].asJsonObject
                    val num = ch.get("number")?.asString ?: continue
                    result.add(MangaChapter(
                        id = "3asq-$slug-$num",
                        number = num,
                        title = "الفصل $num",
                        date = "",
                        source = "3asq"
                    ))
                }
                if (result.isEmpty()) null else result
            }
        } catch (e: Exception) {
            Log.e(TAG, "3asq chapters error: ${e.message}")
            null
        }
    }

    private fun getMangaDexChapters(id: String): List<MangaChapter> {
        val allChapters = mutableListOf<Triple<String, String, String>>()
        var offset = 0
        val limit = 100
        var total = Int.MAX_VALUE
        while (offset < total && offset < 5000) {
            try {
                val url = "https://api.mangadex.org/manga/$id/feed?limit=$limit&offset=$offset&order[chapter]=desc&contentRating[]=safe&contentRating[]=suggestive"
                val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json").build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) { resp.close(); break }
                val body = resp.body?.string()
                resp.close()
                if (body.isNullOrEmpty()) break
                val root = JsonParser.parseString(body)
                if (!root.isJsonObject) break
                val data = root.asJsonObject.getAsJsonArray("data") ?: break
                total = root.asJsonObject.get("total")?.asInt ?: break
                for (i in 0 until data.size()) {
                    try {
                        val ch = data[i].asJsonObject
                        val chId = ch.get("id").asString
                        val attrs = ch.getAsJsonObject("attributes") ?: continue
                        val num = attrs.get("chapter")?.asString ?: continue
                        val publishAt = attrs.get("publishAt")?.asString ?: ""
                        allChapters.add(Triple(chId, num, publishAt))
                    } catch (e: Exception) { continue }
                }
                if (data.size() < limit) break
            } catch (e: Exception) { break }
            offset += limit
        }
        return allChapters.groupBy { it.second }.mapValues { it.value.first() }.values
            .sortedByDescending { it.second.toFloatOrNull() ?: 0f }
            .map { (chId, num, date) -> MangaChapter(id = chId, number = num, title = "الفصل $num", date = formatDate(date), source = "mangadex") }
    }

    // ===== Chapter Pages =====
    suspend fun getChapterPages(chapter: MangaChapter): Result<List<ChapterPage>> {
        return try {
            if (chapter.source == "3asq") {
                val parts = chapter.id.split("-")
                if (parts.size >= 3) {
                    val num = parts.last()
                    val slug = parts.dropLast(1).joinToString("-").removePrefix("3asq-")
                    val req = Request.Builder().url("$ASQ_API/pages?slug=$slug&chapter=$num")
                        .header("Accept", "application/json").build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return Result.failure(Exception("HTTP ${resp.code}"))
                        val body = resp.body?.string() ?: return Result.failure(Exception("Empty"))
                        val root = JsonParser.parseString(body)
                        if (!root.isJsonObject) return Result.failure(Exception("Invalid JSON"))
                        val pagesArr = root.asJsonObject.getAsJsonArray("pages") ?: return Result.failure(Exception("No pages"))
                        val pages = mutableListOf<ChapterPage>()
                        for (i in 0 until pagesArr.size()) {
                            val p = pagesArr[i].asJsonObject
                            val pUrl = p.get("url")?.asString ?: continue
                            pages.add(ChapterPage(index = i, url = pUrl))
                        }
                        if (pages.isEmpty()) {
                            // Fallback to MangaDex if 3asq has no pages for this chapter
                            return getMangaDexPagesForChapter(chapter)
                        }
                        Result.success(pages)
                    }
                } else {
                    getMangaDexPagesForChapter(chapter)
                }
            } else {
                // MangaDex
                getMangaDexPagesForChapter(chapter)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getChapterPages: ${e.message}")
            Result.failure(e)
        }
    }

    private fun getMangaDexPagesForChapter(chapter: MangaChapter): Result<List<ChapterPage>> {
        return try {
            val req = Request.Builder().url("https://api.mangadex.org/at-home/server/${chapter.id}")
                .header("User-Agent", UA).header("Accept", "application/json").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Result.failure(Exception("HTTP ${resp.code}"))
                val body = resp.body?.string() ?: return Result.failure(Exception("Empty"))
                val root = JsonParser.parseString(body)
                if (!root.isJsonObject) return Result.failure(Exception("Invalid JSON"))
                val baseUrl = root.asJsonObject.get("baseUrl")?.asString ?: return Result.failure(Exception("No baseUrl"))
                val chapterObj = root.asJsonObject.getAsJsonObject("chapter") ?: return Result.failure(Exception("No chapter"))
                val hash = chapterObj.get("hash")?.asString ?: return Result.failure(Exception("No hash"))
                val dataSaver = chapterObj.getAsJsonArray("dataSaver") ?: return Result.failure(Exception("No pages"))
                val pages = mutableListOf<ChapterPage>()
                for (i in 0 until dataSaver.size()) {
                    val fileName = dataSaver[i].asString
                    pages.add(ChapterPage(index = i, url = "$baseUrl/data-saver/$hash/$fileName"))
                }
                Result.success(pages)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMangaDetailsAllLanguages(id: String): Result<MangaDetails> {
        return getMangaDetails(id)
    }

    suspend fun get3asqChapters(slug: String): Result<List<MangaChapter>> {
        val result = get3asqChapters(slug as String) ?: return Result.failure(Exception("3asq unavailable"))
        return Result.success(result)
    }

    private fun formatDate(iso: String): String {
        if (iso.isEmpty()) return ""
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val date = sdf.parse(iso) ?: return iso.take(10)
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
        } catch (e: Exception) { iso.take(10) }
    }
}
