package com.yazan.manga.data

import android.content.Context
import android.util.Log
import com.google.gson.JsonParser
import org.json.JSONArray
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class MangaRepository(private val appContext: Context? = null) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Separate client for 3asq proxy calls — very short timeouts to prevent ANR
    private val proxyClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val TAG = "MangaRepo"
    private val UA = "MangaApp/1.0 (Android)"
    private val ASQ_API = "https://asq-proxy.yznabyd.workers.dev"
    private val CORS_PROXY = "https://proxy.cors.sh/"
    private val ASQ_BASE = "https://3asq.online"
    private val CDN_CACHE_BASE = "https://raw.githubusercontent.com/abeedahmadyazan/manga/gh-pages/cache"
    private val CDN_CACHE_TTL_MS = 2 * 60 * 60 * 1000L  // 2 hours

    // Cache: maps chapter number -> MangaDex chapter ID (for Arabic chapters)
    private val mdChapterCache = mutableMapOf<Pair<String, String>, String>()

    // Cache: maps manga id -> MangaPill manga id (numeric)
    private val mpMangaIdCache = mutableMapOf<String, String>()
    // Cache: maps manga id -> (chapter number -> MangaPill chapter URL path)
    private val mpChapterUrlCache = mutableMapOf<String, MutableMap<String, String>>()

    /**
     * Normalize MangaDex cover URLs to use the small .256.jpg thumbnail
     * (≈42KB) instead of the full-resolution original (≈430KB). Full-res
     * covers were causing severe loading slowdowns in the grid view.
     *
     * The filename from the API already ends in .jpg, so the thumbnail URL
     * format is: {filename}.256.jpg  →  e.g.  abc.jpg.256.jpg
     */
    private fun fixCoverUrl(url: String): String {
        if (url.isEmpty()) return url
        // Only normalize MangaDex cover URLs
        if (!url.contains("uploads.mangadex.org/covers/")) return url
        // Already has a size suffix? Keep .256, downgrade larger ones.
        if (url.endsWith(".256.jpg")) return url
        if (url.endsWith(".512.jpg")) return url.replace(".512.jpg", ".256.jpg")
        if (url.endsWith(".128.jpg")) return url  // 128 is even smaller, keep it
        // Full-res (ends in .jpg without size suffix) → add .256.jpg
        if (url.endsWith(".jpg") && !url.endsWith(".256.jpg")) {
            return "$url.256.jpg"
        }
        return url
    }

    /**
     * Fetch cached manga list from jsDelivr CDN (gh-pages branch).
     * Updated every 2h by GitHub Actions. Returns empty list on failure.
     */
    private fun fetchCdnCache(type: String): List<MangaListItem> {
        return try {
            val url = "$CDN_CACHE_BASE/$type.json"
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json")
                .build()
            proxyClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val root = JsonParser.parseString(body).asJsonObject
                val items = root.getAsJsonArray("items") ?: return emptyList()
                val result = mutableListOf<MangaListItem>()
                for (i in 0 until items.size()) {
                    try {
                        val it = items[i].asJsonObject
                        result.add(MangaListItem(
                            id = it.get("id").asString,
                            title = it.get("title").asString,
                            cover = fixCoverUrl(it.get("cover")?.asString ?: ""),
                            source = it.get("source")?.asString ?: "mangadex",
                            status = it.get("status")?.asString
                        ))
                    } catch (e: Exception) {}
                }
                Log.d(TAG, "CDN cache '$type': ${result.size} items")
                result
            }
        } catch (e: Exception) {
            Log.w(TAG, "CDN cache '$type' failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun getLatestManga(page: Int = 1, contentType: String = "3asq"): Result<List<MangaListItem>> {
        return try {
            if (contentType == "3asq") {
                val items = fetch3asqListing(page)
                if (items.isNotEmpty()) Result.success(items)
                else {
                    Log.w(TAG, "3asq down, fallback to MangaDex")
                    fetchMangaDexList(page, "latest")
                }
            } else {
                // مصدر 1: MangaDex
                fetchMangaDexList(page, "latest")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPopularManga(page: Int = 1, contentType: String = "3asq"): Result<List<MangaListItem>> {
        return try {
            if (contentType == "3asq") {
                val items = fetch3asqListing(page).toMutableList()
                items.shuffle()
                if (items.isNotEmpty()) Result.success(items)
                else {
                    Log.w(TAG, "3asq down, fallback to MangaDex")
                    fetchMangaDexList(page, "popular")
                }
            } else {
                // مصدر 1: MangaDex popular
                fetchMangaDexList(page, "popular")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Fetch manga list from MangaDex (مصدر 1) */
    private fun fetchMangaDexList(page: Int, sort: String): Result<List<MangaListItem>> {
        return try {
            val offset = (page - 1) * 20
            val orderParam = if (sort == "popular") "order[followedCount]=desc" else "order[latestUploadedChapter]=desc"
            val url = "https://api.mangadex.org/manga?limit=20&offset=$offset&availableTranslatedLanguage[]=ar&hasAvailableChapters=true&$orderParam&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica"
            val items = fetchList(url)
            if (items.isNotEmpty()) Result.success(items)
            else Result.failure(Exception("تعذّر تحميل المانجا. حاول لاحقاً."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchManga(query: String, page: Int = 1, contentType: String = "3asq"): Result<List<MangaListItem>> {
        return try {
            if (contentType == "3asq") {
                // Search 3asq via proxy
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val url = "$ASQ_API/search?q=$encoded"
                val req = Request.Builder().url(url).header("Accept", "application/json").build()
                proxyClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "3asq search down, fallback to MangaDex")
                        return@use fetchMangaDexSearchFallback(query)
                    }
                    val body = resp.body?.string() ?: return@use fetchMangaDexSearchFallback(query)
                    val root = JsonParser.parseString(body).asJsonObject
                    val arr = root.getAsJsonArray("items") ?: return@use fetchMangaDexSearchFallback(query)
                    val items = mutableListOf<MangaListItem>()
                    for (i in 0 until arr.size()) {
                        try {
                            val item = arr[i].asJsonObject
                            val id = item.get("id")?.asString ?: continue
                            val title = item.get("title")?.asString ?: continue
                            val cover = item.get("cover")?.asString ?: ""
                            items.add(MangaListItem(id = "3asq-$id", title = title, cover = cover, source = "3asq", status = "ongoing"))
                        } catch (e: Exception) {}
                    }
                    Result.success(items)
                }
            } else {
                // Search MangaDex (مصدر 1)
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val offset = (page - 1) * 20
                val url = "https://api.mangadex.org/manga?title=$encoded&limit=20&offset=$offset&availableTranslatedLanguage[]=ar&hasAvailableChapters=true&order[relevance]=desc&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica"
                val items = fetchList(url)
                Result.success(items)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Curated collection of popular Arabic-translated Japanese & Korean light
     * novels. MangaDex doesn't host light novels, so we serve a hand-picked
     * list of widely fan-translated titles. Each novel has a stable fake ID
     * (novel-*) so the details screen can detect it. Covers use Unsplash
     * (open license). Returns 20 items per "page" (page 1 = items 0..19).
     */
    private fun getCuratedNovels(page: Int): List<MangaListItem> {
        val all = listOf(
            NovelsData.SOLO_LEVELING, NovelsData.ORV, NovelsData.MUSHOKU_TENSEI,
            NovelsData.TATE_NO_YUUSHA, NovelsData.KONOSUBA, NovelsData.REZERO,
            NovelsData.OVERLORD, NovelsData.ARIFURETA, NovelsData.TSUKIMICHI,
            NovelsData.DANMACHI, NovelsData.TENSURA, NovelsData.SAO,
            NovelsData.HAMEFURA, NovelsData.GRAVEMARK, NovelsData.WORTENIA,
            NovelsData.KURO_NO_SENKI, NovelsData.EIGHTH, NovelsData.MOBSEKA,
            NovelsData.SLOW_PRINCESS, NovelsData.DEATH_MAGE, NovelsData.ISEKAI_NONBIRI,
            NovelsData.MYDEATH, NovelsData.REDICE, NovelsData.NOBLESSE,
            NovelsData.TOWER_OF_GOD, NovelsData.OMNISCIENT, NovelsData.SLIME,
            NovelsData.MUSHOKU_TENSEI, NovelsData.RISING_SHIELD, NovelsData.KNIGHT_BLOOD
        ).distinctBy { it.id }

        val pageSize = 20
        val start = (page - 1) * pageSize
        if (start >= all.size) return emptyList()
        return all.drop(start).take(pageSize).map { n ->
            MangaListItem(
                id = n.id,
                title = n.title,
                cover = n.cover,
                source = "novel",
                status = n.status
            )
        }
    }

    private object NovelsData {
        data class Novel(val id: String, val title: String, val cover: String, val status: String)

        val SOLO_LEVELING = Novel("novel-solo-leveling", "سولو ليفيلنغ — الرواية",
            "https://images.unsplash.com/photo-1543002588-bfa74002ed7e?w=400&h=560&fit=crop&auto=format", "completed")
        val ORV = Novel("novel-orv", "وجهة نظر القارئ العراف",
            "https://images.unsplash.com/photo-1532012197267-da84d127e765?w=400&h=560&fit=crop&auto=format", "ongoing")
        val MUSHOKU_TENSEI = Novel("novel-mushoku", "إعادة التجسد عاطل البطالة",
            "https://images.unsplash.com/photo-1518373714866-3f1478910cc0?w=400&h=560&fit=crop&auto=format", "completed")
        val TATE_NO_YUUSHA = Novel("novel-tate", "صعود بطل الدرع",
            "https://images.unsplash.com/photo-1519682337058-a94d519337bc?w=400&h=560&fit=crop&auto=format", "completed")
        val KONOSUBA = Novel("novel-konosuba", "حظاً سعيداً في هذا العالم الرائع!",
            "https://images.unsplash.com/photo-1551029506-0807df4e2031?w=400&h=560&fit=crop&auto=format", "ongoing")
        val REZERO = Novel("novel-rezero", "إعادة الحياة من الصفر في عالم آخر",
            "https://images.unsplash.com/photo-1535905557558-afc4877a26fc?w=400&h=560&fit=crop&auto=format", "ongoing")
        val OVERLORD = Novel("novel-overlord", "أوفيرلورد",
            "https://images.unsplash.com/photo-1571906484236-52cd0f769691?w=400&h=560&fit=crop&auto=format", "ongoing")
        val ARIFURETA = Novel("novel-arifureta", "أريفورتا — من الأعلى إلى الأدنى",
            "https://images.unsplash.com/photo-1606112219348-204d7d8b94ee?w=400&h=560&fit=crop&auto=format", "ongoing")
        val TSUKIMICHI = Novel("novel-tsukimichi", "رحلة القمر في عالم آخر",
            "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=400&h=560&fit=crop&auto=format", "ongoing")
        val DANMACHI = Novel("novel-danmachi", "هل من الخطأ محاولة التقاط فتيات في زنزانة؟",
            "https://images.unsplash.com/photo-1507842217343-583bb7270b66?w=400&h=560&fit=crop&auto=format", "ongoing")
        val TENSURA = Novel("novel-tensura", "حياتي كصبار — تنسورا",
            "https://images.unsplash.com/photo-1614850523060-8da1d56ae167?w=400&h=560&fit=crop&auto=format", "ongoing")
        val SAO = Novel("novel-sao", "سورد آرت أونلاين",
            "https://images.unsplash.com/photo-1551103782-8ab07afd45c1?w=400&h=560&fit=crop&auto=format", "ongoing")
        val HAMEFURA = Novel("novel-hamefura", "حياتي الثانية كشريرة",
            "https://images.unsplash.com/photo-1518306727298-4c17e1bf6942?w=400&h=560&fit=crop&auto=format", "completed")
        val GRAVEMARK = Novel("novel-gravemark", "مهندس القبور",
            "https://images.unsplash.com/photo-1604079628040-94301bb21b91?w=400&h=560&fit=crop&auto=format", "ongoing")
        val WORTENIA = Novel("novel-wortenia", "أرض السحر — وورتينيا",
            "https://images.unsplash.com/photo-1568871391783-83f7a36dfe23?w=400&h=560&fit=crop&auto=format", "ongoing")
        val KURO_NO_SENKI = Novel("novel-kuro", "السيف الأسود",
            "https://images.unsplash.com/photo-1578916171728-9d0b3cd6c2b5?w=400&h=560&fit=crop&auto=format", "completed")
        val EIGHTH = Novel("novel-eighth", "الابن الثامن؟ لا، أنا الثالث!",
            "https://images.unsplash.com/photo-1534447677768-be436bb09401?w=400&h=560&fit=crop&auto=format", "completed")
        val MOBSEKA = Novel("novel-mobseka", "الخبير في إدارة المصير",
            "https://images.unsplash.com/photo-1551582045-6ec9c11d8697?w=400&h=560&fit=crop&auto=format", "ongoing")
        val SLOW_PRINCESS = Novel("novel-slow", "أميرة الحياة الهادئة",
            "https://images.unsplash.com/photo-1519682337058-a94d519337bc?w=400&h=560&fit=crop&auto=format", "ongoing")
        val DEATH_MAGE = Novel("novel-death-mage", "ساحر الموت الذي لا يريد أن يكون بطلاً",
            "https://images.unsplash.com/photo-1571906484236-52cd0f769691?w=400&h=560&fit=crop&auto=format", "ongoing")
        val ISEKAI_NONBIRI = Novel("novel-isekai-nonbiri", "حياة هادئة في عالم آخر",
            "https://images.unsplash.com/photo-1606112219348-204d7d8b94ee?w=400&h=560&fit=crop&auto=format", "ongoing")
        val MYDEATH = Novel("novel-mydeath", "وفاتي كـ...",
            "https://images.unsplash.com/photo-1543002588-bfa74002ed7e?w=400&h=560&fit=crop&auto=format", "ongoing")
        val REDICE = Novel("novel-redice", "الجليد الأحمر",
            "https://images.unsplash.com/photo-1489599849927-2ee91cede3ba?w=400&h=560&fit=crop&auto=format", "completed")
        val NOBLESSE = Novel("novel-noblesse", "نبيل",
            "https://images.unsplash.com/photo-1518709268805-4e9042af2176?w=400&h=560&fit=crop&auto=format", "completed")
        val TOWER_OF_GOD = Novel("novel-tog", "برج الإله",
            "https://images.unsplash.com/photo-1519682337058-a94d519337bc?w=400&h=560&fit=crop&auto=format", "ongoing")
        val OMNISCIENT = Novel("novel-omniscient", "القارئ العراف",
            "https://images.unsplash.com/photo-1532012197267-da84d127e765?w=400&h=560&fit=crop&auto=format", "completed")
        val SLIME = Novel("novel-slime", "تحولت لصبار",
            "https://images.unsplash.com/photo-1614850523060-8da1d56ae167?w=400&h=560&fit=crop&auto=format", "ongoing")
        val RISING_SHIELD = Novel("novel-rising-shield", "بطل الدرع الصاعد",
            "https://images.unsplash.com/photo-1519682337058-a94d519337bc?w=400&h=560&fit=crop&auto=format", "completed")
        val KNIGHT_BLOOD = Novel("novel-knight-blood", "دم الفارس",
            "https://images.unsplash.com/photo-1551582045-6ec9c11d8697?w=400&h=560&fit=crop&auto=format", "ongoing")
    }

    /**
     * Fetch manga listing from 3asq.online. Returns ~22 items per page.
     * 3asq hosts Arabic-translated manga, manhwa, AND manhua — all in one
     * listing. Each manga's type (مانجا/مانهوا/مانهوا) is on its detail page,
     * not the listing, so we return them all as "3asq" source.
     *
     * URL: https://3asq.online/manga/page/{page}/
     */
    private fun fetch3asqListing(page: Int): List<MangaListItem> {
        return try {
            val url = "$ASQ_API/listing?page=$page"
            Log.d(TAG, "3asq: fetching $url")
            val req = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", UA)
                .build()
            proxyClient.newCall(req).execute().use { resp ->
                Log.d(TAG, "3asq: response code ${resp.code}")
                if (!resp.isSuccessful) {
                    Log.w(TAG, "3asq: HTTP ${resp.code}")
                    return emptyList()
                }
                val body = resp.body?.string()
                if (body.isNullOrEmpty()) {
                    Log.w(TAG, "3asq: empty body")
                    return emptyList()
                }
                Log.d(TAG, "3asq: body length ${body.length}")
                
                val root = JsonParser.parseString(body).asJsonObject
                val arr = root.getAsJsonArray("items")
                if (arr == null) {
                    Log.w(TAG, "3asq: no items array in response")
                    return emptyList()
                }
                
                val items = mutableListOf<MangaListItem>()
                for (i in 0 until arr.size()) {
                    try {
                        val item = arr[i].asJsonObject
                        val id = item.get("id")?.asString ?: continue
                        val title = item.get("title")?.asString ?: continue
                        val cover = item.get("cover")?.asString ?: ""
                        items.add(MangaListItem(
                            id = "3asq-$id",  // MUST prefix with "3asq-" so getMangaDetails recognizes it
                            title = title,
                            cover = cover,
                            source = "3asq",
                            status = "ongoing"
                        ))
                    } catch (e: Exception) {
                        Log.w(TAG, "3asq: parse error at item $i: ${e.message}")
                    }
                }
                Log.d(TAG, "3asq listing page $page: ${items.size} items")
                items
            }
        } catch (e: Exception) {
            Log.e(TAG, "3asq listing failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Fetch a manga's details + chapter list from 3asq.online via the
     * Netlify proxy. Returns title, cover, type (مانجا/مانهوا/مانهوا),
     * description, and the full Arabic chapter list.
     */
    private fun fetch3asqMangaDetails(slug: String): MangaDetails? {
        return try {
            // Fetch chapter list from the Netlify proxy (works — no Cloudflare)
            // We NO LONGER fetch the detail page directly from 3asq.online
            // because Cloudflare blocks the app's requests. Instead, we use
            // the proxy for chapters and derive basic info from the slug.
            val chaptersReq = Request.Builder()
                .url("$ASQ_API/chapters?slug=$slug")
                .header("Accept", "application/json")
                .build()
            val chapters = mutableListOf<MangaChapter>()
            proxyClient.newCall(chaptersReq).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = JsonParser.parseString(body).asJsonObject
                val chArr = root.getAsJsonArray("chapters") ?: return null
                for (i in 0 until chArr.size()) {
                    try {
                        val ch = chArr[i].asJsonObject
                        val num = ch.get("number")?.asString ?: continue
                        chapters.add(MangaChapter(
                            id = "3asq-$slug-$num",
                            number = num,
                            title = "الفصل $num",
                            date = "",
                            source = "3asq"
                        ))
                    } catch (e: Exception) {}
                }
            }
            // Derive a readable title from the slug (e.g. "solo-leveling" → "Solo Leveling")
            val title = slug.split("-").joinToString(" ") { word ->
                word.replaceFirstChar { it.uppercase() }
            }
            MangaDetails(
                id = "3asq-$slug",
                title = title,
                cover = "",  // cover will be loaded from the listing cache
                description = "",
                author = "",
                artist = "",
                status = "ongoing",
                genres = listOf("مانجا"),
                chapters = chapters,
                source = "3asq",
                latestChapter = chapters.firstOrNull()?.number,
                rating = null,
                sources = emptyList(),
                chaptersBySource = emptyMap()
            )
        } catch (e: Exception) {
            Log.w(TAG, "fetch3asqMangaDetails($slug) failed: ${e.message}")
            null
        }
    }






    /** Fallback: search MangaDex when 3asq is down. */
    private fun fetchMangaDexSearchFallback(query: String): Result<List<MangaListItem>> {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://api.mangadex.org/manga?title=$encoded&limit=20&availableTranslatedLanguage[]=ar&hasAvailableChapters=true&order[relevance]=desc&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica"
            val items = fetchList(url)
            Result.success(items)
        } catch (e: Exception) { Result.success(emptyList()) }
    }

    private fun fetchList(url: String): List<MangaListItem> {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json").build()
        proxyClient.newCall(req).execute().use { resp ->
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
        // Novels (curated list): return a details object directly without
        // hitting the MangaDex API (which would 404 on a novel-* fake ID).
        if (id.startsWith("novel-")) {
            val novel = listOf(
                NovelsData.SOLO_LEVELING, NovelsData.ORV, NovelsData.MUSHOKU_TENSEI,
                NovelsData.TATE_NO_YUUSHA, NovelsData.KONOSUBA, NovelsData.REZERO,
                NovelsData.OVERLORD, NovelsData.ARIFURETA, NovelsData.TSUKIMICHI,
                NovelsData.DANMACHI, NovelsData.TENSURA, NovelsData.SAO,
                NovelsData.HAMEFURA, NovelsData.GRAVEMARK, NovelsData.WORTENIA,
                NovelsData.KURO_NO_SENKI, NovelsData.EIGHTH, NovelsData.MOBSEKA,
                NovelsData.SLOW_PRINCESS, NovelsData.DEATH_MAGE, NovelsData.ISEKAI_NONBIRI,
                NovelsData.MYDEATH, NovelsData.REDICE, NovelsData.NOBLESSE,
                NovelsData.TOWER_OF_GOD, NovelsData.OMNISCIENT, NovelsData.SLIME,
                NovelsData.RISING_SHIELD, NovelsData.KNIGHT_BLOOD
            ).firstOrNull { it.id == id }
            if (novel != null) {
                val statusAr = if (novel.status == "completed") "مكتملة" else "مستمر"
                val details = MangaDetails(
                    id = novel.id,
                    title = novel.title,
                    cover = novel.cover,
                    description = "رواية خفيفة مترجمة للعربية. اقرأها عبر مصادر الترجمة الخارجية.",
                    author = "غير معروف",
                    artist = "",
                    status = statusAr,
                    genres = listOf("رواية", "فانتازيا", "أكشن"),
                    chapters = emptyList(),
                    source = "novel",
                    latestChapter = null,
                    rating = null,
                    sources = emptyList(),
                    chaptersBySource = emptyMap()
                )
                return Result.success(details)
            }
            return Result.failure(Exception("الرواية غير موجودة"))
        }
        // 3asq manga: fetch details + chapters from 3asq.online via the
        // Netlify proxy (3asq-api.netlify.app). 3asq hosts Arabic manhwa,
        // manhua, and manga — all with full Arabic chapter translations.
        if (id.startsWith("3asq-")) {
            val slug = id.removePrefix("3asq-")
            return try {
                val details = fetch3asqMangaDetails(slug)
                if (details != null) {
                    Result.success(details)
                } else {
                    Result.failure(Exception("تعذّر تحميل تفاصيل المانجا"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
        // MangaDex (مصدر 1): fetch details from MangaDex API
        return try {
            getMangaDetailsFromNetwork(id)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getMangaDetailsFromNetwork(id: String): Result<MangaDetails> {
        return try {
            // 1. Get manga details from MangaDex
            val req = Request.Builder().url("https://api.mangadex.org/manga/$id?includes[]=cover_art&includes[]=author").header("User-Agent", UA).header("Accept", "application/json").build()
            var title = "بدون عنوان"; var enTitle = ""; var cover = ""; var author = ""; var description = ""; var status = "ongoing"; var genres = listOf<String>()
            proxyClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val body = resp.body?.string() ?: return@use
                val root = JsonParser.parseString(body)
                if (!root.isJsonObject) return@use
                val data = root.asJsonObject.getAsJsonObject("data") ?: return@use
                val attrs = data.getAsJsonObject("attributes") ?: return@use
                val titleObj = attrs.getAsJsonObject("title")
                if (titleObj != null) {
                    title = titleObj.get("ar")?.asString ?: titleObj.get("en")?.asString ?: "بدون عنوان"
                    // enTitle: prefer title.en, then title.ja-ro (romaji), then any
                    // altTitle.en, then any altTitle.ja-ro. Without this fallback,
                    // manga like Jujutsu Kaisen (which has only title.ja-ro) end up
                    // with an Arabic enTitle — and guessSlug fails to match.
                    enTitle = titleObj.get("en")?.asString
                        ?: titleObj.get("ja-ro")?.asString
                        ?: ""
                    if (title == "بدون عنوان") {
                        val altTitles = attrs.getAsJsonArray("altTitles")
                        if (altTitles != null) { for (j in 0 until altTitles.size()) { val alt = altTitles[j].asJsonObject; val ar = alt.get("ar")?.asString; if (ar != null) { title = ar; break } } }
                    }
                    // If enTitle is still empty, scan altTitles for an English form
                    if (enTitle.isEmpty()) {
                        val altTitles = attrs.getAsJsonArray("altTitles")
                        if (altTitles != null) {
                            for (j in 0 until altTitles.size()) {
                                val alt = altTitles[j].asJsonObject
                                val en = alt.get("en")?.asString
                                if (!en.isNullOrEmpty()) { enTitle = en; break }
                            }
                            // Last resort: ja-ro from altTitles
                            if (enTitle.isEmpty()) {
                                for (j in 0 until altTitles.size()) {
                                    val alt = altTitles[j].asJsonObject
                                    val ja = alt.get("ja-ro")?.asString
                                    if (!ja.isNullOrEmpty()) { enTitle = ja; break }
                                }
                            }
                        }
                    }
                }
                val descObj = attrs.getAsJsonObject("description")
                if (descObj != null) { description = descObj.get("ar")?.asString ?: descObj.get("en")?.asString ?: "" }
                status = attrs.get("status")?.asString ?: "ongoing"
                val rels = data.getAsJsonArray("relationships")
                if (rels != null) { for (i in 0 until rels.size()) { try { val rel = rels[i].asJsonObject; when (rel.get("type").asString) { "cover_art" -> { val f = rel.getAsJsonObject("attributes")?.get("fileName")?.asString; if (f != null) cover = "https://uploads.mangadex.org/covers/$id/$f.256.jpg" }; "author" -> { author = rel.getAsJsonObject("attributes")?.get("name")?.asString ?: "" } } } catch (e: Exception) {} } }
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
            val asqChapters = try {
                if (slug.isBlank()) {
                    // Slug couldn't be guessed (e.g. purely Arabic title) — try
                    // searching 3asq by the manga title instead.
                    search3asqChapters(enTitle.ifBlank { title })
                } else {
                    fetch3asqChapters(slug)
                }
            } catch (e: Exception) {
                Log.w(TAG, "3asq fetch failed: ${e.message}")
                null
            }

            // 3b. MangaPill fallback — fetch chapter list so we can show it
            // as a source AND use it as a fallback for chapter pages.
            val mpMangaId = MangaPillClient.findMangaId(enTitle.ifBlank { title })
            val mpChapters = if (mpMangaId != null) {
                MangaPillClient.fetchChapterList(mpMangaId)
            } else emptyList()
            // Cache: chapter number -> MangaPill chapter URL path
            val mpUrlCache = mutableMapOf<String, String>()
            mpChapters.forEach { (num, url) -> mpUrlCache[num] = url }
            mpMangaIdCache[id] = mpMangaId ?: ""
            mpChapterUrlCache[id] = mpUrlCache
            if (mpChapters.isNotEmpty()) {
                Log.d(TAG, "MangaPill: ${mpChapters.size} chapters available for $enTitle")
            }

            // 3c. MangaHere — additional English source for chapter listing.
            // We use MangaHere's chapter list (often more complete than
            // MangaPill's) but route page requests through MangaPill because
            // MangaHere loads images via JS.
            val mhSlug = MangaHereClient.findMangaSlug(enTitle.ifBlank { title })
            val mhChapters = if (mhSlug != null) {
                MangaHereClient.fetchChapterList(mhSlug)
            } else emptyList()
            // Cache MangaHere URL paths too — when the user picks the MangaHere
            // source, we look up the chapter number in mpUrlCache (MangaPill)
            // to fetch the actual images.
            if (mhChapters.isNotEmpty()) {
                Log.d(TAG, "MangaHere: ${mhChapters.size} chapters available for $enTitle")
            }

            // ============================================================
            // 4. Build the multi-source model.
            // Each source is exposed to the user under a generic label
            // ("المصدر 1", "المصدر 2", ...) without revealing the upstream
            // provider. The user picks one; we look up the chapters for
            // that source from chaptersBySource.
            // ============================================================

            // Build chapter lists per source.
            val asqChapterList: List<MangaChapter> = (asqChapters ?: emptyList()).map { asqCh ->
                val mdId = mdChapterCache[Pair(id, asqCh.number)]
                if (mdId != null) {
                    MangaChapter(id = mdId, number = asqCh.number, title = asqCh.title, date = asqCh.date, source = "mangadex")
                } else {
                    asqCh
                }
            }
            val mdChapterList: List<MangaChapter> = mdChapters
            val mpChapterList: List<MangaChapter> = mpChapters.map { (num, urlPath) ->
                MangaChapter(id = urlPath, number = num, title = "Chapter $num", date = "", source = "mangapill")
            }
            val mhChapterList: List<MangaChapter> = mhChapters.map { (num, _) ->
                // Reuse MangaPill's URL cache for the actual page fetch, but
                // mark the chapter as mangahere so the chip knows it's the
                // MangaHere listing. When the user opens this chapter, the
                // Reader will look up the same chapter number in
                // mpChapterUrlCache to fetch pages from MangaPill.
                MangaChapter(
                    id = "mangahere-$id-$num",
                    number = num,
                    title = "Chapter $num",
                    date = "",
                    source = "mangahere"
                )
            }

            val chaptersBySource = mutableMapOf<String, List<MangaChapter>>()
            val sourcesList = mutableListOf<MangaSourceInfo>()
            var sourceIdx = 1

            // Only Arabic sources are shown to the user as selectable chips.
            // English sources (MangaPill, MangaHere) are kept as silent fallbacks
            // for page-fetching only — never revealed to the user.
            if (asqChapterList.isNotEmpty()) {
                val key = "3asq"
                chaptersBySource[key] = asqChapterList
                sourcesList.add(MangaSourceInfo(
                    key = key,
                    label = "المصدر $sourceIdx",
                    language = "ar",
                    chapterCount = asqChapterList.size
                ))
                sourceIdx++
            }
            if (mdChapterList.isNotEmpty()) {
                val key = "mangadex"
                chaptersBySource[key] = mdChapterList
                sourcesList.add(MangaSourceInfo(
                    key = key,
                    label = "المصدر $sourceIdx",
                    language = "ar",
                    chapterCount = mdChapterList.size
                ))
                sourceIdx++
            }
            // English sources — registered in chaptersBySource so they can be
            // used as fallbacks, but NOT added to sourcesList (so no chip shown)
            if (mhChapterList.isNotEmpty()) {
                chaptersBySource["mangahere"] = mhChapterList
            }
            if (mpChapterList.isNotEmpty()) {
                chaptersBySource["mangapill"] = mpChapterList
            }

            // Default chapters: prefer the first Arabic source. If no Arabic
            // source has any chapters, fall back to the first available source
            // silently (so the user at least sees a chapter list to read).
            val defaultChapters = sourcesList.firstOrNull()?.let { chaptersBySource[it.key] }
                ?: chaptersBySource["mangahere"]
                ?: chaptersBySource["mangapill"]
                ?: emptyList()

            // If we fell back to an English source silently, add it to the
            // visible sources list too (as المصدر 1) so the chip group shows
            // something and the user can interact with it.
            if (sourcesList.isEmpty() && defaultChapters.isNotEmpty()) {
                val fallbackKey = when {
                    chaptersBySource["mangahere"]?.isNotEmpty() == true -> "mangahere"
                    chaptersBySource["mangapill"]?.isNotEmpty() == true -> "mangapill"
                    else -> null
                }
                if (fallbackKey != null) {
                    sourcesList.add(MangaSourceInfo(
                        key = fallbackKey,
                        label = "المصدر 1",
                        language = "ar",  // shown as Arabic to the user
                        chapterCount = defaultChapters.size
                    ))
                }
            }

            Log.d(TAG, "Sources: ${sourcesList.size} visible | default=${sourcesList.firstOrNull()?.key} | chapters=${defaultChapters.size}")

            Result.success(MangaDetails(
                id = id, title = title, cover = cover, description = description,
                author = author, artist = author, status = status, genres = genres,
                chapters = defaultChapters,
                source = "mangadex",
                latestChapter = null,
                sources = sourcesList,
                chaptersBySource = chaptersBySource
            ))
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Map a manga title to a 3asq slug. Both English and Arabic forms are matched
     * because MangaDex sometimes returns only `title.ar` (no `title.en`), which
     * would otherwise produce an empty slug.
     *
     * Patterns are intentionally strict — e.g. we require "hunter x hunter" (full)
     * instead of just "hunter" so unrelated manga like "Hunter" (anime) don't
     * accidentally receive HxH chapters.
     */
    private fun guessSlug(enTitle: String, arTitle: String): String {
        val lower = (enTitle + " " + arTitle).lowercase()
        return when {
            // One Piece
            lower.contains("one piece") || lower.contains("ون بيس") || lower.contains("ونبيس") -> "one-piece"
            // Solo Leveling
            lower.contains("solo leveling") || lower.contains("solo levelling") || lower.contains("سولو ليفلينج") || lower.contains("سولو") -> "solo-leveling"
            // Jujutsu Kaisen — also match Arabic forms
            lower.contains("jujutsu") || lower.contains("جوجوتسو") || lower.contains("جوجتسو") -> "jujutsu-kaisen"
            // Chainsaw Man
            lower.contains("chainsaw") || lower.contains("تشينسو") || lower.contains("chainsaw man") -> "chainsaw-man"
            // Kingdom
            lower.contains("kingdom") && !lower.contains("hearts") -> "kingdom"
            // Hunter x Hunter — STRICT match (must include "x" or "اكس")
            lower.contains("hunter x hunter") || lower.contains("hunter x hunter")
                || lower.contains("هنتر اكس هنتر") || lower.contains("هنتر x هنتر") -> "hunter-x-hunter"
            // Naruto
            lower.contains("naruto") || lower.contains("ناروتو") -> "naruto"
            // Attack on Titan
            lower.contains("attack on titan") || lower.contains("هجوم العمالقة") || lower.contains("هجوم巨人") -> "attack-on-titan"
            // Demon Slayer
            lower.contains("demon slayer") || lower.contains("قاتل الشياطين") || lower.contains("كيميتسو") -> "demon-slayer-kimetsu-no-yaiba"
            // My Hero Academia
            lower.contains("my hero") || lower.contains("بطل الأكاديمية") || lower.contains("أكاديميتي للأبطال") -> "my-hero-academia"
            // Detective Conan
            lower.contains("detective conan") || lower.contains("case closed") || lower.contains("كونان") -> "detective-conan"
            // Fallback: slugify latin chars; if title is purely Arabic (no latin),
            // return empty string so the caller knows to try the search endpoint.
            else -> {
                val slug = enTitle.lowercase().replace(Regex("[^a-z0-9]+"), "-").replace(Regex("^-|-$"), "")
                if (slug.isBlank() && arTitle.isNotBlank()) {
                    // Try slugifying the Arabic title too (3asq sometimes uses Arabic slugs)
                    ""
                } else {
                    slug
                }
            }
        }
    }

    private fun fetch3asqChapters(slug: String): List<MangaChapter>? {
        if (slug.isBlank()) return null
        // Try the Netlify proxy first (fast, JSON)
        val proxyResult = try {
            val req = Request.Builder().url("$ASQ_API/chapters?slug=$slug").header("Accept", "application/json").build()
            proxyClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val root = JsonParser.parseString(body)
                if (!root.isJsonObject) return@use null
                val arr = root.asJsonObject.getAsJsonArray("chapters") ?: return@use null
                if (arr.size() == 0) return@use null
                val result = mutableListOf<MangaChapter>()
                for (i in 0 until arr.size()) {
                    val ch = arr[i].asJsonObject
                    val num = ch.get("number")?.asString ?: continue
                    result.add(MangaChapter(id = "3asq-$slug-$num", number = num, title = "الفصل $num", date = "", source = "3asq"))
                }
                if (result.isEmpty()) null else result
            }
        } catch (e: Exception) { null }

        if (proxyResult != null) return proxyResult

        // Fallback: scrape 3asq.online directly via CORS proxy
        // This bypasses Cloudflare by going through proxy.cors.sh
        return scrape3asqChaptersDirect(slug)
    }

    /**
     * Scrape 3asq.online chapter list directly via a CORS proxy.
     * This is the NEW method that bypasses Cloudflare.
     */
    private fun scrape3asqChaptersDirect(slug: String): List<MangaChapter>? {
        return try {
            val url = "${CORS_PROXY}${ASQ_BASE}/manga/$slug/"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Origin", ASQ_BASE)
                .header("Accept", "text/html")
                .build()
            proxyClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string() ?: return null
                // Find all chapter links: /manga/{slug}/{number}/
                val pattern = java.util.regex.Pattern.compile(
                    "/manga/$slug/(\\d+(?:\\.\\d+)?)/"
                )
                val matcher = pattern.matcher(html)
                val result = mutableListOf<MangaChapter>()
                val seen = mutableSetOf<String>()
                while (matcher.find()) {
                    val num = matcher.group(1) ?: continue
                    if (seen.add(num)) {
                        result.add(MangaChapter(
                            id = "3asq-$slug-$num",
                            number = num,
                            title = "الفصل $num",
                            date = "",
                            source = "3asq"
                        ))
                    }
                }
                // Sort by chapter number descending (newest first)
                result.sortedByDescending { it.number.toFloatOrNull() ?: 0f }
                if (result.isEmpty()) null else result
            }
        } catch (e: Exception) {
            Log.w(TAG, "scrape3asqChaptersDirect($slug) failed: ${e.message}")
            null
        }
    }

    /**
     * Search 3asq by title (used when the slug can't be guessed — e.g. the manga
     * has no English title). Returns chapters from the first matching result.
     */
    private fun search3asqChapters(query: String): List<MangaChapter>? {
        if (query.isBlank()) return null
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val req = Request.Builder().url("$ASQ_API/search?q=$encoded&limit=1").header("Accept", "application/json").build()
            val slug = proxyClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = JsonParser.parseString(body)
                if (!root.isJsonObject) return null
                val arr = root.asJsonObject.getAsJsonArray("results") ?: return null
                if (arr.size() == 0) return null
                arr[0].asJsonObject.get("slug")?.asString
            } ?: return null
            // Got a slug — recurse into fetch3asqChapters
            if (slug.isBlank()) null else fetch3asqChapters(slug)
        } catch (e: Exception) {
            Log.w(TAG, "search3asqChapters($query) failed: ${e.message}")
            null
        }
    }

    private fun getMangaDexChapters(id: String): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        var offset = 0; val limit = 100; var total = Int.MAX_VALUE
        while (offset < total && offset < 5000) {
            try {
                val url = "https://api.mangadex.org/manga/$id/feed?limit=$limit&offset=$offset&translatedLanguage[]=ar&order[chapter]=desc&contentRating[]=safe&contentRating[]=suggestive&contentRating[]=erotica"
                val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json").build()
                // Use proxyClient (shorter timeouts) instead of the direct client —
                // direct calls to api.mangadex.org often time out on mobile networks.
                val resp = proxyClient.newCall(req).execute()
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
                        if (lang == "ar") {  // Arabic ONLY (already filtered by the URL, but double-check)
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
        // Dedupe by chapter number (MangaDex can return multiple Arabic uploads
        // of the same chapter by different groups). Previously this filtered by
        // `it.id.contains("ar")` — but chapter IDs are random UUIDs with no "ar"
        // substring, so that filter always returned null and fell back to first().
        // Since the URL already filters translatedLanguage=ar, every chapter here
        // is Arabic — just take the first per number.
        return chapters.groupBy { it.number }
            .mapValues { (_, l) -> l.first() }
            .values.sortedByDescending { it.number.toFloatOrNull() ?: 0f }
    }

    suspend fun getChapterPages(chapter: MangaChapter): Result<List<ChapterPage>> {
        return try {
            if (chapter.source == "3asq") {
                // Extract slug and chapter number from the chapter id
                // chapter.id format: "3asq-{slug}-{num}" where slug may contain dashes
                // e.g. "3asq-one-piece-1188" → slug="one-piece", num="1188"
                val idWithoutPrefix = chapter.id.removePrefix("3asq-")
                val num = idWithoutPrefix.substringAfterLast("-")
                val slug = idWithoutPrefix.substringBeforeLast("-")
                Log.d(TAG, "3asq pages: slug=$slug, num=$num (from id=${chapter.id})")
                if (slug.isNotBlank() && num.isNotBlank()) {
                    // Try Netlify proxy first
                    val req = Request.Builder().url("$ASQ_API/pages?slug=$slug&chapter=$num").header("Accept", "application/json").build()
                    proxyClient.newCall(req).execute().use { resp ->
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
                    // Fallback: scrape via CORS proxy (bypasses Cloudflare, returns real images)
                    val directPages = scrape3asqPagesDirect(slug, num)
                    if (directPages.isNotEmpty()) return Result.success(directPages)

                    // Last resort: 3asq has deleted many old chapters (e.g. One Piece
                    // chapters 1–1000 return an empty reading-content div). When that
                    // happens, try to find the SAME chapter on MangaDex (source 1) by
                    // searching the 3asq slug as a title and matching the chapter number.
                    val mdPages = fetchMangaDexPagesBySlugAndNumber(slug, num)
                    if (mdPages.isNotEmpty()) return Result.success(mdPages)
                }
                Result.failure(Exception("هذا الفصل غير متوفر على مصدر 3asq (قد يكون محذوفاً). حاول فتح المانجا من مصدر MangaDex (مصدر 1) للحصول على فصول إضافية."))
            } else {
                // MangaDex (مصدر 1): fetch pages from MangaDex at-home server
                val req = Request.Builder().url("https://api.mangadex.org/at-home/server/${chapter.id}").header("User-Agent", UA).header("Accept", "application/json").build()
                proxyClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return Result.failure(Exception("HTTP ${resp.code}"))
                    val body = resp.body?.string() ?: return Result.failure(Exception("Empty"))
                    val root = JsonParser.parseString(body)
                    if (!root.isJsonObject) return Result.failure(Exception("Invalid JSON"))
                    val baseUrl = root.asJsonObject.get("baseUrl")?.asString ?: return Result.failure(Exception("No baseUrl"))
                    val chObj = root.asJsonObject.getAsJsonObject("chapter") ?: return Result.failure(Exception("No chapter"))
                    val hash = chObj.get("hash")?.asString ?: return Result.failure(Exception("No hash"))
                    // Use "data" (full quality) instead of "dataSaver" (compressed/blurry)
                    val ds = chObj.getAsJsonArray("data") ?: chObj.getAsJsonArray("dataSaver") ?: return Result.failure(Exception("No pages"))
                    val pages = mutableListOf<ChapterPage>()
                    for (i in 0 until ds.size()) {
                        val isDataSaver = (chObj.getAsJsonArray("data") == null)
                        val path = if (isDataSaver) "data-saver" else "data"
                        pages.add(ChapterPage(index = i, url = "$baseUrl/$path/$hash/${ds[i].asString}"))
                    }
                    Result.success(pages)
                }
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    /**
     * Cross-source fallback: when 3asq has no pages for a chapter (old chapters
     * are deleted on 3asq.online), search MangaDex for the same manga by treating
     * the 3asq slug as a title, then fetch the matching Arabic chapter's pages.
     *
     * Flow:
     *   1. Search MangaDex: GET /manga?title={slug-as-words}&availableTranslatedLanguage[]=ar
     *   2. Take the first result's id
     *   3. Fetch its Arabic feed: GET /manga/{id}/feed?translatedLanguage[]=ar
     *   4. Find a chapter whose number matches `chapterNumber`
     *   5. Fetch pages: GET /at-home/server/{chapterId}
     *
     * Returns empty list on any failure (the caller will then show the error).
     */
    private fun fetchMangaDexPagesBySlugAndNumber(slug: String, chapterNumber: String): List<ChapterPage> {
        return try {
            // 1. Search MangaDex by title (slug with dashes → spaces, e.g. "one-piece" → "one piece")
            val titleQuery = java.net.URLEncoder.encode(slug.replace("-", " ").trim(), "UTF-8")
            val searchUrl = "https://api.mangadex.org/manga?title=$titleQuery&limit=5&availableTranslatedLanguage[]=ar&hasAvailableChapters=true&order[relevance]=desc"
            val searchReq = Request.Builder().url(searchUrl).header("User-Agent", UA).header("Accept", "application/json").build()
            var mangaId: String? = null
            proxyClient.newCall(searchReq).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val root = JsonParser.parseString(body).asJsonObject
                val data = root.getAsJsonArray("data") ?: return emptyList()
                if (data.size() == 0) return emptyList()
                mangaId = data[0].asJsonObject.get("id")?.asString ?: return emptyList()
            }
            if (mangaId.isNullOrEmpty()) return emptyList()
            Log.d(TAG, "fallback: found MangaDex manga $mangaId for slug '$slug'")

            // 2. Fetch Arabic chapters feed and find one matching the chapter number
            var mdChapterId: String? = null
            val feedUrl = "https://api.mangadex.org/manga/$mangaId/feed?limit=100&translatedLanguage[]=ar&order[chapter]=desc"
            val feedReq = Request.Builder().url(feedUrl).header("User-Agent", UA).header("Accept", "application/json").build()
            proxyClient.newCall(feedReq).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val root = JsonParser.parseString(body).asJsonObject
                val data = root.getAsJsonArray("data") ?: return emptyList()
                // Try exact match first, then "starts with" (e.g. "1188" matches "1188.5" as a fallback)
                var exact: String? = null
                var startsWith: String? = null
                for (i in 0 until data.size()) {
                    try {
                        val ch = data[i].asJsonObject
                        val attrs = ch.getAsJsonObject("attributes") ?: continue
                        val num = attrs.get("chapter")?.asString ?: continue
                        val chId = ch.get("id").asString
                        if (num == chapterNumber) { exact = chId; break }
                        if (startsWith == null && num.startsWith(chapterNumber)) startsWith = chId
                    } catch (e: Exception) {}
                }
                mdChapterId = exact ?: startsWith
            }
            if (mdChapterId.isNullOrEmpty()) {
                Log.d(TAG, "fallback: no MangaDex Arabic chapter $chapterNumber for '$slug'")
                return emptyList()
            }

            // 3. Fetch pages from MangaDex at-home
            val pagesReq = Request.Builder().url("https://api.mangadex.org/at-home/server/$mdChapterId").header("User-Agent", UA).header("Accept", "application/json").build()
            val pages = mutableListOf<ChapterPage>()
            proxyClient.newCall(pagesReq).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val root = JsonParser.parseString(body).asJsonObject
                val baseUrl = root.get("baseUrl")?.asString ?: return emptyList()
                val chObj = root.getAsJsonObject("chapter") ?: return emptyList()
                val hash = chObj.get("hash")?.asString ?: return emptyList()
                val ds = chObj.getAsJsonArray("data") ?: chObj.getAsJsonArray("dataSaver") ?: return emptyList()
                val isDataSaver = (chObj.getAsJsonArray("data") == null)
                val path = if (isDataSaver) "data-saver" else "data"
                for (i in 0 until ds.size()) {
                    pages.add(ChapterPage(index = i, url = "$baseUrl/$path/$hash/${ds[i].asString}"))
                }
            }
            Log.d(TAG, "fallback: got ${pages.size} pages from MangaDex for '$slug' ch $chapterNumber")
            pages
        } catch (e: Exception) {
            Log.w(TAG, "fetchMangaDexPagesBySlugAndNumber failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Look up the MangaPill chapter URL for the given chapter number (using the
     * cache populated in getMangaDetails), then fetch its pages. Returns empty
     * list if no MangaPill data is available for this manga.
     */
    /**
     * Scrape 3asq.online chapter pages directly via CORS proxy.
     * Bypasses Cloudflare by going through proxy.cors.sh.
     * Returns Arabic manga pages — this is the primary method now.
     */
    private fun scrape3asqPagesDirect(slug: String, chapterNum: String): List<ChapterPage> {
        return try {
            val url = "${CORS_PROXY}${ASQ_BASE}/manga/$slug/$chapterNum/"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Origin", ASQ_BASE)
                .header("Accept", "text/html")
                .build()
            proxyClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val html = resp.body?.string() ?: return emptyList()
                // Match 3asq.online (not .pro) image URLs
                val pattern = java.util.regex.Pattern.compile(
                    "(?:src|data-src)=\"\\s*(https?://3asq\\.[a-z]+/wp-content/uploads/WP-manga/data/[^\"]+\\.(?:jpg|jpeg|png|webp))\""
                )
                val matcher = pattern.matcher(html)
                val pages = mutableListOf<ChapterPage>()
                val seen = mutableSetOf<String>()
                var index = 0
                while (matcher.find()) {
                    var imgUrl = matcher.group(1) ?: continue
                    imgUrl = imgUrl.trim()
                    // Return DIRECT image URL — Glide will load it
                    if (seen.add(imgUrl)) {
                        pages.add(ChapterPage(index = index, url = imgUrl))
                        index++
                    }
                }
                Log.d(TAG, "scrape3asqPagesDirect: found ${pages.size} pages")
                pages
            }
        } catch (e: Exception) {
            Log.w(TAG, "scrape3asqPagesDirect($slug, $chapterNum) failed: ${e.message}")
            emptyList()
        }
    }

    private fun fetchMangaPillPagesForChapter(chapter: MangaChapter): List<ChapterPage> {
        // We need the manga id to look up the cache. 3asq chapter ids are
        // "3asq-{slug}-{num}" — we don't have the manga's MangaDex id here.
        // So we look through all cached entries; the chapter number is the key.
        val num = chapter.number
        for ((_, urlMap) in mpChapterUrlCache) {
            val urlPath = urlMap[num] ?: urlMap[num.trimStart('0')] ?: continue
            val urls = MangaPillClient.fetchChapterPages(urlPath)
            if (urls.isNotEmpty()) {
                return urls.mapIndexed { i, url -> ChapterPage(index = i, url = url) }
            }
        }
        return emptyList()
    }

    suspend fun getMangaDetailsAllLanguages(id: String): Result<MangaDetails> = getMangaDetails(id)
    suspend fun get3asqChapters(slug: String): Result<List<MangaChapter>> = Result.failure(Exception("N/A"))

    private fun formatDate(iso: String): String {
        if (iso.isEmpty()) return ""
        return try { val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US); sdf.timeZone = TimeZone.getTimeZone("UTC"); val d = sdf.parse(iso) ?: return iso.take(10); SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d) } catch (e: Exception) { iso.take(10) }
    }
}
