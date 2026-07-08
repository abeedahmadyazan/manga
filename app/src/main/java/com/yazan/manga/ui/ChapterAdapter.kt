package com.yazan.manga.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.yazan.manga.R
import com.yazan.manga.data.DownloadManager
import com.yazan.manga.data.MangaChapter
import com.yazan.manga.data.MangaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChapterAdapter(
    private val onClick: (MangaChapter) -> Unit,
    private val onCommentsClick: (MangaChapter) -> Unit,
    /** Returns the manga id this adapter belongs to. Used for download paths. */
    private val mangaIdProvider: () -> String = { "" }
) : RecyclerView.Adapter<ChapterAdapter.ChapterVH>() {

    private val items: MutableList<MangaChapter> = mutableListOf()

    fun submitList(newItems: List<MangaChapter>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChapterVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chapter, parent, false)
        return ChapterVH(view)
    }

    override fun onBindViewHolder(holder: ChapterVH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ChapterVH(view: View) : RecyclerView.ViewHolder(view) {
        private val number: TextView = view.findViewById(R.id.chapterNumber)
        private val title: TextView = view.findViewById(R.id.chapterTitle)
        private val date: TextView = view.findViewById(R.id.chapterDate)
        private val btnComments: ImageButton = view.findViewById(R.id.btnChapterComments)
        private val btnDownload: ImageButton = view.findViewById(R.id.btnChapterDownload)

        fun bind(ch: MangaChapter) {
            number.text = "فصل ${ch.number}"
            title.text = ch.title.ifEmpty { "الفصل ${ch.number}" }
            date.text = ch.date.ifEmpty { "" }

            itemView.setOnClickListener { onClick(ch) }
            btnComments.setOnClickListener { onCommentsClick(ch) }

            // Reflect current download state on the icon
            refreshDownloadIcon(ch)

            btnDownload.setOnClickListener {
                val ctx = itemView.context
                val mangaId = mangaIdProvider()
                if (mangaId.isBlank()) {
                    Toast.makeText(ctx, "تعذّر تحديد المانجا", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (DownloadManager.isChapterDownloaded(ctx, mangaId, ch.number)) {
                    // Already downloaded — ask to delete
                    android.app.AlertDialog.Builder(ctx)
                        .setTitle("حذف التحمميل")
                        .setMessage("هذا الفصل محمّل. هل تريد حذفه؟")
                        .setPositiveButton("حذف") { _, _ ->
                            DownloadManager.deleteChapter(ctx, mangaId, ch.number)
                            Toast.makeText(ctx, "تم الحذف", Toast.LENGTH_SHORT).show()
                            refreshDownloadIcon(ch)
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                    return@setOnClickListener
                }
                // Start download
                startDownload(ctx, mangaId, ch)
            }
        }

        private fun refreshDownloadIcon(ch: MangaChapter) {
            val ctx = itemView.context
            val mangaId = mangaIdProvider()
            if (mangaId.isNotBlank() && DownloadManager.isChapterDownloaded(ctx, mangaId, ch.number)) {
                btnDownload.setImageResource(R.drawable.ic_check)
                btnDownload.imageTintList = android.content.res.ColorStateList.valueOf(
                    ctx.getColor(R.color.primary)
                )
            } else {
                btnDownload.setImageResource(R.drawable.ic_download)
                btnDownload.imageTintList = android.content.res.ColorStateList.valueOf(
                    ctx.getColor(R.color.text_secondary)
                )
            }
        }

        private var downloadJob: kotlinx.coroutines.Job? = null

        private fun startDownload(ctx: Context, mangaId: String, ch: MangaChapter) {
            if (downloadJob?.isActive == true) return
            Toast.makeText(ctx, "بدأ تحميل الفصل ${ch.number}", Toast.LENGTH_SHORT).show()
            // Show a spinning state
            btnDownload.setImageResource(android.R.drawable.ic_popup_sync)
            btnDownload.isEnabled = false

            // Use a GlobalScope job tied to the adapter — the download continues
            // even if the user scrolls away or leaves the activity, because we
            // want downloads to complete in the background.
            downloadJob = kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                val repo = MangaRepository()
                val result = withContext(Dispatchers.IO) {
                    // First: fetch the page URLs from the network
                    val pagesResult = repo.getChapterPages(ch)
                    if (pagesResult.isFailure) return@withContext Result.failure<Int>(
                        pagesResult.exceptionOrNull() ?: Exception("Unknown")
                    )
                    val urls = pagesResult.getOrNull()?.map { it.url } ?: emptyList()
                    if (urls.isEmpty()) return@withContext Result.failure<Int>(
                        Exception("لا توجد صفحات")
                    )
                    // Then: download each image to disk
                    val downloaded = try {
                        DownloadManager.downloadChapter(
                            context = ctx,
                            mangaId = mangaId,
                            chapterNumber = ch.number,
                            pageUrls = urls
                        )
                    } catch (e: Exception) {
                        return@withContext Result.failure<Int>(e)
                    }
                    Result.success(downloaded)
                }

                btnDownload.isEnabled = true
                result.onSuccess { count ->
                    if (count > 0) {
                        Toast.makeText(ctx, "تم تحميل $count صفحة", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(ctx, "لم يتم تحميل أي صفحة", Toast.LENGTH_SHORT).show()
                    }
                    refreshDownloadIcon(ch)
                }.onFailure { e ->
                    Toast.makeText(ctx, "فشل التحميل: ${e.message ?: "خطأ"}", Toast.LENGTH_LONG).show()
                    refreshDownloadIcon(ch)
                }
            }
        }
    }
}
