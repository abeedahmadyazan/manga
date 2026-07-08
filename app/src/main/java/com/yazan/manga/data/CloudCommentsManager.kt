package com.yazan.manga.data

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

object CloudCommentsManager {
    private val db = FirebaseFirestore.getInstance()
    private val TAG = "CloudComments"
    
    data class Comment(
        val id: String = "",
        val contextId: String = "",
        val contextType: String = "",
        val authorName: String = "",
        val authorEmail: String = "",
        val isAdmin: Boolean = false,
        val text: String = "",
        val parentId: String? = null,
        val likes: List<String> = emptyList(),
        val dislikes: List<String> = emptyList(),
        val createdAt: Long = System.currentTimeMillis(),
        val editedAt: Long? = null
    )

    fun addComment(context: Context, contextId: String, contextType: String, text: String, parentId: String? = null, callback: (Boolean, String?) -> Unit) {
        val user = AuthManager.getCurrentUser(context) ?: run {
            callback(false, "يجب تسجيل الدخول")
            return
        }

        // Check if the user is currently suspended in the cloud (banned_users/{email})
        db.collection("banned_users").document(user.email).get()
            .addOnSuccessListener { banDoc ->
                if (banDoc.exists()) {
                    val until = banDoc.getLong("until") ?: 0L
                    if (until == 0L) {
                        callback(false, "تم إيقاف حسابك بشكل دائم")
                        return@addOnSuccessListener
                    }
                    if (until > System.currentTimeMillis()) {
                        val remainingMs = until - System.currentTimeMillis()
                        val remainingText = when {
                            remainingMs < 60 * 60 * 1000 -> "${remainingMs / 60000} دقيقة"
                            remainingMs < 24 * 60 * 60 * 1000 -> "${remainingMs / (60 * 60 * 1000)} ساعة"
                            else -> "${remainingMs / (24 * 60 * 60 * 1000)} يوم"
                        }
                        callback(false, "تم إيقاف حسابك. متبقي: $remainingText")
                        return@addOnSuccessListener
                    }
                    // Ban expired — clean it up
                    db.collection("banned_users").document(user.email).delete()
                }

                val comment = hashMapOf(
                    "contextId" to contextId,
                    "contextType" to contextType,
                    "authorName" to user.name,
                    "authorEmail" to user.email,
                    "isAdmin" to user.isAdmin,
                    "text" to text.trim(),
                    "parentId" to parentId,
                    "likes" to emptyList<String>(),
                    "dislikes" to emptyList<String>(),
                    "createdAt" to System.currentTimeMillis(),
                    "editedAt" to null
                )

                db.collection("comments")
                    .add(comment)
                    .addOnSuccessListener { doc ->
                        Log.d(TAG, "Comment added: ${doc.id}")
                        callback(true, null)
                    }
                    .addOnFailureListener {
                        callback(false, "حدث خطأ")
                    }
            }
            .addOnFailureListener {
                // If the ban check fails, still allow the comment (don't block legit users)
                val comment = hashMapOf(
                    "contextId" to contextId,
                    "contextType" to contextType,
                    "authorName" to user.name,
                    "authorEmail" to user.email,
                    "isAdmin" to user.isAdmin,
                    "text" to text.trim(),
                    "parentId" to parentId,
                    "likes" to emptyList<String>(),
                    "dislikes" to emptyList<String>(),
                    "createdAt" to System.currentTimeMillis(),
                    "editedAt" to null
                )
                db.collection("comments").add(comment)
                    .addOnSuccessListener { callback(true, null) }
                    .addOnFailureListener { callback(false, "حدث خطأ") }
            }
    }

    fun listenToComments(
        contextId: String,
        onUpdate: (List<Comment>) -> Unit,
        onError: ((Exception) -> Unit)? = null
    ): ListenerRegistration {
        return db.collection("comments")
            .whereEqualTo("contextId", contextId)
            // NOTE: no orderBy() here — it requires a composite index on Firestore.
            // We sort locally instead to avoid index-not-found errors.
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Listen error: ${error.message}")
                    onError?.invoke(error)
                    // DON'T clear the list on error - keep existing comments
                    return@addSnapshotListener
                }

