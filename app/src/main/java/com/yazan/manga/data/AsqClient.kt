package com.yazan.manga.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * AsqClient — Direct scraper for 3asq.online (Arabic manga source).
 *
 * All network requests hit https://3asq.online directly from the phone using a
 * desktop browser User-Agent. HTML is parsed using regex (no Jsoup dependency).
 *
 * URL patterns:
 *  - Manga page:  https://3asq.online/manga/{slug}/
 *  - Chapter:     https://3asq.online/manga/{slug}/{chapter}/
 *  - Search:      https://3asq.online/?s={query}&post_type=wp-manga
 *  - Listing:     https://3asq.online/page/{page}/
 */
object AsqClient {

    const val BASE_URL = "https://3asq.online"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // =============================================================
    //  Public API
    // =============================================================

    /** Latest manga listed on the homepage / page listing. */
    fun fetchLatest(page: Int): List<MangaListItem> {
        val url = if (page <= 1) BASE_URL else "$BASE_URL/page/$page/"
        val html = get(url) ?: return emptyList()
        return parseListingCards(html)
    }

    /** Popular manga — derived from the homepage "trending" widgets & top-rated cards. */
    fun fetchPopular(page: Int): List<MangaListItem> {
        // 3asq does not expose a real "popular" endpoint. We use the homepage
        // (slider + main listing) for page 1, then `/page/N/` for pagination.
        val url = if (page <= 1) BASE_URL else "$BASE_URL/page/$page/"
        val html = get(url) ?: return emptyList()
        val items = mutableListOf<MangaListItem>()
        items += parseSliderItems(html)
        items += parseListingCards(html)
        return items.distinctBy { it.id }
    }

