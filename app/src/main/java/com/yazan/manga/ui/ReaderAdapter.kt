package com.yazan.manga.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.yazan.manga.R
import com.yazan.manga.data.ChapterPage

class ReaderAdapter(
    private val pages: MutableList<ChapterPage> = mutableListOf()
) : RecyclerView.Adapter<ReaderAdapter.PageVH>() {

    fun submitList(newPages: List<ChapterPage>) {
        pages.clear()
        pages.addAll(newPages)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reader_page, parent, false)
        return PageVH(view)
    }

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        holder.bind(pages[position])
    }

    override fun getItemCount(): Int = pages.size

    inner class PageVH(view: View) : RecyclerView.ViewHolder(view) {
        private val imageView: ImageView = view.findViewById(R.id.pageImage)

        fun bind(page: ChapterPage) {
            Glide.with(imageView.context)
                .load(page.url)
                .placeholder(R.color.surface)
                .into(imageView)
        }
    }
}