                val comments = mutableListOf<Comment>()
                snapshot?.documents?.forEach { doc ->
                    val c = Comment(
                        id = doc.id,
                        contextId = doc.getString("contextId") ?: "",
                        contextType = doc.getString("contextType") ?: "",
                        authorName = doc.getString("authorName") ?: "",
                        authorEmail = doc.getString("authorEmail") ?: "",
                        isAdmin = doc.getBoolean("isAdmin") ?: false,
                        text = doc.getString("text") ?: "",
                        parentId = doc.getString("parentId"),
                        likes = (doc.get("likes") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        dislikes = (doc.get("dislikes") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        createdAt = doc.getLong("createdAt") ?: 0,
                        editedAt = doc.getLong("editedAt")
                    )
                    comments.add(c)
                }
                // Sort locally (descending by createdAt) to replace the removed orderBy()
                comments.sortByDescending { it.createdAt }
                onUpdate(comments)
            }
    }

    fun deleteComment(commentId: String, callback: (Boolean) -> Unit) {
        db.collection("comments").document(commentId)
            .delete()
            .addOnSuccessListener { callback(true) }
            .addOnFailureListener { callback(false) }
    }

    fun toggleLike(commentId: String, userEmail: String, isLike: Boolean, callback: (Boolean) -> Unit) {
        db.collection("comments").document(commentId)
            .get()
            .addOnSuccessListener { doc ->
                val likes = (doc.get("likes") as? List<*>)?.filterIsInstance<String>()?.toMutableList() ?: mutableListOf()
                val dislikes = (doc.get("dislikes") as? List<*>)?.filterIsInstance<String>()?.toMutableList() ?: mutableListOf()
                
                if (isLike) {
                    if (likes.contains(userEmail)) likes.remove(userEmail)
                    else { likes.add(userEmail); dislikes.remove(userEmail) }
                } else {
                    if (dislikes.contains(userEmail)) dislikes.remove(userEmail)
                    else { dislikes.add(userEmail); likes.remove(userEmail) }
                }
                
                db.collection("comments").document(commentId)
                    .update("likes", likes, "dislikes", dislikes)
                    .addOnSuccessListener { callback(true) }
                    .addOnFailureListener { callback(false) }
            }
    }

    fun editComment(commentId: String, newText: String, callback: (Boolean) -> Unit) {
        db.collection("comments").document(commentId)
            .update("text", newText.trim(), "editedAt", System.currentTimeMillis())
            .addOnSuccessListener { callback(true) }
            .addOnFailureListener { callback(false) }
    }

    fun banUser(authorEmail: String, context: Context, callback: (Boolean) -> Unit) {
        // Get all comments by this user and delete them
        db.collection("comments")
            .whereEqualTo("authorEmail", authorEmail)
            .get()
            .addOnSuccessListener { snapshot ->
                val batch = db.batch()
                snapshot.documents.forEach { doc -> batch.delete(doc.reference) }
                batch.commit()
                    .addOnSuccessListener { callback(true) }
                    .addOnFailureListener { callback(false) }
            }
    }

    // ============================================================
    //  Reports (cloud-backed so the admin sees them from any device)
    // ============================================================

    data class Report(
        val id: String = "",
        val commentId: String = "",
        val commentText: String = "",
        val commentContextId: String = "",
        val commentContextTitle: String = "",
        val reportedEmail: String = "",
        val reportedName: String = "",
        val reportedByEmail: String = "",
        val reportedByName: String = "",
        val reason: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val resolved: Boolean = false,
        val resolvedBy: String? = null,
        val resolvedAt: Long? = null
    )

