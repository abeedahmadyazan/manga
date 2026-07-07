package com.yazan.manga.ui

import android.content.Context
import android.text.format.DateUtils
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
import com.yazan.manga.data.AuthManager
import com.yazan.manga.data.CommentsManager

/**
 * Full-featured comments adapter.
 *
 * - Renders top-level comments in the RecyclerView
 * - Renders replies inline (indented, with right border) inside repliesContainer
 * - Avatar + author name → onProfileClick(username)
 * - Like / dislike toggle (highlights when active)
 * - Reply / edit / delete / ban buttons (visibility by permission)
 */
class CommentsAdapter(
    private val context: Context,
    private val onLike: (CommentsManager.Comment) -> Unit,
    private val onDislike: (CommentsManager.Comment) -> Unit,
    private val onReply: (CommentsManager.Comment) -> Unit,
    private val onEdit: (CommentsManager.Comment) -> Unit,
    private val onDelete: (CommentsManager.Comment) -> Unit,
    private val onBan: (CommentsManager.Comment) -> Unit,
    private val onProfileClick: (String) -> Unit
) : RecyclerView.Adapter<CommentsAdapter.CommentVH>() {

    private val items: MutableList<CommentsManager.Comment> = mutableListOf()
    private var currentUser: AuthManager.User? = null

    init {
        currentUser = AuthManager.getCurrentUser(context)
    }

    fun refreshUser() {
        currentUser = AuthManager.getCurrentUser(context)
        notifyDataSetChanged()
    }

    fun submitList(newItems: List<CommentsManager.Comment>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_comment, parent, false)
        return CommentVH(view)
    }

    override fun onBindViewHolder(holder: CommentVH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class CommentVH(view: View) : RecyclerView.ViewHolder(view) {
        private val avatarLetter: TextView = view.findViewById(R.id.avatarLetter)
        private val avatarImage: ImageView = view.findViewById(R.id.avatarImage)
        private val authorName: TextView = view.findViewById(R.id.authorName)
        private val adminBadge: TextView = view.findViewById(R.id.adminBadge)
        private val commentTime: TextView = view.findViewById(R.id.commentTime)
        private val commentText: TextView = view.findViewById(R.id.commentText)
        private val editedLabel: TextView = view.findViewById(R.id.editedLabel)
        private val likeCount: TextView = view.findViewById(R.id.likeCount)
        private val dislikeCount: TextView = view.findViewById(R.id.dislikeCount)
        private val btnLike: LinearLayout = view.findViewById(R.id.btnLike)
        private val btnDislike: LinearLayout = view.findViewById(R.id.btnDislike)
        private val btnReply: LinearLayout = view.findViewById(R.id.btnReply)
        private val btnEdit: ImageButton = view.findViewById(R.id.btnEdit)
        private val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        private val btnBan: ImageButton = view.findViewById(R.id.btnBan)
        private val repliesContainer: LinearLayout = view.findViewById(R.id.repliesContainer)

        fun bind(comment: CommentsManager.Comment) {
            val user = currentUser
            val isOwner = user?.email == comment.authorEmail
            val isAdmin = user?.isAdmin == true

            // Avatar (first letter of name) — clickable to open profile
            val firstLetter = comment.authorName.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
            avatarLetter.text = firstLetter
            if (comment.isAdmin) {
                avatarLetter.background = context.getDrawable(R.drawable.bg_danger_button)
            }
            avatarLetter.visibility = View.VISIBLE
            avatarImage.visibility = View.GONE

            // Avatar click → profile
            val openProfile = View.OnClickListener {
                // Pass the username to the activity; for legacy lookups by name we use authorName
                onProfileClick(comment.authorEmail)
            }
            avatarLetter.setOnClickListener(openProfile)
            avatarImage.setOnClickListener(openProfile)

            // Author name + admin badge
            authorName.text = comment.authorName
            adminBadge.visibility = if (comment.isAdmin) View.VISIBLE else View.GONE
            authorName.setOnClickListener(openProfile)

            // Time
            commentTime.text = DateUtils.getRelativeTimeSpanString(
                comment.createdAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )

            // Comment text + edited label
            commentText.text = comment.text
            editedLabel.visibility = if (comment.editedAt != null) View.VISIBLE else View.GONE

            // Likes / dislikes
            likeCount.text = comment.likes.size.toString()
            dislikeCount.text = comment.dislikes.size.toString()

            val likedByMe = user != null && comment.likes.contains(user.email)
            val dislikedByMe = user != null && comment.dislikes.contains(user.email)
            (btnLike.getChildAt(0) as ImageView).setColorTint(
                context,
                if (likedByMe) R.color.primary else R.color.text_secondary
            )
            likeCount.setTextColor(
                context.getColor(if (likedByMe) R.color.primary else R.color.text_secondary)
            )
            (btnDislike.getChildAt(0) as ImageView).setColorTint(
                context,
                if (dislikedByMe) R.color.danger else R.color.text_secondary
            )
            dislikeCount.setTextColor(
                context.getColor(if (dislikedByMe) R.color.danger else R.color.text_secondary)
            )

            // Button visibility by permission
            btnEdit.visibility = if (isOwner) View.VISIBLE else View.GONE
            btnDelete.visibility = if (isOwner || isAdmin) View.VISIBLE else View.GONE
            btnBan.visibility = if (isAdmin && !comment.isAdmin) View.VISIBLE else View.GONE

            // Click handlers
            btnLike.setOnClickListener { onLike(comment) }
            btnDislike.setOnClickListener { onDislike(comment) }
            btnReply.setOnClickListener { onReply(comment) }
            btnEdit.setOnClickListener { onEdit(comment) }
            btnDelete.setOnClickListener { onDelete(comment) }
            btnBan.setOnClickListener { onBan(comment) }

            // Inline replies
            repliesContainer.removeAllViews()
            val replies = CommentsManager.getReplies(context, comment.id)
            if (replies.isNotEmpty()) {
                repliesContainer.visibility = View.VISIBLE
                replies.sortedBy { it.createdAt }.forEach { reply -> renderReply(reply) }
            } else {
                repliesContainer.visibility = View.GONE
            }
        }

        private fun renderReply(reply: CommentsManager.Comment) {
            val inflater = LayoutInflater.from(context)
            val view = inflater.inflate(R.layout.item_comment, repliesContainer, false) as ViewGroup

            val avatar = view.findViewById<TextView>(R.id.avatarLetter)
            val authorTv = view.findViewById<TextView>(R.id.authorName)
            val badge = view.findViewById<TextView>(R.id.adminBadge)
            val timeTv = view.findViewById<TextView>(R.id.commentTime)
            val textTv = view.findViewById<TextView>(R.id.commentText)
            val edited = view.findViewById(R.id.editedLabel) as TextView
            val likesTv = view.findViewById<TextView>(R.id.likeCount)
            val dislikesTv = view.findViewById<TextView>(R.id.dislikeCount)
            val likeBtn = view.findViewById<LinearLayout>(R.id.btnLike)
            val dislikeBtn = view.findViewById<LinearLayout>(R.id.btnDislike)
            val replyBtn = view.findViewById<LinearLayout>(R.id.btnReply)
            val editBtn = view.findViewById<ImageButton>(R.id.btnEdit)
            val deleteBtn = view.findViewById<ImageButton>(R.id.btnDelete)
            val banBtn = view.findViewById<ImageButton>(R.id.btnBan)

            val user = currentUser
            val isOwner = user?.email == reply.authorEmail
            val isAdmin = user?.isAdmin == true

            avatar.text = reply.authorName.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
            if (reply.isAdmin) avatar.background = context.getDrawable(R.drawable.bg_danger_button)

            val openProfile = View.OnClickListener { onProfileClick(reply.authorEmail) }
            avatar.setOnClickListener(openProfile)
            authorTv.setOnClickListener(openProfile)

            authorTv.text = reply.authorName
            badge.visibility = if (reply.isAdmin) View.VISIBLE else View.GONE
            timeTv.text = DateUtils.getRelativeTimeSpanString(
                reply.createdAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            )
            textTv.text = reply.text
            edited.visibility = if (reply.editedAt != null) View.VISIBLE else View.GONE
            likesTv.text = reply.likes.size.toString()
            dislikesTv.text = reply.dislikes.size.toString()

            val likedByMe = user != null && reply.likes.contains(user.email)
            val dislikedByMe = user != null && reply.dislikes.contains(user.email)
            (likeBtn.getChildAt(0) as ImageView).setColorTint(
                context,
                if (likedByMe) R.color.primary else R.color.text_secondary
            )
            likesTv.setTextColor(
                context.getColor(if (likedByMe) R.color.primary else R.color.text_secondary)
            )
            (dislikeBtn.getChildAt(0) as ImageView).setColorTint(
                context,
                if (dislikedByMe) R.color.danger else R.color.text_secondary
            )
            dislikesTv.setTextColor(
                context.getColor(if (dislikedByMe) R.color.danger else R.color.text_secondary)
            )

            editBtn.visibility = if (isOwner) View.VISIBLE else View.GONE
            deleteBtn.visibility = if (isOwner || isAdmin) View.VISIBLE else View.GONE
            banBtn.visibility = if (isAdmin && !reply.isAdmin) View.VISIBLE else View.GONE

            likeBtn.setOnClickListener { onLike(reply) }
            dislikeBtn.setOnClickListener { onDislike(reply) }
            replyBtn.setOnClickListener { onReply(reply) }
            editBtn.setOnClickListener { onEdit(reply) }
            deleteBtn.setOnClickListener { onDelete(reply) }
            banBtn.setOnClickListener { onBan(reply) }

            // Hide nested replies container for replies (single level only)
            view.findViewById<View>(R.id.repliesContainer).visibility = View.GONE

            repliesContainer.addView(view)
        }

        private fun ImageView.setColorTint(ctx: Context, colorRes: Int) {
            this.setColorFilter(ctx.getColor(colorRes))
        }
    }
}
