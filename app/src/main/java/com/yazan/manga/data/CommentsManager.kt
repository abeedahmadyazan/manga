package com.yazan.manga.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Comments Manager with full features:
 * - Comments for manga (general) and chapters
 * - Replies to comments
 * - Like / Dislike (toggle, one per user)
 * - Delete comment (owner or admin)
 * - Edit comment (owner only)
 * - Rate limiting: 60 seconds between comments/replies
 * - Max 2 comments per chapter (must delete one to add more)
 * - Ban users (admin only)
 * - Sort: newest / oldest / most liked
 */
object CommentsManager {

    private const val PREFS_NAME = "manga_comments"
    private const val KEY_COMMENTS = "all_comments"
    private const val KEY_LAST_COMMENT_TIME = "last_comment_time"
    private const val COOLDOWN_MS = 60_000L  // 60 seconds
    private const val MAX_PER_CHAPTER = 2

    data class Comment(
        val id: String,
        val context: String,        // manga ID or chapter ID
        val contextType: String,    // "manga" or "chapter"
        val authorName: String,
        val authorEmail: String,
        val authorDeviceId: String,
        val isAdmin: Boolean,
        val text: String,
        val parentId: String?,      // null = top-level, else = reply
        val likes: MutableList<String>,   // list of user emails who liked
        val dislikes: MutableList<String>, // list of user emails who disliked
        val createdAt: Long,
        val editedAt: Long?
    )

    data class CooldownResult(val allowed: Boolean, val secondsLeft: Int)

