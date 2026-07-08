package com.yazan.manga.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yazan.manga.R
import com.yazan.manga.data.DownloadManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DownloadsAdapter(
    private val onClick: (DownloadManager.DownloadedChapter) -> Unit,
    private val onDeleteClick: (DownloadManager.DownloadedChapter) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.VH>() {

    private val items: MutableList<DownloadManager.DownloadedChapter> = mutableListOf()

    fun submitList(newItems: List<DownloadManager.DownloadedChapter>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_download, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.downloadTitle)
        private val subtitle: TextView = view.findViewById(R.id.downloadSubtitle)
        private val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)

        fun bind(item: DownloadManager.DownloadedChapter) {
            title.text = "فصل ${item.chapterNumber}"
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(item.downloadedAt))
            subtitle.text = "${item.pageCount} صفحة · $dateStr"
            itemView.setOnClickListener { onClick(item) }
            btnDelete.setOnClickListener { onDeleteClick(item) }
        }
    }
}
