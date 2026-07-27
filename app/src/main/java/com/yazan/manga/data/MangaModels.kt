package com.yazan.manga.data

import com.google.gson.annotations.SerializedName

/**
 * Supported manga sources. Each source is exposed to the user under a generic
 * label ("المصدر 1", "المصدر 2", ...) so we never reveal the real upstream
 * provider — this keeps the door open for swapping sources without changing
 * the user-facing UX.
 */
enum class MangaSource(val value: String) {
    ASQ("3asq"),
    MANGADEX("mangadex"),
    MANGAPILL("mangapill"),
    MANGAHERE("mangahere");

    companion object {
        fun fromValue(v: String?): MangaSource = entries.firstOrNull { it.value == v } ?: MANGADEX
    }
}

/**
 * Describes one available source for a manga:
 *  - `key` is the internal source identifier (MangaSource.value)
 *  - `label` is the user-facing Arabic label ("المصدر 1", "المصدر 2", ...)
 *  - `language` is "ar" or "en"
 *  - `chapterCount` is the number of chapters available from this source
 */
data class MangaSourceInfo(
    val key: String,
    val label: String,
    val language: String,
    val chapterCount: Int
)

// Manga list item (homepage / search results / popular)
data class MangaListItem(
    val id: String,                // 3asq slug
    val title: String,
    val cover: String,
    val source: String = MangaSource.ASQ.value,
    val rating: Double? = null,
    val views: String? = null,
    val status: String? = null
)

// Single chapter
data class MangaChapter(
    val id: String,                // "3asq-{slug}-{number}" or MangaDex UUID or MangaPill URL path
    val number: String,
    val title: String,
    val date: String,
    val source: String = MangaSource.ASQ.value,
    val externalUrl: String? = null
)

// Full manga details (from manga page)
data class MangaDetails(
    val id: String,
    val title: String,
    val cover: String,
    val description: String,
    val author: String,
    val artist: String,
    val status: String,
    val genres: List<String>,
    val chapters: List<MangaChapter>,
    val source: String = MangaSource.ASQ.value,
    val latestChapter: String? = null,
    val rating: Double? = null,
    /** Available sources for this manga. Used by the details screen to let the
     *  user pick which source to read from. The first entry is the default. */
    val sources: List<MangaSourceInfo> = emptyList(),
    /** Chapters grouped by source key. Looked up by the source chip on the
     *  details screen when the user switches sources. */
    val chaptersBySource: Map<String, List<MangaChapter>> = emptyMap()
)

// Page in a chapter
data class ChapterPage(
    val index: Int,
    val url: String
)
