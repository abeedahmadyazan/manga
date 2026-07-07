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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val TAG = "MangaRepo"
    private val UA = "MangaApp/1.0 (Android)"
    private val ASQ_API = "https://3asq-api.netlify.app/.netlify/functions"

    // Cache: maps chapter number -> MangaDex chapter ID (for Arabic chapters)
    private val mdChapterCache = mutableMapOf<Pair<String, String>, String>()

    suspend fun getLatestManga(page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val offset = (page - 1) * 20
            val url = "https://api.mangadex.org/manga?limit=20&offset=$offset&availableTranslatedLanguage[]=ar&order[latestUploadedChapter]=desc&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive"
            Result.success(fetchList(url))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getPopularManga(page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val offset = (page - 1) * 20
            val url = "https://api.mangadex.org/manga?limit=20&offset=$offset&availableTranslatedLanguage[]=ar&order[followedCount]=desc&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive"
            Result.success(fetchList(url))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getArabicManga(page: Int = 1): Result<List<MangaListItem>> = getLatestManga(page)
    suspend fun getEnglishManga(page: Int = 1): Result<List<MangaListItem>> = getLatestManga(page)

    suspend fun searchManga(query: String, page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val offset = (page - 1) * 20
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://api.mangadex.org/manga?title=$encoded&limit=20&offset=$offset&availableTranslatedLanguage[]=ar&order[relevance]=desc&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive"
            Result.success(fetchList(url))
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun fetchList(url: String): List<MangaListItem> {
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
                                val f = rel.getAsJsonObject("attributes")?.get("fileName")?.asString
                                if (f != null) cover = "https://uploads.mangadex.org/covers/$id/$f.256.jpg"
                                break
                            }
                        }
                    }
                    items.add(MangaListItem(id = id, title = title, cover = cover, source = "mangadex", status = attrs.get("status")?.asString ?: "ongoing"))
                } catch (e: Exception) {}
            }
            return items
        }
    }

    suspend fun getMangaDetails(id: String): Result<MangaDetails> {
        return try {
            // 1. Get manga details from MangaDex
            val req = Request.Builder().url("https://api.mangadex.org/manga/$id?includes[]=cover_art&includes[]=author").header("User-Agent", UA).header("Accept", "application/json").build()
            var title = "بدون عنوان"; var enTitle = ""; var cover = ""; var author = ""; var description = ""; var status = "ongoing"; var genres = listOf<String>()
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
                    if (title == "بدون عنوان") {
                        val altTitles = attrs.getAsJsonArray("altTitles")
                        if (altTitles != null) { for (j in 0 until altTitles.size()) { val alt = altTitles[j].asJsonObject; val ar = alt.get("ar")?.asString; if (ar != null) { title = ar; break } } }
                    }
                }
                val descObj = attrs.getAsJsonObject("description")
                if (descObj != null) { description = descObj.get("ar")?.asString ?: descObj.get("en")?.asString ?: "" }
                status = attrs.get("status")?.asString ?: "ongoing"
                val rels = data.getAsJsonArray("relationships")
                if (rels != null) { for (i in 0 until rels.size()) { try { val rel = rels[i].asJsonObject; when (rel.get("type").asString) { "cover_art" -> { val f = rel.getAsJsonObject("attributes")?.get("fileName")?.asString; if (f != null) cover = "https://uploads.mangadex.org/covers/$id/$f.512.jpg" }; "author" -> { author = rel.getAsJsonObject("attributes")?.get("name")?.asString ?: "" } } } catch (e: Exception) {} } }
                val tags = attrs.getAsJsonArray("tags")
                if (tags != null) { val gl = mutableListOf<String>(); for (i in 0 until tags.size()) { try { val t = tags[i].asJsonObject; val n = t.getAsJsonObject("attributes")?.getAsJsonObject("name")?.get("en")?.asString; if (n != null) gl.add(n) } catch (e: Exception) {} }; genres = gl }
            }

            // 2. Get MangaDex Arabic chapters (cache them by number)
            val mdChapters = getMangaDexChapters(id)
            for (ch in mdChapters) {
                mdChapterCache[Pair(id, ch.number)] = ch.id
            }

            // 3. Try 3asq for more chapters (One Piece 1187)
            val slug = guessSlug(enTitle, title)
            val asqChapters = try { fetch3asqChapters(slug) } catch (e: Exception) { null }

            // 4. Merge: 3asq list + MangaDex chapters (prefer MangaDex for reliability)
            val chapters = if (!asqChapters.isNullOrEmpty()) {
                // Use 3asq chapter list (1187), but mark which ones have MangaDex backup
                asqChapters.map { asqCh ->
                    val mdId = mdChapterCache[Pair(id, asqCh.number)]
                    if (mdId != null) {
                        // Use MangaDex ID (more reliable)
                        MangaChapter(id = mdId, number = asqCh.number, title = asqCh.title, date = asqCh.date, source = "mangadex")
                    } else {
                        // Use 3asq (might work for recent chapters)
                        asqCh
                    }
                }
            } else {
                mdChapters
            }

            Result.success(MangaDetails(id = id, title = title, cover = cover, description = description, author = author, artist = author, status = status, genres = genres, chapters = chapters, source = "mangadex", latestChapter = null))
        } catch (e: Exception) { Result.failure(e) }
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

    private fun fetch3asqChapters(slug: String): List<MangaChapter>? {
        return try {
            val req = Request.Builder().url("$ASQ_API/chapters?slug=$slug").header("Accept", "application/json").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = JsonParser.parseString(body)
                if (!root.isJsonObject) return null
                val arr = root.asJsonObject.getAsJsonArray("chapters") ?: return null
                if (arr.size() == 0) return null
                val result = mutableListOf<MangaChapter>()
                for (i in 0 until arr.size()) {
                    val ch = arr[i].asJsonObject
                    val num = ch.get("number")?.asString ?: continue
                    result.add(MangaChapter(id = "3asq-$slug-$num", number = num, title = "الفصل $num", date = "", source = "3asq"))
                }
                if (result.isEmpty()) null else result
            }
        } catch (e: Exception) { null }
    }

    private fun getMangaDexChapters(id: String): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        var offset = 0; val limit = 100; var total = Int.MAX_VALUE
        while (offset < total && offset < 5000) {
            try {
                val url = "https://api.mangadex.org/manga/$id/feed?limit=$limit&offset=$offset&order[chapter]=desc&contentRating[]=safe&contentRating[]=suggestive"
                val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json").build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) { resp.close(); break }
                val body = resp.body?.string(); resp.close()
                if (body.isNullOrEmpty()) break
                val root = JsonParser.parseString(body)
                if (!root.isJsonObject) break
                val data = root.asJsonObject.getAsJsonArray("data") ?: break
                total = root.asJsonObject.get("total")?.asInt ?: break
                for (i in 0 until data.size()) {
                    try {
                        val ch = data[i].asJsonObject
                        val attrs = ch.getAsJsonObject("attributes") ?: continue
                        val lang = attrs.get("translatedLanguage")?.asString ?: ""
                        if (lang == "ar" || lang == "en") {
                            val chId = ch.get("id").asString
                            val num = attrs.get("chapter")?.asString ?: continue
                            val pub = attrs.get("publishAt")?.asString ?: ""
                            chapters.add(MangaChapter(id = chId, number = num, title = "الفصل $num", date = formatDate(pub), source = "mangadex"))
                        }
                    } catch (e: Exception) {}
                }
                if (data.size() < limit) break
            } catch (e: Exception) { break }
            offset += limit
        }
        return chapters.groupBy { it.number }.mapValues { (_, l) -> l.firstOrNull { it.id.contains("ar") } ?: l.first() }.values.sortedByDescending { it.number.toFloatOrNull() ?: 0f }
    }

    suspend fun getChapterPages(chapter: MangaChapter): Result<List<ChapterPage>> {
        return try {
            if (chapter.source == "3asq") {
                // Try 3asq first
                val parts = chapter.id.split("-")
                if (parts.size >= 3) {
                    val num = parts.last()
                    val slug = parts.dropLast(1).joinToString("-").removePrefix("3asq-")
                    val req = Request.Builder().url("$ASQ_API/pages?slug=$slug&chapter=$num").header("Accept", "application/json").build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            val body = resp.body?.string() ?: ""
                            val root = JsonParser.parseString(body)
                            if (root.isJsonObject) {
                                val arr = root.asJsonObject.getAsJsonArray("pages")
                                if (arr != null && arr.size() > 0) {
                                    val pages = mutableListOf<ChapterPage>()
                                    for (i in 0 until arr.size()) {
                                        val p = arr[i].asJsonObject
                                        val u = p.get("url")?.asString ?: continue
                                        pages.add(ChapterPage(index = i, url = if (u.startsWith("//")) "https:$u" else u))
                                    }
                                    if (pages.isNotEmpty()) return Result.success(pages)
                                }
                            }
                        }
                    }
                }
                // 3asq failed, no fallback (MangaDex ID not available)
                Result.failure(Exception("هذا الفصل غير متاح حالياً"))
            } else {
                // MangaDex
                val req = Request.Builder().url("https://api.mangadex.org/at-home/server/${chapter.id}").header("User-Agent", UA).header("Accept", "application/json").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return Result.failure(Exception("HTTP ${resp.code}"))
                    val body = resp.body?.string() ?: return Result.failure(Exception("Empty"))
                    val root = JsonParser.parseString(body)
                    if (!root.isJsonObject) return Result.failure(Exception("Invalid JSON"))
                    val baseUrl = root.asJsonObject.get("baseUrl")?.asString ?: return Result.failure(Exception("No baseUrl"))
                    val chObj = root.asJsonObject.getAsJsonObject("chapter") ?: return Result.failure(Exception("No chapter"))
                    val hash = chObj.get("hash")?.asString ?: return Result.failure(Exception("No hash"))
                    val ds = chObj.getAsJsonArray("dataSaver") ?: return Result.failure(Exception("No pages"))
                    val pages = mutableListOf<ChapterPage>()
                    for (i in 0 until ds.size()) { pages.add(ChapterPage(index = i, url = "$baseUrl/data-saver/$hash/${ds[i].asString}")) }
                    Result.success(pages)
                }
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun getMangaDetailsAllLanguages(id: String): Result<MangaDetails> = getMangaDetails(id)
    suspend fun get3asqChapters(slug: String): Result<List<MangaChapter>> = Result.failure(Exception("N/A"))

    private fun formatDate(iso: String): String {
        if (iso.isEmpty()) return ""
        return try { val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US); sdf.timeZone = TimeZone.getTimeZone("UTC"); val d = sdf.parse(iso) ?: return iso.take(10); SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d) } catch (e: Exception) { iso.take(10) }
    }
}
