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

    private val TAG = "MangaRepository"
    
    // 3asq API via Netlify (public, works on phone)
    private val ASQ_API = "https://manga-app-yazan.netlify.app/api/3asq"

    // ===== MangaDex for listing =====
    suspend fun getLatestManga(page: Int = 1): Result<List<MangaListItem>> {
        return try {
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
        return try {
            val offset = (page - 1) * 20
            val url = "https://api.mangadex.org/manga?limit=20&offset=$offset&availableTranslatedLanguage[]=en&order[latestUploadedChapter]=desc&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive"
            Result.success(fetchMangaDexList(url))
        } catch (e: Exception) {
            Result.failure(e)
        }
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
        val req = Request.Builder().url(url).header("User-Agent", "MangaApp/1.0").header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val json = JsonParser.parseString(body).asJsonObject
            val data = json.getAsJsonArray("data") ?: return emptyList()
            val items = mutableListOf<MangaListItem>()
            for (i in 0 until data.size()) {
                val m = data[i].asJsonObject
                val id = m.get("id").asString
                val attrs = m.getAsJsonObject("attributes") ?: continue
                val titleObj = attrs.getAsJsonObject("title") ?: continue
                var title = titleObj.get("ar")?.asString ?: titleObj.get("en")?.asString ?: titleObj.entrySet()?.firstOrNull()?.value?.asString ?: "بدون عنوان"
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
                items.add(MangaListItem(id = id, title = title, cover = cover, source = "mangadex", status = attrs.get("status")?.asString ?: "ongoing"))
            }
            return items
        }
    }

    // ===== Manga Details (MangaDex) =====
    suspend fun getMangaDetails(id: String): Result<MangaDetails> {
        return try {
            val req = Request.Builder().url("https://api.mangadex.org/manga/$id?includes[]=cover_art&includes[]=author").header("User-Agent", "MangaApp/1.0").header("Accept", "application/json").build()
            var title = "بدون عنوان"; var cover = ""; var author = ""; var description = ""; var status = "ongoing"; var genres = listOf<String>()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val body = resp.body?.string() ?: return@use
                val root = JsonParser.parseString(body).asJsonObject
                val data = root.getAsJsonObject("data") ?: return@use
                val attrs = data.getAsJsonObject("attributes") ?: return@use
                val titleObj = attrs.getAsJsonObject("title")
                if (titleObj != null) {
                    title = titleObj.get("ar")?.asString ?: titleObj.get("en")?.asString ?: titleObj.entrySet()?.firstOrNull()?.value?.asString ?: "بدون عنوان"
                }
                val descObj = attrs.getAsJsonObject("description")
                if (descObj != null) {
                    description = descObj.get("ar")?.asString ?: descObj.get("en")?.asString ?: ""
                }
                status = attrs.get("status")?.asString ?: "ongoing"
                val rels = data.getAsJsonArray("relationships")
                if (rels != null) {
                    for (i in 0 until rels.size()) {
                        val rel = rels[i].asJsonObject
                        if (rel.get("type").asString == "cover_art") {
                            val relAttrs = rel.getAsJsonObject("attributes")
                            val fileName = relAttrs?.get("fileName")?.asString
                            if (fileName != null) cover = "https://uploads.mangadex.org/covers/$id/$fileName.512.jpg"
                        }
                        if (rel.get("type").asString == "author") {
                            val relAttrs = rel.getAsJsonObject("attributes")
                            author = relAttrs?.get("name")?.asString ?: ""
                        }
                    }
                }
                val tags = attrs.getAsJsonArray("tags")
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

            // Try 3asq chapters first (Arabic, One Piece 1187)
            val asqSlug = guess3asqSlug(title)
            val asqChapters = try { get3asqChapters(asqSlug) } catch (e: Exception) { null }

            // Get MangaDex chapters as fallback
            val mdChapters = getMangaDexChapters(id)

            // Use 3asq if available, otherwise MangaDex
            val chapters = if (!asqChapters.isNullOrEmpty()) asqChapters else mdChapters

            Result.success(MangaDetails(id = id, title = title, cover = cover, description = description, author = author, artist = author, status = status, genres = genres, chapters = chapters, source = if (!asqChapters.isNullOrEmpty()) "3asq" else "mangadex", latestChapter = null))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMangaDetailsAllLanguages(id: String): Result<MangaDetails> {
        return getMangaDetails(id)
    }

    private fun guess3asqSlug(title: String): String {
        val lower = title.lowercase()
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
            else -> lower.replace(Regex("[^a-z0-9]+"), "-").replace(Regex("^-|-$"), "")
        }
    }

    // ===== 3asq chapters via Netlify API =====
    private fun get3asqChapters(slug: String): List<MangaChapter>? {
        return try {
            val req = Request.Builder().url("$ASQ_API/chapters?slug=$slug").header("Accept", "application/json").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = JsonParser.parseString(body).asJsonObject
                val chaptersArr = root.getAsJsonArray("chapters") ?: return null
                if (chaptersArr.size() == 0) return null
                val result = mutableListOf<MangaChapter>()
                for (i in 0 until chaptersArr.size()) {
                    val ch = chaptersArr[i].asJsonObject
                    val num = ch.get("number")?.asString ?: continue
                    result.add(MangaChapter(id = "3asq-$slug-$num", number = num, title = "الفصل $num", date = "", source = "3asq"))
                }
                if (result.isEmpty()) null else result
            }
        } catch (e: Exception) {
            Log.e(TAG, "3asq chapters error: ${e.message}")
            null
        }
    }

    private fun getMangaDexChapters(id: String): List<MangaChapter> {
        val allChapters = mutableListOf<Triple<String, String, String>>() // id, number, date
        var offset = 0
        val limit = 100
        var total = Int.MAX_VALUE
        while (offset < total && offset < 5000) {
            val url = "https://api.mangadex.org/manga/$id/feed?limit=$limit&offset=$offset&order[chapter]=desc&contentRating[]=safe&contentRating[]=suggestive"
            val req = Request.Builder().url(url).header("User-Agent", "MangaApp/1.0").header("Accept", "application/json").build()
            var done = false
            try {
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) { resp.close(); break }
                val body = resp.body?.string()
                resp.close()
                if (body.isNullOrEmpty()) break
                val root = JsonParser.parseString(body).asJsonObject
                val data = root.getAsJsonArray("data") ?: break
                total = root.get("total")?.asInt ?: break
                for (i in 0 until data.size()) {
                    val ch = data[i].asJsonObject
                    val chId = ch.get("id").asString
                    val attrs = ch.getAsJsonObject("attributes") ?: continue
                    val num = attrs.get("chapter")?.asString ?: continue
                    val publishAt = attrs.get("publishAt")?.asString ?: ""
                    allChapters.add(Triple(chId, num, publishAt))
                }
                if (data.size() < limit) done = true
            } catch (e: Exception) { break }
            if (done) break
            offset += limit
        }
        // Dedupe by chapter number
        return allChapters.groupBy { it.second }.mapValues { (_, list) -> list.first() }
            .values.sortedByDescending { it.second.toFloatOrNull() ?: 0f }
            .map { (chId, num, date) -> MangaChapter(id = chId, number = num, title = "الفصل $num", date = formatDate(date), source = "mangadex") }
    }

    // ===== Chapter Pages =====
    suspend fun getChapterPages(chapter: MangaChapter): Result<List<ChapterPage>> {
        return try {
            // If 3asq source, use 3asq pages API
            if (chapter.source == "3asq") {
                val parts = chapter.id.split("-")
                if (parts.size >= 3) {
                    val num = parts.last()
                    val slug = parts.dropLast(1).joinToString("-").removePrefix("3asq-")
                    val req = Request.Builder().url("$ASQ_API/pages?slug=$slug&chapter=$num").header("Accept", "application/json").build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return Result.failure(Exception("HTTP ${resp.code}"))
                        val body = resp.body?.string() ?: return Result.failure(Exception("Empty"))
                        val root = JsonParser.parseString(body).asJsonObject
                        val pagesArr = root.getAsJsonArray("pages") ?: return Result.failure(Exception("No pages"))
                        val pages = mutableListOf<ChapterPage>()
                        for (i in 0 until pagesArr.size()) {
                            val p = pagesArr[i].asJsonObject
                            val pUrl = p.get("url")?.asString ?: continue
                            // Convert relative URL to absolute Netlify URL
                            val absUrl = if (pUrl.startsWith("/")) "https://manga-app-yazan.netlify.app$pUrl" else pUrl
                            pages.add(ChapterPage(index = i, url = absUrl))
                        }
                        return Result.success(pages)
                    }
                }
            }
            // MangaDex
            val url = "https://api.mangadex.org/at-home/server/${chapter.id}"
            val req = Request.Builder().url(url).header("User-Agent", "MangaApp/1.0").header("Accept", "application/json").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return Result.failure(Exception("HTTP ${resp.code}"))
                val body = resp.body?.string() ?: return Result.failure(Exception("Empty"))
                val root = JsonParser.parseString(body).asJsonObject
                val baseUrl = root.get("baseUrl").asString
                val chapterObj = root.getAsJsonObject("chapter")
                val hash = chapterObj.get("hash").asString
                val dataSaver = chapterObj.getAsJsonArray("dataSaver")
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
