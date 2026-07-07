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

class CommentsAdapter(
    private val currentUser: com.yazan.manga.data.AuthManager.User?,
    private val onLike: (String) -> Unit,
    private val onDislike: (String) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onEdit: (String, String) -> Unit,
    private val onBan: (String) -> Unit,
    private val onSuspend: (String, String) -> Unit,
    private val onReport: (String, String) -> Unit,
    private val onOpenReplies: (CommentsManager.Comment) -> Unit,
    private val onViewProfile: (String) -> Unit
) : RecyclerView.Adapter<CommentsAdapter.CommentVH>() {

    private val items = mutableListOf<CommentsManager.Comment>()
    private val repliesMap = mutableMapOf<String, List<CommentsManager.Comment>>()

    fun submitList(topLevel: List<CommentsManager.Comment>, replies: Map<String, List<CommentsManager.Comment>>) {
        items.clear()
        items.addAll(topLevel)
        repliesMap.clear()
        repliesMap.putAll(replies)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_comment, parent, false)
        return CommentVH(view)
    }

    override fun onBindViewHolder(holder: CommentVH, position: Int) {
        holder.bind(items[position], position)
    }

    override fun getItemCount(): Int = items.size

    inner class CommentVH(view: View) : RecyclerView.ViewHolder(view) {
        private val authorText: TextView = view.findViewById(R.id.commentAuthor)
        private val adminBadge: TextView = view.findViewById(R.id.adminBadge)
        private val timeText: TextView = view.findViewById(R.id.commentTime)
        private val commentText: TextView = view.findViewById(R.id.commentText)
        private val likeBtn: TextView = view.findViewById(R.id.btnLike)
        private val dislikeBtn: TextView = view.findViewById(R.id.btnDislike)
        private val replyBtn: TextView = view.findViewById(R.id.btnReply)
        private val deleteBtn: TextView = view.findViewById(R.id.btnDelete)
        private val editBtn: TextView = view.findViewById(R.id.btnEdit)
        private val banBtn: TextView = view.findViewById(R.id.btnBan)
        private val suspendBtn: TextView = view.findViewById(R.id.btnSuspend)
        private val reportBtn: TextView = view.findViewById(R.id.btnReport)
        private val repliesToggle: TextView = view.findViewById(R.id.repliesToggle)

        fun bind(c: CommentsManager.Comment, position: Int) {
            authorText.text = c.authorName
            adminBadge.visibility = if (c.isAdmin) View.VISIBLE else View.GONE
            timeText.text = formatDate(c.createdAt) + if (c.editedAt != null) " (معدّل)" else ""
            commentText.text = c.text

            likeBtn.text = "👍 ${c.likes.size}"
            dislikeBtn.text = "👎 ${c.dislikes.size}"

            val liked = currentUser != null && c.likes.contains(currentUser.email)
            val disliked = currentUser != null && c.dislikes.contains(currentUser.email)
            likeBtn.alpha = if (liked) 1f else 0.5f
            dislikeBtn.alpha = if (disliked) 1f else 0.5f

            likeBtn.setOnClickListener { onLike(c.id) }
            dislikeBtn.setOnClickListener { onDislike(c.id) }

            authorText.setOnClickListener { onViewProfile(c.authorEmail) }

            val replies = repliesMap[c.id] ?: emptyList()
            if (replies.isNotEmpty()) {
                repliesToggle.visibility = View.VISIBLE
                repliesToggle.text = "💬 ${replies.size} رد — اضغط للعرض"
                repliesToggle.setOnClickListener { onOpenReplies(c) }
            } else {
                repliesToggle.visibility = View.GONE
            }

            replyBtn.setOnClickListener { onOpenReplies(c) }

            val isOwner = currentUser?.email == c.authorEmail
            deleteBtn.visibility = if (isOwner || currentUser?.isAdmin == true) View.VISIBLE else View.GONE
            editBtn.visibility = if (isOwner) View.VISIBLE else View.GONE
            banBtn.visibility = if (currentUser?.isAdmin == true && !c.isAdmin) View.VISIBLE else View.GONE
            suspendBtn.visibility = if (currentUser?.isAdmin == true && !c.isAdmin) View.VISIBLE else View.GONE
            reportBtn.visibility = if (currentUser != null && !isOwner && !c.isAdmin) View.VISIBLE else View.GONE

            deleteBtn.setOnClickListener { onDelete(c.id) }
            editBtn.setOnClickListener { showEditDialog(c) }
            banBtn.setOnClickListener { onBan(c.id) }
            suspendBtn.setOnClickListener { showSuspendDialog(c) }
            reportBtn.setOnClickListener { onReport(c.id, c.text) }
        }

        private fun showEditDialog(c: CommentsManager.Comment) {
            val input = EditText(itemView.context)
            input.setText(c.text)
            AlertDialog.Builder(itemView.context)
                .setTitle("تعديل التعليق")
                .setView(input)
                .setPositiveButton("حفظ") { _, _ -> onEdit(c.id, input.text.toString()) }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        private fun showSuspendDialog(c: CommentsManager.Comment) {
            val options = arrayOf("إيقاف يومين", "إيقاف أسبوع", "إيقاف شهر", "إيقاف دائم")
            AlertDialog.Builder(itemView.context)
                .setTitle("إيقاف حساب ${c.authorName}")
                .setItems(options) { _, which ->
                    val days = when (which) { 0 -> 2; 1 -> 7; 2 -> 30; else -> 0 }
                    val input = EditText(itemView.context)
                    input.hint = "سبب الإيقاف"
                    AlertDialog.Builder(itemView.context)
                        .setTitle("سبب الإيقاف")
                        .setView(input)
                        .setPositiveButton("إيقاف") { _, _ ->
                            val reason = input.text.toString().ifEmpty { "مخالفة القوانين" }
                            onSuspend(c.authorEmail, reason)
                        }
                        .setNegativeButton("إلغاء", null)
                        .show()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        }

        private fun formatDate(timestamp: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
    }
}
