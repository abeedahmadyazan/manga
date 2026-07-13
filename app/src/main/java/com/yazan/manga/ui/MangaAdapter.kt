package com.yazan.manga.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.yazan.manga.R
import com.yazan.manga.data.MangaListItem

class MangaAdapter(
    private val items: MutableList<MangaListItem> = mutableListOf(),
    private val onClick: (MangaListItem) -> Unit,
    private val onRemoveClick: ((MangaListItem) -> Unit)? = null
) : RecyclerView.Adapter<MangaAdapter.MangaVH>() {

    init {
        // Stable IDs let RecyclerView animate item changes/moves instead of
        // rebinding everything — this is the single biggest smoothness win.
        setHasStableIds(true)
    }

    fun submitList(newItems: List<MangaListItem>) {
        // Use DiffUtil so only changed items are rebound — avoids the
        // full rebind (and image reload) that notifyDataSetChanged causes.
        val old = items.toList()
        val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(
            object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = newItems.size
                override fun areItemsTheSame(o: Int, n: Int) = old[o].id == newItems[n].id
                override fun areContentsTheSame(o: Int, n: Int) = old[o] == newItems[n]
            }
        )
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    fun appendList(newItems: List<MangaListItem>) {
        val start = items.size
        items.addAll(newItems)
        notifyItemRangeInserted(start, newItems.size)
    }

    override fun getItemId(position: Int): Long {
        // Generate a stable 64-bit ID from the manga id's hashCode.
        // Required when setHasStableIds(true).
        return items[position].id.hashCode().toLong()
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

    override fun onViewRecycled(holder: MangaVH) {
        // Clear the Glide load when the view is recycled — prevents
        // the old image from flashing briefly when the holder is reused.
        Glide.with(holder.itemView.context).clear(holder.coverRef)
        super.onViewRecycled(holder)
    }

    inner class MangaVH(view: View) : RecyclerView.ViewHolder(view) {
        private val cover: ImageView = view.findViewById(R.id.mangaCover)
        private val title: TextView = view.findViewById(R.id.mangaTitle)
        private val btnRemove: ImageButton = view.findViewById(R.id.btnRemoveFromList)
        private val ratingChip: LinearLayout = view.findViewById(R.id.ratingChip)
        // Exposed for onViewRecycled to clear Glide
        val coverRef: ImageView get() = cover
        private val tvRating: TextView = view.findViewById(R.id.tvRating)
        private val tvStatus: TextView = view.findViewById(R.id.tvStatus)

        fun bind(item: MangaListItem) {
            title.text = item.title

            // Rating chip — only show if rating is non-null and > 0
            val rating = item.rating
            if (rating != null && rating > 0.0) {
                ratingChip.visibility = View.VISIBLE
                tvRating.text = String.format("%.1f", rating)
            } else {
                ratingChip.visibility = View.GONE
            }

            // Status badge — only show if non-empty
            val status = item.status
            if (!status.isNullOrBlank()) {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = normalizeStatus(status)
            } else {
                tvStatus.visibility = View.GONE
            }

            Glide.with(cover.context)
                .load(item.cover)
                .centerCrop()
                .override(400, 600)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .placeholder(R.color.surface_light)
                .error(R.color.surface_light)
                .into(cover)

            itemView.setOnClickListener { onClick(item) }

            // Show the remove button only if a remove callback was provided
            // (i.e. only in MangaListActivity, not in MainActivity/search).
            if (onRemoveClick != null) {
                btnRemove.visibility = View.VISIBLE
                btnRemove.setOnClickListener { onRemoveClick.invoke(item) }
            } else {
                btnRemove.visibility = View.GONE
            }
        }

        /** Translate raw status strings (en/ar) to a short Arabic label. */
        private fun normalizeStatus(raw: String): String {
            val v = raw.trim().lowercase()
            return when {
                v.contains("ongoing") || v.contains("مستمر") -> "مستمرة"
                v.contains("completed") || v.contains("منته") || v.contains("مكتمل") -> "منتهية"
                v.contains("hiatus") || v.contains("متوقف") || v.contains("متوقفة") -> "متوقفة"
                v.contains("cancelled") || v.contains("ملغ") -> "ملغاة"
                else -> raw
            }
        }
    }
}
