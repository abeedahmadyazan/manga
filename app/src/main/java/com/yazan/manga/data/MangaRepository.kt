package com.yazan.manga.data

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Repository that calls MangaDex API directly.
 * - Paginates chapters (for One Piece with 1100+ chapters)
 * - Returns Result<T> for clean error handling
 */
class MangaRepository {

    private val api = MangaApiClient.api

    // ===== Latest Arabic manga =====
    suspend fun getLatestManga(page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val offset = (page - 1) * 20
            val response = api.getLatestArabic(offset = offset)
            Result.success(response.data.map { it.toListItem() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ===== Popular Arabic manga =====
    suspend fun getPopularManga(page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val offset = (page - 1) * 20
            val response = api.getPopularArabic(offset = offset)
            Result.success(response.data.map { it.toListItem() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ===== Arabic-only manga (latest Arabic translations) =====
    suspend fun getArabicManga(page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val offset = (page - 1) * 20
            val response = api.getLatestArabic(offset = offset)
            Result.success(response.data.map { it.toListItem() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ===== English-only manga (latest English translations) =====
    suspend fun getEnglishManga(page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val offset = (page - 1) * 20
            val response = api.getLatestEnglish(offset = offset)
            Result.success(response.data.map { it.toListItem() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ===== Search Arabic manga =====
    suspend fun searchManga(query: String, page: Int = 1): Result<List<MangaListItem>> {
        return try {
            val offset = (page - 1) * 20
            val response = api.searchArabic(title = query, offset = offset)
            Result.success(response.data.map { it.toListItem() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ===== Manga details with chapters =====
    suspend fun getMangaDetails(id: String): Result<MangaDetails> {
        return try {
            val mangaResponse = api.getMangaDetails(id)
            val manga = mangaResponse.data

            val allChapters = mutableListOf<MangaDexChapter>()
            var offset = 0
            val limit = 100
            var total = Int.MAX_VALUE

            while (offset < total && offset < 2000) {
                val page = api.getMangaChapters(id, limit = limit, offset = offset)
                if (page.data.isEmpty()) break
                allChapters.addAll(page.data)
                total = page.total
                offset += limit
                if (page.data.size < limit) break
            }

            val deduped = allChapters
                .groupBy { it.attributes.chapter ?: it.id }
                .mapValues { (_, list) -> list.maxByOrNull { it.attributes.publishAt ?: "" }!! }
                .values
                .sortedByDescending { it.attributes.chapter?.toFloatOrNull() ?: 0f }

            val chapters = deduped.map { it.toChapter() }
            Result.success(manga.toDetails(chapters))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ===== Chapter pages =====
    suspend fun getChapterPages(chapter: MangaChapter): Result<List<ChapterPage>> {
        return try {
            val response = api.getChapterPages(chapter.id)
            val baseUrl = response.baseUrl
            val hash = response.chapter.hash
            val pages = response.chapter.dataSaver.mapIndexed { index, fileName ->
                ChapterPage(
                    index = index,
                    url = "$baseUrl/data-saver/$hash/$fileName"
                )
            }
            Result.success(pages)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ===== Helpers: MangaDex -> UI models =====

    private fun MangaDexManga.toListItem(): MangaListItem {
        return MangaListItem(
            id = id,
            title = pickArabicTitle(),
            cover = pickCoverUrl(),
            source = "mangadex",
            status = attributes.status
        )
    }

    private fun MangaDexManga.pickArabicTitle(): String {
        attributes.title["ar"]?.let { return it }
        attributes.title["en"]?.let { return it }
        attributes.altTitles.firstOrNull { it.containsKey("ar") }?.get("ar")?.let { return it }
        attributes.altTitles.firstOrNull { it.containsKey("en") }?.get("en")?.let { return it }
        attributes.title.values.firstOrNull()?.let { return it }
        return "بدون عنوان"
    }

    private fun MangaDexManga.pickCoverUrl(): String {
        val coverRel = relationships.firstOrNull { it.type == "cover_art" }
        val fileName = coverRel?.attributes?.fileName ?: return ""
        return "https://uploads.mangadex.org/covers/$id/$fileName.256.jpg"
    }

    private fun MangaDexManga.toDetails(chapters: List<MangaChapter>): MangaDetails {
        val author = relationships.firstOrNull { it.type == "author" }?.attributes?.name ?: ""
        val artist = relationships.firstOrNull { it.type == "artist" }?.attributes?.name ?: ""
        val description = attributes.description["ar"]
            ?: attributes.description["en"]
            ?: ""
        val genres = attributes.tags.mapNotNull { it.attributes.name["en"] }

        return MangaDetails(
            id = id,
            title = pickArabicTitle(),
            cover = pickCoverUrl(),
            description = description,
            author = author,
            artist = artist,
            status = attributes.status,
            genres = genres,
            chapters = chapters,
            source = "mangadex",
            latestChapter = attributes.lastChapter
        )
    }

    private fun MangaDexChapter.toChapter(): MangaChapter {
        val groupName = relationships
            .filter { it.type == "scanlation_group" }
            .joinToString("، ") { it.attributes?.name ?: "" }
            .ifEmpty { null }

        return MangaChapter(
            id = id,
            number = attributes.chapter ?: "",
            title = attributes.title ?: "",
            date = formatDate(attributes.publishAt ?: attributes.createdAt),
            source = "mangadex",
            externalUrl = attributes.externalUrl,
            groupName = groupName
        )
    }

    private fun formatDate(iso: String?): String {
        if (iso.isNullOrEmpty()) return ""
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            val date = sdf.parse(iso) ?: return iso.take(10)
            val out = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            out.format(date)
        } catch (e: Exception) {
            iso.take(10)
        }
    }
}