    /** Check if user can comment (cooldown) */
    fun checkCooldown(context: Context): CooldownResult {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_COMMENT_TIME, 0)
        val now = System.currentTimeMillis()
        val diff = now - last
        if (diff < COOLDOWN_MS) {
            val left = ((COOLDOWN_MS - diff) / 1000).toInt()
            return CooldownResult(false, left)
        }
        return CooldownResult(true, 0)
    }

    /** Check if user can comment on a chapter (max 2 per chapter) */
    fun checkChapterLimit(context: Context, chapterId: String, userEmail: String): Boolean {
        val comments = getAllComments(context)
        val count = comments.filter {
            it.context == chapterId &&
            it.contextType == "chapter" &&
            it.authorEmail == userEmail &&
            it.parentId == null
        }.size
        return count < MAX_PER_CHAPTER
    }

    /** Add a comment or reply */
    fun addComment(
        context: Context,
        contextId: String,
        contextType: String,  // "manga" or "chapter"
        text: String,
        parentId: String? = null
    ): String? {
        val user = AuthManager.getCurrentUser(context) ?: return "يجب تسجيل الدخول"

        // Cooldown check
        val cd = checkCooldown(context)
        if (!cd.allowed) {
            return "انتظر ${cd.secondsLeft} ثانية قبل التعليق مرة أخرى"
        }

        // Chapter limit check (only for top-level chapter comments)
        if (contextType == "chapter" && parentId == null) {
            if (!checkChapterLimit(context, contextId, user.email)) {
                return "وصلت للحد الأقصى ($MAX_PER_CHAPTER تعليقات لكل فصل). احذف تعليقاً لتضيف جديداً."
            }
        }

        val comment = Comment(
            id = UUID.randomUUID().toString(),
            context = contextId,
            contextType = contextType,
            authorName = user.name,
            authorEmail = user.email,
            authorDeviceId = user.deviceId,
            isAdmin = user.isAdmin,
            text = text.trim(),
            parentId = parentId,
            likes = mutableListOf(),
            dislikes = mutableListOf(),
            createdAt = System.currentTimeMillis(),
            editedAt = null
        )

        val comments = getAllComments(context).toMutableList()
        comments.add(comment)
        saveComments(context, comments)

        // Update last comment time
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_COMMENT_TIME, System.currentTimeMillis()).apply()

        return null // success
    }

    fun getComments(context: Context, contextId: String): List<Comment> {
        return getAllComments(context).filter { it.context == contextId }
    }

    fun getReplies(context: Context, parentId: String): List<Comment> {
        return getAllComments(context).filter { it.parentId == parentId }
    }

    fun deleteComment(context: Context, commentId: String): Boolean {
        val comments = getAllComments(context).toMutableList()
        // Delete comment + all its replies
        comments.removeAll { it.id == commentId || it.parentId == commentId }
        saveComments(context, comments)
        return true
    }

    fun editComment(context: Context, commentId: String, newText: String): String? {
        val user = AuthManager.getCurrentUser(context) ?: return "يجب تسجيل الدخول"
        val comments = getAllComments(context).toMutableList()
        val idx = comments.indexOfFirst { it.id == commentId }
        if (idx < 0) return "التعليق غير موجود"
        if (comments[idx].authorEmail != user.email) return "يمكن تعديل تعليقك فقط"
        comments[idx] = comments[idx].copy(text = newText.trim(), editedAt = System.currentTimeMillis())
        saveComments(context, comments)
        return null
    }

    fun toggleLike(context: Context, commentId: String): String? {
        val user = AuthManager.getCurrentUser(context) ?: return "يجب تسجيل الدخول"
        val comments = getAllComments(context).toMutableList()
        val idx = comments.indexOfFirst { it.id == commentId }
        if (idx < 0) return "التعليق غير موجود"

        val comment = comments[idx]
        if (comment.likes.contains(user.email)) {
            comment.likes.remove(user.email)
        } else {
            comment.likes.add(user.email)
            comment.dislikes.remove(user.email)
        }
        comments[idx] = comment
        saveComments(context, comments)
        return null
    }

    fun toggleDislike(context: Context, commentId: String): String? {
        val user = AuthManager.getCurrentUser(context) ?: return "يجب تسجيل الدخول"
        val comments = getAllComments(context).toMutableList()
        val idx = comments.indexOfFirst { it.id == commentId }
        if (idx < 0) return "التعليق غير موجود"

        val comment = comments[idx]
        if (comment.dislikes.contains(user.email)) {
            comment.dislikes.remove(user.email)
        } else {
            comment.dislikes.add(user.email)
            comment.likes.remove(user.email)
        }
        comments[idx] = comment
        saveComments(context, comments)
        return null
    }

    /** Ban a user by device ID (admin only) */
    fun banUser(context: Context, commentId: String): String? {
        val user = AuthManager.getCurrentUser(context) ?: return "يجب تسجيل الدخول"
        if (!user.isAdmin) return "هذا الإجراء للمشرف فقط"

        val comments = getAllComments(context)
        val target = comments.find { it.id == commentId } ?: return "التعليق غير موجود"
        if (target.isAdmin) return "لا يمكن حظر مشرف"

        AuthManager.banDevice(context, target.authorDeviceId)
        // Delete all comments by this user
        val filtered = comments.filterNot { it.authorDeviceId == target.authorDeviceId }
        saveComments(context, filtered)
        return null
    }

    // === Sorting ===
    fun sortComments(comments: List<Comment>, sortBy: String): List<Comment> {
        return when (sortBy) {
            "oldest" -> comments.sortedBy { it.createdAt }
            "likes" -> comments.sortedByDescending { it.likes.size - it.dislikes.size }
            else -> comments.sortedByDescending { it.createdAt }  // newest
        }
    }

    // === Storage ===
    private fun getAllComments(context: Context): List<Comment> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_COMMENTS, "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = mutableListOf<Comment>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(Comment(
                id = o.getString("id"),
                context = o.getString("context"),
                contextType = o.getString("contextType"),
                authorName = o.getString("authorName"),
                authorEmail = o.getString("authorEmail"),
                authorDeviceId = o.getString("authorDeviceId"),
                isAdmin = o.getBoolean("isAdmin"),
                text = o.getString("text"),
                parentId = if (o.has("parentId") && !o.isNull("parentId")) o.getString("parentId") else null,
                likes = mutableListOf(*o.getJSONArray("likes").toStringList()),
                dislikes = mutableListOf(*o.getJSONArray("dislikes").toStringList()),
                createdAt = o.getLong("createdAt"),
                editedAt = if (o.has("editedAt") && !o.isNull("editedAt")) o.getLong("editedAt") else null
            ))
        }
        return list
    }

    private fun JSONArray.toStringList(): Array<String> {
        return Array(this.length()) { this.getString(it) }
    }

    private fun saveComments(context: Context, comments: List<Comment>) {
        val arr = JSONArray()
        comments.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("context", c.context)
                put("contextType", c.contextType)
                put("authorName", c.authorName)
                put("authorEmail", c.authorEmail)
                put("authorDeviceId", c.authorDeviceId)
                put("isAdmin", c.isAdmin)
                put("text", c.text)
                put("parentId", c.parentId)
                put("likes", JSONArray(c.likes))
                put("dislikes", JSONArray(c.dislikes))
                put("createdAt", c.createdAt)
                put("editedAt", c.editedAt)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_COMMENTS, arr.toString()).apply()
    }
}
