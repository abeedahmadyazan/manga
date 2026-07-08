package com.yazan.manga.data

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
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
            .addOnFailureListener { e ->
                Log.e(TAG, "Error adding comment: ${e.message}")
                callback(false, e.message)
            }
    }

    fun listenToComments(contextId: String, onUpdate: (List<Comment>) -> Unit): ListenerRegistration {
        return db.collection("comments")
            .whereEqualTo("contextId", contextId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Listen error: ${error.message}")
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
}
