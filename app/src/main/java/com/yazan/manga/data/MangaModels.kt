package com.yazan.manga.data

import com.google.gson.annotations.SerializedName

// Manga list item (from /api/manga/list)
data class MangaListItem(
    val id: String,
    val title: String,
    val cover: String,
    val source: String = "3asq",
    val rating: Double? = null,
    val views: String? = null,
    val status: String? = null
)

// Chapter (from /api/manga/details -> chapters[])
data class MangaChapter(
    val id: String,
    val number: String,
    val title: String,
    val date: String,
    val source: String = "3asq",
    val externalUrl: String? = null
)

// Full manga details (from /api/manga/details)
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
    val source: String = "3asq",
    val latestChapter: String? = null
)

// Page in a chapter (from /api/manga/chapter/pages)
data class ChapterPage(
    val index: Int,
    val url: String
)

// API response wrappers
data class MangaListResponse(
    val items: List<MangaListItem>,
    val error: String? = null
)

data class ChapterPagesResponse(
    val pages: List<ChapterPage>,
    val error: String? = null
)
