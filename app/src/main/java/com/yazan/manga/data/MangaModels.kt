package com.yazan.manga.data

import com.google.gson.annotations.SerializedName

// ====== UI models (what adapters consume) ======

data class MangaListItem(
    val id: String,
    val title: String,
    val cover: String,
    val source: String = "mangadex",
    val rating: Double? = null,
    val views: String? = null,
    val status: String? = null
)

data class MangaChapter(
    val id: String,
    val number: String,
    val title: String,
    val date: String,
    val source: String = "mangadex",
    val externalUrl: String? = null,
    val groupName: String? = null
)

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
    val source: String = "mangadex",
    val latestChapter: String? = null
)

data class ChapterPage(
    val index: Int,
    val url: String
)

// ====== MangaDex API response models ======

data class MangaDexListResponse(
    val result: String,
    val response: String? = null,
    val data: List<MangaDexManga>,
    val limit: Int = 0,
    val offset: Int = 0,
    val total: Int = 0
)

data class MangaDexMangaResponse(
    val result: String,
    val data: MangaDexManga
)

data class MangaDexManga(
    val id: String,
    val type: String,
    val attributes: MangaDexMangaAttributes,
    val relationships: List<MangaDexRelationship> = emptyList()
)

data class MangaDexMangaAttributes(
    val title: Map<String, String> = emptyMap(),
    val altTitles: List<Map<String, String>> = emptyList(),
    val description: Map<String, String> = emptyMap(),
    val status: String = "",
    val year: Int? = null,
    val tags: List<MangaDexTag> = emptyList(),
    val lastVolume: String? = null,
    val lastChapter: String? = null
)

data class MangaDexTag(
    val id: String,
    val attributes: MangaDexTagAttributes
)

data class MangaDexTagAttributes(
    val name: Map<String, String> = emptyMap()
)

data class MangaDexRelationship(
    val id: String,
    val type: String,
    val attributes: MangaDexRelationshipAttributes? = null
)

data class MangaDexRelationshipAttributes(
    val name: String? = null,
    val fileName: String? = null,
    val locale: String? = null
)

data class MangaDexChaptersResponse(
    val result: String,
    val data: List<MangaDexChapter>,
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0
)

data class MangaDexChapter(
    val id: String,
    val type: String,
    val attributes: MangaDexChapterAttributes,
    val relationships: List<MangaDexRelationship> = emptyList()
)

data class MangaDexChapterAttributes(
    val volume: String? = null,
    val chapter: String? = null,
    val title: String? = null,
    val translatedLanguage: String? = null,
    val externalUrl: String? = null,
    val publishAt: String? = null,
    val readableAt: String? = null,
    val createdAt: String? = null,
    val pages: Int = 0
)

data class MangaDexChapterPagesResponse(
    val result: String,
    val baseUrl: String,
    val chapter: MangaDexAtHomeChapter
)

data class MangaDexAtHomeChapter(
    val hash: String,
    val data: List<String>,
    val dataSaver: List<String>
)