    /** Search 3asq via the WordPress search endpoint. */
    fun search(query: String): List<MangaListItem> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$BASE_URL/?s=$encoded&post_type=wp-manga"
        val html = get(url) ?: return emptyList()
        return parseSearchResults(html)
    }

    /** Full manga details from the manga page. */
    fun fetchMangaDetails(slug: String): MangaDetails? {
        val html = get("$BASE_URL/manga/$slug/") ?: return null

        val title = extractTitle(html)
        val cover = extractCover(html)
        val description = extractDescription(html)
        val author = extractAuthor(html)
        val artist = extractArtist(html)
        val status = extractStatus(html)
        val genres = extractGenres(html)
        val rating = extractRating(html)
        val latestChapter = extractLatestChapter(html)

        var chapters = if (latestChapter > 0) {
            // Generate 1..latest chapters (descending — newest first).
            (latestChapter downTo 1).map { num ->
                MangaChapter(
                    id = "3asq-${slug}-$num",
                    number = num.toString(),
                    title = "الفصل $num",
                    date = "",
                    source = MangaSource.ASQ.value,
                    externalUrl = "$BASE_URL/manga/$slug/$num/"
                )
            }
        } else {
            // Fallback 1: scrape whatever chapter links appear in the page.
            parseChaptersFromHtml(html, slug)
        }

        // Fallback 2: if no chapters found, try the AJAX endpoint used by
        // the WordPress Manga theme (Madara). Many manga pages load their
        // chapter list dynamically via AJAX, so the links aren't in the HTML.
        if (chapters.isEmpty()) {
            val ajaxHtml = get("$BASE_URL/manga/$slug/ajax/chapters/")
            if (ajaxHtml != null) {
                chapters = parseChaptersFromHtml(ajaxHtml, slug)
            }
        }

        return MangaDetails(
            id = slug,
            title = title.ifEmpty { slug },
            cover = cover,
            description = description.ifEmpty { "لا يوجد وصف" },
            author = author,
            artist = artist,
            status = status,
            genres = genres,
            chapters = chapters,
            source = MangaSource.ASQ.value,
            latestChapter = latestChapter.takeIf { it > 0 }?.toString(),
            rating = rating
        )
    }

    /** Page images from a chapter URL. */
    fun fetchChapterPages(slug: String, chapter: String): List<ChapterPage> {
        val url = "$BASE_URL/manga/$slug/$chapter/"
        val html = get(url) ?: return emptyList()
        return parseChapterImages(html)
    }

    // =============================================================
    //  HTTP helper
    // =============================================================

    private fun get(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                )
                .header("Accept-Language", "ar,en-US;q=0.9,en;q=0.8")
                .header("Accept-Encoding", "gzip")
                .header("Referer", BASE_URL)
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                body.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    // =============================================================
    //  Parsers (regex based — no Jsoup)
    // =============================================================

    /** Parse "page-listing-item" cards from the homepage / listing pages. */
    private fun parseListingCards(html: String): List<MangaListItem> {
        val items = mutableListOf<MangaListItem>()

        // Each card is a <div class="page-item-detail manga ..."> ... </div><!-- .page-item-detail -->
        val cardPattern = Pattern.compile(
            "<div\\s+class=\"page-item-detail[\\s\\S]*?</div>\\s*<!--\\s*\\.page-item-detail\\s*-->",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = cardPattern.matcher(html)
        while (matcher.find()) {
            val block = matcher.group()
            val item = parseCardBlock(block) ?: continue
            items.add(item)
        }

        // Fallback: some pages use the simpler "item-thumb" structure.
        if (items.isEmpty()) {
            val simpleCardPattern = Pattern.compile(
                "<div\\s+id=\"manga-item-\\d+\"[\\s\\S]*?</div>\\s*</div>\\s*</div>\\s*</div>",
                Pattern.CASE_INSENSITIVE
            )
            val m2 = simpleCardPattern.matcher(html)
            while (m2.find()) {
                parseCardBlock(m2.group())?.let { items.add(it) }
            }
        }

        return items.distinctBy { it.id }
    }

    /** Parse featured slider items. */
    private fun parseSliderItems(html: String): List<MangaListItem> {
        val items = mutableListOf<MangaListItem>()

        // slider items: <a href="https://3asq.online/manga/{slug}/"> ... <img src="...">
        val sliderPattern = Pattern.compile(
            "<div\\s+class=\"slider__thumb_item[\\s\\S]*?<a\\s+href=\"(https?://3asq\\.pro/manga/([\\w-]+)/?)[^>]*>[\\s\\S]*?<img[^>]+src=\"([^\"]+)\"[\\s\\S]*?<div\\s+class=\"post-title[\\s\\S]*?<a[^>]*>([^<]+)</a>",
            Pattern.CASE_INSENSITIVE
        )
        val m = sliderPattern.matcher(html)
        while (m.find()) {
            val slug = m.group(2) ?: continue
            val cover = m.group(3) ?: ""
            val title = (m.group(4) ?: slug).trim()
            items.add(
                MangaListItem(
                    id = slug,
                    title = title,
                    cover = cover,
                    source = MangaSource.ASQ.value
                )
            )
        }
        return items
    }

    private fun parseCardBlock(block: String): MangaListItem? {
        // Extract manga URL → slug
        val urlPattern = Pattern.compile(
            "href=\"https?://3asq\\.pro/manga/([\\w-]+)/?\"",
            Pattern.CASE_INSENSITIVE
        )
        val urlMatcher = urlPattern.matcher(block)
        if (!urlMatcher.find()) return null
        val slug = urlMatcher.group(1) ?: return null

        // Extract title (prefer the inner <a> text after the last /manga/{slug}/ link)
        val titlePattern = Pattern.compile(
            "<div\\s+class=\"post-title[\\s\\S]*?<a[^>]*>([^<]+)</a>",
            Pattern.CASE_INSENSITIVE
        )
        val title = titlePattern.matcher(block).let { m ->
            if (m.find()) m.group(1)?.trim()?.takeIf { it.isNotEmpty() } else slug
        } ?: slug

        // Extract cover image src (first <img> in the block)
        val imgPattern = Pattern.compile(
            "<img[^>]+src=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
        )
        val cover = imgPattern.matcher(block).let { m ->
            if (m.find()) m.group(1) ?: "" else ""
        }

        // Try to extract rating (optional)
        val ratingPattern = Pattern.compile(
            "<span\\s+class=\"score[^>]*>([0-9.]+)</span>",
            Pattern.CASE_INSENSITIVE
        )
        val rating = ratingPattern.matcher(block).let { m ->
            if (m.find()) m.group(1)?.toDoubleOrNull() else null
        }

        // Try to extract views count (optional)
        val viewsPattern = Pattern.compile(
            "<i\\s+class=\"fa\\s+fa-eye\"></i>\\s*([0-9,]+)",
            Pattern.CASE_INSENSITIVE
        )
        val views = viewsPattern.matcher(block).let { m ->
            if (m.find()) m.group(1)?.replace(",", "")?.let { v -> "${v} مشاهدة" } else null
        }

        // Try to extract the latest chapter number (optional)
        val statusPattern = Pattern.compile(
            "الحالة\\s*</h5>[\\s\\S]*?<div\\s+class=\"summary-content\">\\s*([^<]+)",
            Pattern.CASE_INSENSITIVE
        )
        val status = statusPattern.matcher(block).let { m ->
            if (m.find()) m.group(1)?.trim() else null
        }

        return MangaListItem(
            id = slug,
            title = title,
            cover = cover,
            source = MangaSource.ASQ.value,
            rating = rating,
            views = views,
            status = status
        )
    }

    /** Parse search results page (c-tabs-item__content blocks). */
    private fun parseSearchResults(html: String): List<MangaListItem> {
        val items = mutableListOf<MangaListItem>()

        val blockPattern = Pattern.compile(
            "<div\\s+class=\"row\\s+c-tabs-item__content\">([\\s\\S]*?)</div>\\s*</div>\\s*</div>",
            Pattern.CASE_INSENSITIVE
        )
        val m = blockPattern.matcher(html)
        while (m.find()) {
            val block = m.group() ?: continue
            val urlP = Pattern.compile(
                "href=\"https?://3asq\\.pro/manga/([\\w-]+)/?\"",
                Pattern.CASE_INSENSITIVE
            )
            val slug = urlP.matcher(block).let { mm -> if (mm.find()) mm.group(1) else null } ?: continue

            val titleP = Pattern.compile(
                "<div\\s+class=\"post-title\">[\\s\\S]*?<a[^>]*>([^<]+)</a>",
                Pattern.CASE_INSENSITIVE
            )
            val title: String = run {
                val mm = titleP.matcher(block)
                if (mm.find()) mm.group(1)?.trim() ?: slug else slug
            }

            val coverP = Pattern.compile(
                "<img[^>]+src=\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE
            )
            val cover: String = run {
                val mm = coverP.matcher(block)
                if (mm.find()) mm.group(1) ?: "" else ""
            }

            items.add(
                MangaListItem(
                    id = slug,
                    title = title,
                    cover = cover,
                    source = MangaSource.ASQ.value
                )
            )
        }

        // Fallback: simple item-thumb / tab-thumb blocks
        if (items.isEmpty()) {
            val simpleP = Pattern.compile(
                "<div\\s+class=\"(?:item-thumb|tab-thumb)[^>]*>[\\s\\S]*?<a\\s+href=\"https?://3asq\\.pro/manga/([\\w-]+)/?\"[^>]*title=\"([^\"]+)\"[\\s\\S]*?<img[^>]+src=\"([^\"]+)\"",
                Pattern.CASE_INSENSITIVE
            )
            val mm = simpleP.matcher(html)
            while (mm.find()) {
                items.add(
                    MangaListItem(
                        id = mm.group(1) ?: continue,
                        title = mm.group(2) ?: "",
                        cover = mm.group(3) ?: "",
                        source = MangaSource.ASQ.value
                    )
                )
            }
        }

        return items.distinctBy { it.id }
    }

    private fun parseChaptersFromHtml(html: String, slug: String): List<MangaChapter> {
        val list = mutableListOf<MangaChapter>()
        // Look for chapter links in various formats:
        //  1. https://3asq.online/manga/{slug}/{num}/
        //  2. https://3asq.online/manga/{slug}/{chapter-slug}/
        //  3. <li class="wp-manga-chapter"> ... <a href="...">Chapter X</a>
        // We extract whatever comes after /manga/{slug}/ as the chapter identifier.
        val pattern = Pattern.compile(
            "href=\"https?://3asq\\.pro/manga/" + Pattern.quote(slug) + "/([^\"]+)/\"",
            Pattern.CASE_INSENSITIVE
        )
        val m = pattern.matcher(html)
        val seen = mutableSetOf<String>()
        while (m.find()) {
            val raw = m.group(1) ?: continue
            // Skip non-chapter URLs (like the manga page itself, reviews, etc.)
            if (raw == "feed" || raw == "reviews" || raw.startsWith("?")) continue
            if (!seen.add(raw)) continue
            // Use the raw identifier as the chapter number/title
            val displayNum = raw.toFloatOrNull()?.toInt()?.toString() ?: raw
            list.add(
                MangaChapter(
                    id = "3asq-${slug}-$raw",
                    number = displayNum,
                    title = "الفصل $displayNum",
                    date = "",
                    source = MangaSource.ASQ.value,
                    externalUrl = "$BASE_URL/manga/$slug/$raw/"
                )
            )
        }
        // Sort by number descending (newest first) — numeric chapters first
        return list.sortedByDescending { it.number.toFloatOrNull() ?: 0f }
    }

    private fun parseChapterImages(html: String): List<ChapterPage> {
        val pages = mutableListOf<ChapterPage>()
        // <img id="image-0" src=" ... " class="wp-manga-chapter-img">
        val pattern = Pattern.compile(
            "<img[^>]+src=\"\\s*([^\"]+)\\s*\"[^>]+class=\"[^\"]*wp-manga-chapter-img[^\"]*\"",
            Pattern.CASE_INSENSITIVE
        )
        val m = pattern.matcher(html)
        var idx = 0
        while (m.find()) {
            val url = m.group(1)?.trim() ?: continue
            if (url.isEmpty()) continue
            pages.add(ChapterPage(index = idx++, url = url))
        }

        // Fallback: any wp-manga-chapter-img image without strict ordering
        if (pages.isEmpty()) {
            val fallback = Pattern.compile(
                "class=\"[^\"]*wp-manga-chapter-img[^\"]*\"[^>]+src=\"\\s*([^\"]+)\\s*\"",
                Pattern.CASE_INSENSITIVE
            )
            val fm = fallback.matcher(html)
            var i = 0
            while (fm.find()) {
                val url = fm.group(1)?.trim() ?: continue
                pages.add(ChapterPage(index = i++, url = url))
            }
        }
        return pages
    }

    // =============================================================
    //  Field extractors for the manga details page
    // =============================================================

    private fun extractTitle(html: String): String {
        // <div class="post-title"> ... <h1> TITLE </h1>
        val p = Pattern.compile(
            "<div\\s+class=\"post-title\"[\\s\\S]*?<h1[^>]*>([\\s\\S]*?)</h1>",
            Pattern.CASE_INSENSITIVE
        )
        val m = p.matcher(html)
        if (m.find()) {
            return stripTags(m.group(1) ?: "").trim()
        }
        // Fallback: <title>TITLE - 3asq</title>
        val tp = Pattern.compile("<title>([^<]+)</title>", Pattern.CASE_INSENSITIVE)
        val tm = tp.matcher(html)
        if (tm.find()) {
            val t = tm.group(1) ?: ""
            return t.substringBefore(" - ").substringBefore(" – ").trim()
        }
        return ""
    }

    private fun extractCover(html: String): String {
        // Inside <div class="summary_image"> there's an <img src="...">
        val p = Pattern.compile(
            "<div\\s+class=\"summary_image\"[\\s\\S]*?<img[^>]+src=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
        )
        val m = p.matcher(html)
        if (m.find()) return m.group(1)?.trim() ?: ""
        // Fallback: first og:image meta tag
        val og = Pattern.compile(
            "<meta[^>]+property=\"og:image\"[^>]+content=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
        )
        val om = og.matcher(html)
        if (om.find()) return om.group(1)?.trim() ?: ""
        return ""
    }

    private fun extractDescription(html: String): String {
        // <div class="manga-excerpt summary__content ..."> ... </div>
        val p = Pattern.compile(
            "<div\\s+class=\"[^\"]*manga-excerpt[^\"]*\"[^>]*>([\\s\\S]*?)</div>",
            Pattern.CASE_INSENSITIVE
        )
        val m = p.matcher(html)
        if (m.find()) {
            return stripTags(m.group(1) ?: "").trim()
        }
        // Fallback: og:description
        val og = Pattern.compile(
            "<meta[^>]+property=\"og:description\"[^>]+content=\"([^\"]+)\"",
            Pattern.CASE_INSENSITIVE
        )
        val om = og.matcher(html)
        if (om.find()) return om.group(1)?.trim() ?: ""
        return ""
    }

    private fun extractAuthor(html: String): String {
        return extractLabeledField(html, "الكاتب")
            .ifEmpty { extractLabeledField(html, "Author") }
    }

    private fun extractArtist(html: String): String {
        return extractLabeledField(html, "الرسام")
            .ifEmpty { extractLabeledField(html, "Artist") }
    }

    private fun extractStatus(html: String): String {
        return extractLabeledField(html, "الحالة")
            .ifEmpty { extractLabeledField(html, "Status") }
            .ifEmpty { "مستمرة" }
    }

    private fun extractGenres(html: String): List<String> {
        val list = mutableListOf<String>()
        // Find the genres-content block and extract all <a> texts
        val blockP = Pattern.compile(
            "genres-content[\\s\\S]*?</div>",
            Pattern.CASE_INSENSITIVE
        )
        val bm = blockP.matcher(html)
        val block = if (bm.find()) bm.group() ?: "" else ""
        if (block.isEmpty()) return list

        val aP = Pattern.compile(
            "<a[^>]*>([^<]+)</a>",
            Pattern.CASE_INSENSITIVE
        )
        val am = aP.matcher(block)
        while (am.find()) {
            val g = am.group(1)?.trim() ?: continue
            if (g.isNotEmpty()) list.add(g)
        }
        return list
    }

    private fun extractRating(html: String): Double? {
        // <span class="score font-meta total_votes">4.7</span>
        val p = Pattern.compile(
            "<span\\s+class=\"score[^>]*>([0-9.]+)</span>",
            Pattern.CASE_INSENSITIVE
        )
        val m = p.matcher(html)
        return if (m.find()) m.group(1)?.toDoubleOrNull() else null
    }

    /** Extract latest chapter number from the "btn-read-first" link. */
    private fun extractLatestChapter(html: String): Int {
        // <a href="https://3asq.online/manga/{slug}/{num}/" id="btn-read-first" ...>
        // Note: href appears before id in the actual HTML
        val p = Pattern.compile(
            "href=\"[^\"]*?/(\\d+(?:\\.\\d+)?)/?\"[^>]*id=\"btn-read-first\"",
            Pattern.CASE_INSENSITIVE
        )
        val m = p.matcher(html)
        if (m.find()) {
            val raw = m.group(1) ?: return 0
            val num = raw.toFloatOrNull()?.toInt() ?: raw.toIntOrNull() ?: 0
            return num
        }
        // Fallback 1: try Chapters field
        val cp = Pattern.compile(
            ">Chapters</h5>[\\s\\S]*?<div\\s+class=\"summary-content\">\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        val cm = cp.matcher(html)
        if (cm.find()) {
            return cm.group(1)?.toIntOrNull() ?: 0
        }
        // Fallback 2: find the highest numeric chapter link on the page
        // (some manga don't have btn-read-first but do have chapter links).
        val chapterPattern = Pattern.compile(
            "href=\"https?://3asq\\.pro/manga/[\\w-]+/(\\d+(?:\\.\\d+)?)/\"",
            Pattern.CASE_INSENSITIVE
        )
        val chapterMatcher = chapterPattern.matcher(html)
        var maxChapter = 0
        while (chapterMatcher.find()) {
            val raw = chapterMatcher.group(1) ?: continue
            val num = raw.toFloatOrNull()?.toInt() ?: raw.toIntOrNull() ?: continue
            if (num > maxChapter) maxChapter = num
        }
        return maxChapter
    }

    /** Extract a labeled post-content_item field by its Arabic heading. */
    private fun extractLabeledField(html: String, label: String): String {
        val p = Pattern.compile(
            "<h5>\\s*" + Pattern.quote(label) + "\\s*</h5>[\\s\\S]*?<div\\s+class=\"summary-content\">\\s*([\\s\\S]*?)</div>",
            Pattern.CASE_INSENSITIVE
        )
        val m = p.matcher(html)
        if (m.find()) {
            return stripTags(m.group(1) ?: "").trim()
        }
        return ""
    }

    /** Strip HTML tags and decode common entities. */
    private fun stripTags(s: String): String {
        return s
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
