package com.yazan.manga.data

import com.google.gson.annotations.SerializedName

/**
 * Supported manga sources. The native app is 3asq-only (Arabic).
 */
enum class MangaSource(val value: String) {
    ASQ("3asq");

    companion object {
        fun fromValue(v: String?): MangaSource = entries.firstOrNull { it.value == v } ?: ASQ
    }
}

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
    val id: String,                // "3asq-{slug}-{number}"
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
    val rating: Double? = null
)

// Page in a chapter
data class ChapterPage(
    val index: Int,
    val url: String
)
