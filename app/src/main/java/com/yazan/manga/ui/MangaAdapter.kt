package com.yazan.manga.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.yazan.manga.R
import com.yazan.manga.data.MangaListItem

class MangaAdapter(
    private val items: MutableList<MangaListItem> = mutableListOf(),
    private val onClick: (MangaListItem) -> Unit
) : RecyclerView.Adapter<MangaAdapter.MangaVH>() {

    fun submitList(newItems: List<MangaListItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun appendList(newItems: List<MangaListItem>) {
        val start = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(start, newItems.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MangaVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_manga, parent, false)
        return MangaVH(view)
    }

    override fun onBindViewHolder(holder: MangaVH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class MangaVH(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.mangaCover)
        private val title: TextView = view.findViewById(R.id.mangaTitle)

        fun bind(item: MangaListItem) {
            title.text = item.title

            Glide.with(cover.context)
                .load(item.cover)
                .centerCrop()
                .placeholder(R.color.surface)
                .into(cover)

            itemView.setOnClickListener { onClick(item) }
        }
    }
}
