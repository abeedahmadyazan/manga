package com.yazan.manga.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.yazan.manga.R
import com.yazan.manga.data.MangaChapter

class ChapterAdapter(
    private val onClick: (MangaChapter) -> Unit,
    private val onCommentsClick: (MangaChapter) -> Unit
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

        fun bind(ch: MangaChapter) {
            number.text = "فصل ${ch.number}"
            title.text = ch.title.ifEmpty { "الفصل ${ch.number}" }
            date.text = ch.date.ifEmpty { "" }

            itemView.setOnClickListener { onClick(ch) }
            btnComments.setOnClickListener { onCommentsClick(ch) }
        }
    }
}
