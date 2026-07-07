package com.yazan.manga.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.yazan.manga.R
import com.yazan.manga.data.CommentsManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RepliesAdapter(
    private val currentUser: com.yazan.manga.data.AuthManager.User?,
    private val onLike: (String) -> Unit,
    private val onDislike: (String) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onEdit: (String, String) -> Unit,
    private val onBan: (String) -> Unit,
    private val onReport: (String, String) -> Unit,
    private val onViewProfile: (String) -> Unit
) : RecyclerView.Adapter<RepliesAdapter.ReplyVH>() {

    private val items = mutableListOf<CommentsManager.Comment>()

    fun submitList(newItems: List<CommentsManager.Comment>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReplyVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_reply, parent, false)
        return ReplyVH(view)
    }

    override fun onBindViewHolder(holder: ReplyVH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ReplyVH(view: View) : RecyclerView.ViewHolder(view) {
        private val authorText: TextView = view.findViewById(R.id.replyAuthor)
        private val adminBadge: TextView = view.findViewById(R.id.adminBadge)
        private val timeText: TextView = view.findViewById(R.id.replyTime)
        private val replyText: TextView = view.findViewById(R.id.replyText)
        private val likeBtn: TextView = view.findViewById(R.id.btnLike)
        private val dislikeBtn: TextView = view.findViewById(R.id.btnDislike)
        private val deleteBtn: TextView = view.findViewById(R.id.btnDelete)
        private val editBtn: TextView = view.findViewById(R.id.btnEdit)
        private val banBtn: TextView = view.findViewById(R.id.btnBan)
        private val reportBtn: TextView = view.findViewById(R.id.btnReport)

        fun bind(c: CommentsManager.Comment) {
            authorText.text = c.authorName
            adminBadge.visibility = if (c.isAdmin) View.VISIBLE else View.GONE
            timeText.text = formatDate(c.createdAt) + if (c.editedAt != null) " (معدّل)" else ""
            replyText.text = c.text

            likeBtn.text = "👍 ${c.likes.size}"
            dislikeBtn.text = "👎 ${c.dislikes.size}"

            val liked = currentUser != null && c.likes.contains(currentUser.email)
            val disliked = currentUser != null && c.dislikes.contains(currentUser.email)
            likeBtn.alpha = if (liked) 1f else 0.5f
            dislikeBtn.alpha = if (disliked) 1f else 0.5f

            likeBtn.setOnClickListener { onLike(c.id) }
            dislikeBtn.setOnClickListener { onDislike(c.id) }

            authorText.setOnClickListener { onViewProfile(c.authorEmail) }

            val isOwner = currentUser?.email == c.authorEmail
            deleteBtn.visibility = if (isOwner || currentUser?.isAdmin == true) View.VISIBLE else View.GONE
            editBtn.visibility = if (isOwner) View.VISIBLE else View.GONE
            banBtn.visibility = if (currentUser?.isAdmin == true && !c.isAdmin) View.VISIBLE else View.GONE
            reportBtn.visibility = if (currentUser != null && !isOwner) View.VISIBLE else View.GONE

            deleteBtn.setOnClickListener { onDelete(c.id) }
            editBtn.setOnClickListener { showEditDialog(c) }
            banBtn.setOnClickListener { onBan(c.id) }
            reportBtn.setOnClickListener { onReport(c.id, c.text) }
        }

        private fun showEditDialog(c: CommentsManager.Comment) {
            val input = EditText(itemView.context)
            input.setText(c.text)
            AlertDialog.Builder(itemView.context)
                .setTitle("تعديل الرد")
                .setView(input)
                .setPositiveButton("حفظ") { _, _ -> onEdit(c.id, input.text.toString()) }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        private fun formatDate(timestamp: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
    }
}
