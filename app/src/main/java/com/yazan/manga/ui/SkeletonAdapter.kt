package com.yazan.manga.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.facebook.shimmer.ShimmerFrameLayout
import com.yazan.manga.R

/**
 * Adapter that renders N skeleton placeholder cards inside a ShimmerFrameLayout.
 * Used while the real manga list is being fetched from the network.
 */
class SkeletonAdapter(
    private val count: Int = 9
) : RecyclerView.Adapter<SkeletonAdapter.SkeletonVH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SkeletonVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_manga_skeleton, parent, false)
        return SkeletonVH(view)
    }

    override fun onBindViewHolder(holder: SkeletonVH, position: Int) {
        holder.start()
    }

    override fun getItemCount(): Int = count

    inner class SkeletonVH(view: View) : RecyclerView.ViewHolder(view) {
        private val shimmer: ShimmerFrameLayout = view as ShimmerFrameLayout

        fun start() {
            shimmer.startShimmer()
        }
    }
}
