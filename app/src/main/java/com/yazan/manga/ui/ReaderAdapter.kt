package com.yazan.manga.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.yazan.manga.R
import com.yazan.manga.data.ChapterPage

class ReaderAdapter : RecyclerView.Adapter<ReaderAdapter.PageVH>() {

    private val items: MutableList<ChapterPage> = mutableListOf()
    // Global zoom (applied via buttons). Per-page pinch-zoom is handled by
    // ZoomableImageView itself, so this is only for the +/- buttons.
    private var globalZoom: Float = 1.0f

    fun submitList(newItems: List<ChapterPage>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setZoom(zoom: Float) {
        globalZoom = zoom
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reader_page, parent, false)
        return PageVH(view)
    }

    override fun onBindViewHolder(holder: PageVH, position: Int) {
        holder.bind(items[position], globalZoom)
    }

    override fun onViewRecycled(holder: PageVH) {
        holder.resetZoom()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    inner class PageVH(view: View) : RecyclerView.ViewHolder(view) {
        private val imageView: ZoomableImageView = view.findViewById(R.id.pageImage)

        fun bind(page: ChapterPage, globalZoom: Float) {
            // Reset any previous pinch-zoom state before binding a new page
            imageView.resetZoom()

            // Apply the global button-zoom as a base scale (1.0 by default)
            imageView.scaleX = globalZoom
            imageView.scaleY = globalZoom

            Glide.with(imageView.context)
                .load(page.url)
                .placeholder(R.color.surface)
                .into(imageView)
        }

        fun resetZoom() {
            imageView.resetZoom()
        }
    }
}
