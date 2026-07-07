package com.yazan.manga.data

import android.util.Log
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class MangaRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val TAG = "MangaRepo"
    private val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // ================================================================
    // MANGA LIST — from 3asq.pro homepage (Arabic only)
    // ================================================================
    suspend fun getLatestManga(page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val url = if (page <= 1) "https://3asq.pro/" else "https://3asq.pro/page/$page/"
            val html = fetchHtml(url) ?: return Result.success(emptyList())
            val items = parse3asqHomepage(html)
            if (items.isEmpty()) {
                // Fallback to MangaDex Arabic
                Result.success(fetchMangaDexArabicList(page))
            } else {
                Result.success(items)
            }
        } catch (e: Exception) {
            Log.e(TAG, "getLatestManga: ${e.message}")
            Result.success(fetchMangaDexArabicList(page))
        }
    }

    suspend fun getPopularManga(page: Int = 1): Result<List<MangaListItem>> {
        return getLatestManga(page)
    }

    suspend fun getArabicManga(page: Int = 1): Result<List<MangaListItem>> {
        return getLatestManga(page)
    }

    suspend fun getEnglishManga(page: Int = 1): Result<List<MangaListItem>> {
        return getLatestManga(page)
    }

    suspend fun searchManga(query: String, page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://3asq.pro/?s=$encoded&post_type=wp-manga"
            val html = fetchHtml(url) ?: return Result.success(emptyList())
            val items = parse3asqHomepage(html)
            if (items.isEmpty()) {
                Result.success(fetchMangaDexSearch(query))
            } else {
                Result.success(items)
            }
        } catch (e: Exception) {
            Result.success(fetchMangaDexSearch(query))
        }
    }

    // ================================================================
    // 3asq HOMEPAGE PARSER
    // ================================================================
    private fun parse3asqHomepage(html: String): List<MangaListItem> {
        val items = mutableListOf<MangaListItem>()
        
        // Pattern 1: page-listing-item blocks
        val p1 = Pattern.compile(
            "<div\\s+class=\"page-listing-item[^>]*>([\\s\\S]*?)</div>\\s*(?:<div\\s+class=\"page-listing-item|<div\\s+class=\"c-blog__heading|$)",
            Pattern.CASE_INSENSITIVE
        )
        val m1 = p1.matcher(html)
        while (m1.find()) {
            val block = m1.group(1) ?: continue
            val item = parse3asqMangaBlock(block)
            if (item != null) items.add(item)
        }
        
        // Pattern 2: item-thumb / tab-thumb blocks
        if (items.isEmpty()) {
            val p2 = Pattern.compile(
                "<a\\s+href=\"https?://3asq\\.pro/manga/([\\w-]+)/?\"[^>]*>[\\s\\S]*?<img[^>]+src=\"([^\"]+)\"[\\s\\S]*?title=\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE
            )
            val m2 = p2.matcher(html)
            val seen = mutableSetOf<String>()
            while (m2.find()) {
                val slug = m2.group(1) ?: continue
                if (seen.contains(slug)) continue
                seen.add(slug)
                val cover = m2.group(2) ?: ""
                val title = m2.group(3) ?: slug
                items.add(MangaListItem(id = slug, title = title, cover = fixCoverUrl(cover), source = "3asq"))
            }
        }
        
        // Pattern 3: any manga links with images
        if (items.isEmpty()) {
            val p3 = Pattern.compile(
                "<a[^>]+href=\"https?://3asq\\.pro/manga/([\\w-]+)/?\"[^>]*>[\\s\\S]*?<img[^>]+src=\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE
            )
            val m3 = p3.matcher(html)
            val seen = mutableSetOf<String>()
            while (m3.find()) {
                val slug = m3.group(1) ?: continue
                if (seen.contains(slug)) continue
                seen.add(slug)
                val cover = m3.group(2) ?: ""
                // Try to find title nearby
                val titleP = Pattern.compile("title=\"([^\"]+)\"")
                val titleM = titleP.matcher(m3.group(0) ?: "")
                val title = if (titleM.find()) titleM.group(1) ?: slug else slug
                items.add(MangaListItem(id = slug, title = title, cover = fixCoverUrl(cover), source = "3asq"))
            }
        }
        
        return items.distinctBy { it.id }
    }

    private fun parse3asqMangaBlock(block: String): MangaListItem? {
        val urlP = Pattern.compile("href=\"https?://3asq\\.pro/manga/([\\w-]+)/?\"", Pattern.CASE_INSENSITIVE)
        val slug = urlP.matcher(block).let { mm -> if (mm.find()) mm.group(1) else null } ?: return null
        
        val titleP = Pattern.compile("<a[^>]*>([^<]+)</a>", Pattern.CASE_INSENSITIVE)
        val titleM = titleP.matcher(block)
        val title = if (titleM.find()) titleM.group(1)?.trim() ?: slug else slug
        
        val coverP = Pattern.compile("<img[^>]+src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
        val coverM = coverP.matcher(block)
        val cover = if (coverM.find()) coverM.group(1) ?: "" else ""
        
        return MangaListItem(id = slug, title = title, cover = fixCoverUrl(cover), source = "3asq")
    }

    private fun fixCoverUrl(url: String): String {
        if (url.isEmpty()) return ""
        var u = url
        if (u.startsWith("//")) u = "https:$u"
        // Remove thumbnail suffix
        u = u.replace(Regex("-\\d+x\\d+\\.(jpg|png|webp)$"), ".$1")
        return u
    }

    // ================================================================
    // FETCH HTML from 3asq.pro
    // ================================================================
    private fun fetchHtml(url: String): String? {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", BROWSER_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .header("Accept-Encoding", "gzip")
                .header("Referer", "https://3asq.pro/")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .build()
            
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "HTTP ${resp.code} for $url")
                    return null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchHtml error: ${e.message}")
            null
        }
    }

    // ================================================================
    // MANGA DETAILS — from 3asq.pro
    // ================================================================
    suspend fun getMangaDetails(id: String): Result<MangaDetails> {
        return try {
            // id is the slug for 3asq
            val html = fetchHtml("https://3asq.pro/manga/$id/")
            if (html != null && html.length > 1000) {
                val details = parse3asqDetails(html, id)
                if (details != null) return Result.success(details)
            }
            // Fallback: try MangaDex
            getMangaDetailsMangaDex(id)
        } catch (e: Exception) {
            Log.e(TAG, "getMangaDetails: ${e.message}")
            getMangaDetailsMangaDex(id)
        }
    }

    private fun parse3asqDetails(html: String, slug: String): MangaDetails? {
        try {
            // Title
            val titleP = Pattern.compile("<h1[^>]*class=\"[^\"]*post-title[^\"]*\"[^>]*>([^<]+)", Pattern.CASE_INSENSITIVE)
            val titleM = titleP.matcher(html)
            val title = if (titleM.find()) titleM.group(1)?.trim() ?: slug else slug
            
            // Cover
            val coverP = Pattern.compile("<div[^>]*class=\"[^\"]*summary_image[^\"]*\"[^>]*>[\\s\\S]*?<img[^>]+src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
            val coverM = coverP.matcher(html)
            val cover = if (coverM.find()) fixCoverUrl(coverM.group(1) ?: "") else ""
            
            // Description
            val descP = Pattern.compile("<div[^>]*class=\"[^\"]*summary__content[^\"]*\"[^>]*>([\\s\\S]*?)</div>", Pattern.CASE_INSENSITIVE)
            val descM = descP.matcher(html)
            val description = if (descM.find()) descM.group(1)?.replace(Regex("<[^>]+>"), "")?.trim() ?: "" else ""
            
            // Latest chapter
            var latest = 0
            val p1 = Pattern.compile("href=\"https?://3asq\\.[a-z]+/manga/$slug/(\\d+)/?\"[^>]*id=\"btn-read-first\"", Pattern.CASE_INSENSITIVE)
            val m1 = p1.matcher(html)
            if (m1.find()) latest = m1.group(1)?.toIntOrNull() ?: 0
            
            if (latest == 0) {
                val p2 = Pattern.compile("href=\"https?://3asq\\.[a-z]+/manga/$slug/(\\d{1,5})/?\"", Pattern.CASE_INSENSITIVE)
                val m2 = p2.matcher(html)
                while (m2.find()) {
                    val n = m2.group(1)?.toIntOrNull() ?: 0
                    if (n > latest) latest = n
                }
            }
            
            // Generate chapters 1..latest
            val chapters = mutableListOf<MangaChapter>()
            for (i in latest downTo 1) {
                chapters.add(MangaChapter(
                    id = "3asq-$slug-$i",
                    number = i.toString(),
                    title = "الفصل $i",
                    date = "",
                    source = "3asq"
                ))
            }
            
            // Genres
            val genres = mutableListOf<String>()
            val genreP = Pattern.compile("<a[^>]+rel=\"tag\"[^>]*>([^<]+)</a>", Pattern.CASE_INSENSITIVE)
            val genreM = genreP.matcher(html)
            while (genreM.find()) {
                genreM.group(1)?.trim()?.let { genres.add(it) }
            }
            
            // Author
            val authorP = Pattern.compile("<div[^>]*class=\"author-content\"[^>]*>([\\s\\S]*?)</div>", Pattern.CASE_INSENSITIVE)
            val authorM = authorP.matcher(html)
            val author = if (authorM.find()) authorM.group(1)?.replace(Regex("<[^>]+>"), "")?.trim() ?: "" else ""
            
            // Status
            val statusP = Pattern.compile("<div[^>]*class=\"manga-title-badges[^\"]*\"[^>]*>\\s*(\\w+)", Pattern.CASE_INSENSITIVE)
            val statusM = statusP.matcher(html)
            val status = if (statusM.find()) {
                when (statusM.group(1)?.lowercase()) {
                    "ongoing" -> "ongoing"
                    "completed" -> "completed"
                    else -> "ongoing"
                }
            } else "ongoing"
            
            return MangaDetails(
                id = slug,
                title = title,
                cover = cover,
                description = description,
                author = author,
                artist = author,
                status = status,
                genres = genres,
                chapters = chapters,
                source = "3asq",
                latestChapter = if (latest > 0) latest.toString() else null
            )
        } catch (e: Exception) {
            Log.e(TAG, "parse3asqDetails error: ${e.message}")
            return null
        }
    }

    // ================================================================
    // CHAPTER PAGES — from 3asq.pro
    // ================================================================
    suspend fun getChapterPages(chapter: MangaChapter): Result<List<ChapterPage>> {
        return try {
            if (chapter.source == "3asq") {
                val parts = chapter.id.split("-")
                if (parts.size >= 3) {
                    val num = parts.last()
                    val slug = parts.dropLast(1).joinToString("-").removePrefix("3asq-")
                    val html = fetchHtml("https://3asq.pro/manga/$slug/$num/")
                    if (html != null) {
                        val pages = parse3asqPages(html)
                        if (pages.isNotEmpty()) return Result.success(pages)
                    }
                }
            }
            // Fallback: MangaDex
            getMangaDexPages(chapter.id)
        } catch (e: Exception) {
            Log.e(TAG, "getChapterPages: ${e.message}")
            Result.failure(e)
        }
    }

    private fun parse3asqPages(html: String): List<ChapterPage> {
        val pages = mutableListOf<ChapterPage>()
        val imgP = Pattern.compile("<img[^>]*class=\"[^\"]*wp-manga-chapter-img[^\"]*\"[^>]*src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
        val m = imgP.matcher(html)
        var index = 0
        while (m.find()) {
            val url = m.group(1)?.trim() ?: continue
            if (url.isNotEmpty() && !url.contains("placeholder")) {
                var fixedUrl = url
                if (fixedUrl.startsWith("//")) fixedUrl = "https:$fixedUrl"
                pages.add(ChapterPage(index = index, url = fixedUrl))
                index++
            }
        }
        // Fallback: image-\d+ pattern
        if (pages.isEmpty()) {
            val altP = Pattern.compile("<img[^>]*id=\"image-\\d+\"[^>]*src=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE)
            val am = altP.matcher(html)
            var idx = 0
            while (am.find()) {
                val url = am.group(1)?.trim() ?: continue
                if (url.isNotEmpty()) {
                    var fixedUrl = url
                    if (fixedUrl.startsWith("//")) fixedUrl = "https:$fixedUrl"
                    pages.add(ChapterPage(index = idx, url = fixedUrl))
                    idx++
                }
            }
        }
        return pages
    }

    // ================================================================
    // MANGADEX FALLBACK
    // ================================================================
    private fun fetchMangaDexArabicList(page: Int): List<MangaListItem> {
        return try {
            val offset = (page - 1) * 20
            val url = "https://api.mangadex.org/manga?limit=20&offset=$offset&availableTranslatedLanguage[]=ar&order[latestUploadedChapter]=desc&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive"
            val req = Request.Builder().url(url).header("User-Agent", "MangaApp/1.0").header("Accept", "application/json").build()
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
                        var title = titleObj.get("ar")?.asString ?: titleObj.get("en")?.asString ?: id
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
                    } catch (e: Exception) { continue }
                }
                items
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchMangaDexArabicList: ${e.message}")
            emptyList()
        }
    }

    private fun fetchMangaDexSearch(query: String): List<MangaListItem> {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://api.mangadex.org/manga?title=$encoded&limit=20&availableTranslatedLanguage[]=ar&order[relevance]=desc&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive"
            val req = Request.Builder().url(url).header("User-Agent", "MangaApp/1.0").header("Accept", "application/json").build()
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
                        var title = titleObj.get("ar")?.asString ?: titleObj.get("en")?.asString ?: id
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
                        items.add(MangaListItem(id = id, title = title, cover = cover, source = "mangadex"))
                    } catch (e: Exception) { continue }
                }
                items
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getMangaDetailsMangaDex(id: String): Result<MangaDetails> {
        return try {
            val req = Request.Builder().url("https://api.mangadex.org/manga/$id?includes[]=cover_art&includes[]=author").header("User-Agent", "MangaApp/1.0").header("Accept", "application/json").build()
            var title = "بدون عنوان"; var cover = ""; var author = ""; var description = ""; var status = "ongoing"
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
                            if (rel.get("type").asString == "cover_art") {
                                val relAttrs = rel.getAsJsonObject("attributes")
                                val fileName = relAttrs?.get("fileName")?.asString
                                if (fileName != null) cover = "https://uploads.mangadex.org/covers/$id/$fileName.512.jpg"
                            }
                            if (rel.get("type").asString == "author") {
                                val relAttrs = rel.getAsJsonObject("attributes")
                                author = relAttrs?.get("name")?.asString ?: ""
                            }
                        } catch (e: Exception) { continue }
                    }
                }
            }
            val chapters = getMangaDexChapters(id)
            Result.success(MangaDetails(id = id, title = title, cover = cover, description = description, author = author, artist = author, status = status, genres = emptyList(), chapters = chapters, source = "mangadex", latestChapter = null))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getMangaDexChapters(id: String): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        var offset = 0
        val limit = 100
        var total = Int.MAX_VALUE
        while (offset < total && offset < 5000) {
            try {
                val url = "https://api.mangadex.org/manga/$id/feed?limit=$limit&offset=$offset&order[chapter]=desc&contentRating[]=safe&contentRating[]=suggestive"
                val req = Request.Builder().url(url).header("User-Agent", "MangaApp/1.0").header("Accept", "application/json").build()
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
                        chapters.add(MangaChapter(id = chId, number = num, title = "الفصل $num", date = formatDate(publishAt), source = "mangadex"))
                    } catch (e: Exception) { continue }
                }
                if (data.size() < limit) break
            } catch (e: Exception) { break }
            offset += limit
        }
        // Dedupe
        return chapters.groupBy { it.number }.mapValues { it.value.first() }.values
            .sortedByDescending { it.number.toFloatOrNull() ?: 0f }
    }

    private fun getMangaDexPages(chapterId: String): Result<List<ChapterPage>> {
        return try {
            val req = Request.Builder().url("https://api.mangadex.org/at-home/server/$chapterId").header("User-Agent", "MangaApp/1.0").header("Accept", "application/json").build()
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
        return Result.failure(Exception("Use getMangaDetails instead"))
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
