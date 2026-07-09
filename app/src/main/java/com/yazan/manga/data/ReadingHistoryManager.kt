package com.yazan.manga.data

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Cloud-backed reading history.
 *
 * Each time a user opens a chapter, we record:
 *   - mangaId, mangaTitle, mangaCover
 *   - chapterId, chapterNumber, chapterTitle
 *   - readAt (timestamp)
 *
 * Stored in Firestore: reading_history/{email} → { entries: [...] }
 * Real-time listener keeps the history screen up to date across devices.
 */
object ReadingHistoryManager {

    private const val TAG = "ReadingHistory"
    private const val COLLECTION = "reading_history"
    private val db by lazy { FirebaseFirestore.getInstance() }

    data class HistoryEntry(
        val mangaId: String = "",
        val mangaTitle: String = "",
        val mangaCover: String = "",
        val chapterId: String = "",
        val chapterNumber: String = "",
        val chapterTitle: String = "",
        val readAt: Long = System.currentTimeMillis()
    )

    /** Record that the user opened a chapter. Called from ReaderActivity. */
    fun recordChapterRead(context: Context, entry: HistoryEntry) {
        val user = AuthManager.getCurrentUser(context) ?: return
        if (user.email.isEmpty()) return
        try {
            db.collection(COLLECTION).document(user.email).get()
                .addOnSuccessListener { doc ->
                    val current = if (doc.exists()) parseEntries(doc) else emptyList()
                    // Remove any existing entry for the same manga (keep only the latest chapter)
                    val filtered = current.filterNot { it.mangaId == entry.mangaId }
                    val updated = (filtered + entry).sortedByDescending { it.readAt }.take(100)
                    val data = mapOf<String, Any>(
                        "entries" to updated.map {
                            mapOf(
                                "mangaId" to it.mangaId,
                                "mangaTitle" to it.mangaTitle,
                                "mangaCover" to it.mangaCover,
                                "chapterId" to it.chapterId,
                                "chapterNumber" to it.chapterNumber,
                                "chapterTitle" to it.chapterTitle,
                                "readAt" to it.readAt
                            )
                        },
                        "lastUpdated" to System.currentTimeMillis()
                    )
                    db.collection(COLLECTION).document(user.email).set(data)
                        .addOnFailureListener { Log.w(TAG, "recordChapterRead failed", it) }
                }
                .addOnFailureListener { Log.w(TAG, "recordChapterRead fetch failed", it) }
        } catch (e: Exception) {
            Log.w(TAG, "recordChapterRead exception", e)
        }
    }

    /** Listen to the current user's reading history in real-time. */
    fun listenToHistory(
        email: String,
        onUpdate: (List<HistoryEntry>) -> Unit,
        onError: ((Exception) -> Unit)? = null
    ): ListenerRegistration {
        return db.collection(COLLECTION).document(email)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "listen error", error)
                    onError?.invoke(error)
                    return@addSnapshotListener
                }
                if (snapshot == null || !snapshot.exists()) {
                    onUpdate(emptyList())
                    return@addSnapshotListener
                }
                onUpdate(parseEntries(snapshot))
            }
    }

    /** Clear all history for the current user. */
    fun clearHistory(context: Context, onDone: () -> Unit) {
        val user = AuthManager.getCurrentUser(context) ?: run { onDone(); return }
        db.collection(COLLECTION).document(user.email).delete()
            .addOnCompleteListener { onDone() }
    }

    private fun parseEntries(doc: com.google.firebase.firestore.DocumentSnapshot): List<HistoryEntry> {
        val arr = doc.get("entries") as? List<*> ?: return emptyList()
        return arr.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            HistoryEntry(
                mangaId = map["mangaId"] as? String ?: "",
                mangaTitle = map["mangaTitle"] as? String ?: "",
                mangaCover = map["mangaCover"] as? String ?: "",
                chapterId = map["chapterId"] as? String ?: "",
                chapterNumber = map["chapterNumber"] as? String ?: "",
                chapterTitle = map["chapterTitle"] as? String ?: "",
                readAt = (map["readAt"] as? Number)?.toLong() ?: 0L
            )
        }.sortedByDescending { it.readAt }
    }
}
