package com.yazan.manga.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.yazan.manga.R
import com.yazan.manga.data.ChapterPage

class ReaderAdapter : RecyclerView.Adapter<ReaderAdapter.PageVH>() {

    private val items: MutableList<ChapterPage> = mutableListOf()
    private var zoomLevel: Float = 1.0f

    fun submitList(newItems: List<ChapterPage>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setZoom(zoom: Float) {
        zoomLevel = zoom
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reader_page, parent, false)
        return PageVH(view)
    }

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        holder.bind(items[position], zoomLevel)
    }

    override fun getItemCount(): Int = items.size

    inner class PageVH(view: View) : RecyclerView.ViewHolder(view) {
        private val imageView: ImageView = view.findViewById(R.id.pageImage)

        fun bind(page: ChapterPage, zoom: Float) {
            Glide.with(imageView.context)
                .load(page.url)
                .placeholder(R.color.surface)
                .into(imageView)

            // Apply zoom by adjusting ImageView scaleType and scale
            imageView.scaleX = zoom
            imageView.scaleY = zoom
        }
    }
}