    /**
     * Report a comment. One report per user per comment (enforced by checking
     * existing reports with the same commentId + reportedByEmail).
     */
    fun reportComment(
        context: Context,
        comment: Comment,
        contextTitle: String,
        reason: String,
        callback: (Boolean, String?) -> Unit
    ) {
        val user = AuthManager.getCurrentUser(context) ?: run {
            callback(false, "يجب تسجيل الدخول")
            return
        }
        if (user.email == comment.authorEmail) {
            callback(false, "لا يمكنك الإبلاغ عن تعليقك")
            return
        }

        // Check if already reported by this user
        db.collection("reports")
            .whereEqualTo("commentId", comment.id)
            .whereEqualTo("reportedByEmail", user.email)
            .get()
            .addOnSuccessListener { existing ->
                if (!existing.isEmpty) {
                    callback(false, "لقد بلّغت عن هذا التعليق مسبقاً")
                    return@addOnSuccessListener
                }

                val report = hashMapOf(
                    "commentId" to comment.id,
                    "commentText" to comment.text,
                    "commentContextId" to comment.contextId,
                    "commentContextTitle" to contextTitle,
                    "reportedEmail" to comment.authorEmail,
                    "reportedName" to comment.authorName,
                    "reportedByEmail" to user.email,
                    "reportedByName" to user.name,
                    "reason" to reason.trim(),
                    "createdAt" to System.currentTimeMillis(),
                    "resolved" to false,
                    "resolvedBy" to null,
                    "resolvedAt" to null
                )
                db.collection("reports")
                    .add(report)
                    .addOnSuccessListener { callback(true, null) }
                    .addOnFailureListener { e -> callback(false, "حدث خطأ") }
            }
            .addOnFailureListener { e -> callback(false, "حدث خطأ") }
    }

    /**
     * Listen to ALL unresolved reports (for the admin panel).
     */
    fun listenToReports(
        onUpdate: (List<Report>) -> Unit,
        onError: ((Exception) -> Unit)? = null
    ): ListenerRegistration {
        return db.collection("reports")
            .whereEqualTo("resolved", false)
            // Sort locally to avoid needing a composite index
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Reports listen error: ${error.message}")
                    onError?.invoke(error)
                    return@addSnapshotListener
                }
                val reports = mutableListOf<Report>()
                snapshot?.documents?.forEach { doc ->
                    reports.add(Report(
                        id = doc.id,
                        commentId = doc.getString("commentId") ?: "",
                        commentText = doc.getString("commentText") ?: "",
                        commentContextId = doc.getString("commentContextId") ?: "",
                        commentContextTitle = doc.getString("commentContextTitle") ?: "",
                        reportedEmail = doc.getString("reportedEmail") ?: "",
                        reportedName = doc.getString("reportedName") ?: "",
                        reportedByEmail = doc.getString("reportedByEmail") ?: "",
                        reportedByName = doc.getString("reportedByName") ?: "",
                        reason = doc.getString("reason") ?: "",
                        createdAt = doc.getLong("createdAt") ?: 0,
                        resolved = doc.getBoolean("resolved") ?: false,
                        resolvedBy = doc.getString("resolvedBy"),
                        resolvedAt = doc.getLong("resolvedAt")
                    ))
                }
                reports.sortByDescending { it.createdAt }
                onUpdate(reports)
            }
    }

    /**
     * Mark a report as resolved (admin only). Optionally delete the reported comment.
     */
    fun resolveReport(
        reportId: String,
        adminEmail: String,
        deleteComment: Boolean,
        commentId: String,
        callback: (Boolean) -> Unit
    ) {
        val updates = mapOf(
            "resolved" to true,
            "resolvedBy" to adminEmail,
            "resolvedAt" to System.currentTimeMillis()
        )
        db.collection("reports").document(reportId)
            .update(updates)
            .addOnSuccessListener {
                if (deleteComment) {
                    db.collection("comments").document(commentId)
                        .delete()
                        .addOnCompleteListener { callback(true) }
                } else {
                    callback(true)
                }
            }
            .addOnFailureListener { callback(false) }
    }

    /** Delete a report (admin only, e.g. if it's invalid). */
    fun deleteReport(reportId: String, callback: (Boolean) -> Unit) {
        db.collection("reports").document(reportId)
            .delete()
            .addOnSuccessListener { callback(true) }
            .addOnFailureListener { callback(false) }
    }
}
