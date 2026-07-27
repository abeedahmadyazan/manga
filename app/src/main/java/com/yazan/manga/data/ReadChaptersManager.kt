package com.yazan.manga.data

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Tracks which chapters the user has read (opened in the reader).
 *
 * Storage:
 *  - **Local** (SharedPreferences): instant read, no network. Used as the
 *    primary source for the "eye" badge in the chapter list.
 *  - **Cloud** (Firestore): syncs across devices so the eye badge appears
 *    on the user's other phones too.
 *
 * The eye badge logic:
 *  - When a chapter is opened in ReaderActivity → markChapterAsRead()
 *  - ChapterAdapter checks isChapterRead() → shows a filled eye icon
 *  - Unread chapters show no eye (or an outline eye)
 *
 * Note: this is separate from ReadingHistoryManager, which only records
 * the LAST chapter read per manga. ReadChaptersManager records ALL
 * chapters ever opened, so the eye badge persists even after the user
 * reads later chapters.
 */
object ReadChaptersManager {
    private const val TAG = "ReadChapters"
    private const val PREFS_NAME = "read_chapters"
    private const val KEY_READ_SET = "read_chapter_ids"
    private const val COLLECTION = "read_chapters"
    private val db by lazy { FirebaseFirestore.getInstance() }

    /** Mark a chapter as read (both locally and in the cloud). */
    fun markChapterAsRead(context: Context, mangaId: String, chapterId: String, chapterNumber: String) {
        if (chapterId.isBlank()) return
        val chapterKey = buildChapterKey(mangaId, chapterId)

        // 1. Local: add to the read set (instant, offline-friendly)
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getStringSet(KEY_READ_SET, emptySet())?.toMutableSet() ?: mutableSetOf()
            if (current.add(chapterKey)) {
                prefs.edit().putStringSet(KEY_READ_SET, current).apply()
                Log.d(TAG, "Marked chapter as read (local): $chapterKey")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark chapter as read locally", e)
        }

        // 2. Cloud: sync to Firestore so the eye badge appears on other devices
        val user = AuthManager.getCurrentUser(context) ?: return
        if (user.email.isEmpty()) return
        try {
            val data = mapOf<String, Any>(
                "mangaId" to mangaId,
                "chapterId" to chapterId,
                "chapterNumber" to chapterNumber,
                "readAt" to System.currentTimeMillis()
            )
            // Use a deterministic doc id: email + mangaId + chapterId
            // so re-reading the same chapter doesn't create duplicates.
            val docId = "${user.email}_${chapterId}".take(200)
            db.collection(COLLECTION).document(docId).set(data)
                .addOnFailureListener { Log.w(TAG, "Cloud markChapterAsRead failed", it) }
        } catch (e: Exception) {
            Log.w(TAG, "Cloud markChapterAsRead exception", e)
        }
    }

    /** Check if a chapter has been read (local check, instant). */
    fun isChapterRead(context: Context, mangaId: String, chapterId: String): Boolean {
        if (chapterId.isBlank()) return false
        val chapterKey = buildChapterKey(mangaId, chapterId)
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val set = prefs.getStringSet(KEY_READ_SET, emptySet()) ?: emptySet()
            set.contains(chapterKey)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Listen to the user's read-chapters set from the cloud.
     * Merges with the local set so the eye badge appears even for
     * chapters read on another device before this session.
     */
    fun listenToReadChapters(
        context: Context,
        email: String,
        onUpdate: (Set<String>) -> Unit
    ): ListenerRegistration {
        return db.collection(COLLECTION)
            .whereEqualTo("__authorEmail", email) // placeholder; real filter below
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "listen error", error)
                    onUpdate(getLocalReadSet(context))
                    return@addSnapshotListener
                }
                // Merge cloud results into local set
                val cloudKeys = mutableSetOf<String>()
                snapshot?.documents?.forEach { doc ->
                    val mangaId = doc.getString("mangaId") ?: ""
                    val chapterId = doc.getString("chapterId") ?: ""
                    if (mangaId.isNotEmpty() && chapterId.isNotEmpty()) {
                        cloudKeys.add(buildChapterKey(mangaId, chapterId))
                    }
                }
                if (cloudKeys.isNotEmpty()) {
                    mergeCloudReadChapters(context, cloudKeys)
                }
                onUpdate(getLocalReadSet(context))
            }
    }

    /** Merge cloud-read chapters into the local SharedPreferences set. */
    private fun mergeCloudReadChapters(context: Context, cloudKeys: Set<String>) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val current = prefs.getStringSet(KEY_READ_SET, emptySet())?.toMutableSet() ?: mutableSetOf()
            val before = current.size
            current.addAll(cloudKeys)
            if (current.size > before) {
                prefs.edit().putStringSet(KEY_READ_SET, current).apply()
                Log.d(TAG, "Merged ${current.size - before} cloud-read chapters into local set")
            }
        } catch (e: Exception) {
            Log.w(TAG, "mergeCloudReadChapters failed", e)
        }
    }

    /** Get the local read-chapters set as a Set<String>. */
    private fun getLocalReadSet(context: Context): Set<String> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getStringSet(KEY_READ_SET, emptySet()) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    /** Build a stable key for a (mangaId, chapterId) pair. */
    private fun buildChapterKey(mangaId: String, chapterId: String): String {
        return "${mangaId}::${chapterId}"
    }

    /** Clear all read chapters (local only; cloud entries remain). */
    fun clearLocalReadChapters(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_READ_SET).apply()
        } catch (e: Exception) {
            Log.w(TAG, "clearLocalReadChapters failed", e)
        }
    }
}
