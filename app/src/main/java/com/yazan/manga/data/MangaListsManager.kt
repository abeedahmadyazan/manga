package com.yazan.manga.data

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Cloud-backed manga lists for each user.
 *
 * Each user has 4 custom lists:
 *  - favorites (المفضلة)
 *  - watchLater (أتابع لاحقاً)
 *  - wantToWatch (أرغب بمتابعتها)
 *  - completed (أنهيتها)
 *
 * Stored in Firestore: user_lists/{email} → { favorites: [...], watchLater: [...], ... }
 * Each entry is a lightweight manga summary { id, title, cover }.
 *
 * Changes are real-time: any device sees the latest lists instantly.
 */
object MangaListsManager {

    private const val TAG = "MangaLists"
    private const val COLLECTION = "user_lists"

    private val db by lazy { FirebaseFirestore.getInstance() }

    enum class ListType(val key: String, val label: String) {
        FAVORITES("favorites", "المفضلة"),
        WATCH_LATER("watchLater", "أتابع لاحقاً"),
        WANT_TO_WATCH("wantToWatch", "أرغب بمتابعتها"),
        COMPLETED("completed", "أنهيتها")
    }

    data class MangaEntry(
        val id: String = "",
        val title: String = "",
        val cover: String = ""
    )

    data class UserLists(
        val favorites: List<MangaEntry> = emptyList(),
        val watchLater: List<MangaEntry> = emptyList(),
        val wantToWatch: List<MangaEntry> = emptyList(),
        val completed: List<MangaEntry> = emptyList()
    ) {
        val total: Int get() = favorites.size + watchLater.size + wantToWatch.size + completed.size
        fun countOf(type: ListType): Int = when (type) {
            ListType.FAVORITES -> favorites.size
            ListType.WATCH_LATER -> watchLater.size
            ListType.WANT_TO_WATCH -> wantToWatch.size
            ListType.COMPLETED -> completed.size
        }
    }

    /** Listen to the current user's lists in real-time. */
    fun listenToMyLists(
        email: String,
        onUpdate: (UserLists) -> Unit,
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
                    onUpdate(UserLists())
                    return@addSnapshotListener
                }
                onUpdate(parseLists(snapshot))
            }
    }

    /** Add a manga to a list (idempotent — won't duplicate). */
    fun addToList(context: Context, email: String, type: ListType, manga: MangaEntry) {
        db.collection(COLLECTION).document(email).get()
            .addOnSuccessListener { doc ->
                val current = if (doc.exists()) parseLists(doc) else UserLists()
                val list = getList(current, type).toMutableList()
                if (list.none { it.id == manga.id }) {
                    list.add(manga)
                    updateList(email, type, list)
                }
            }
            .addOnFailureListener { Log.w(TAG, "addToList failed", it) }
    }

    /** Remove a manga from a list. */
    fun removeFromList(context: Context, email: String, type: ListType, mangaId: String) {
        db.collection(COLLECTION).document(email).get()
            .addOnSuccessListener { doc ->
                val current = if (doc.exists()) parseLists(doc) else UserLists()
                val list = getList(current, type).filterNot { it.id == mangaId }
                updateList(email, type, list)
            }
            .addOnFailureListener { Log.w(TAG, "removeFromList failed", it) }
    }

    /** Check if a manga is in a specific list (one-shot, synchronous-ish via callback). */
    fun isInList(email: String, type: ListType, mangaId: String, onResult: (Boolean) -> Unit) {
        db.collection(COLLECTION).document(email).get()
            .addOnSuccessListener { doc ->
                val lists = if (doc.exists()) parseLists(doc) else UserLists()
                onResult(getList(lists, type).any { it.id == mangaId })
            }
            .addOnFailureListener { onResult(false) }
    }

    private fun getList(lists: UserLists, type: ListType): List<MangaEntry> = when (type) {
        ListType.FAVORITES -> lists.favorites
        ListType.WATCH_LATER -> lists.watchLater
        ListType.WANT_TO_WATCH -> lists.wantToWatch
        ListType.COMPLETED -> lists.completed
    }

    private fun updateList(email: String, type: ListType, list: List<MangaEntry>) {
        val field = type.key
        val data = mapOf<String, Any>(field to list.map {
            mapOf("id" to it.id, "title" to it.title, "cover" to it.cover)
        })
        db.collection(COLLECTION).document(email)
            .set(data, com.google.firebase.firestore.SetOptions.merge())
            .addOnFailureListener { Log.w(TAG, "updateList failed", it) }
    }

    private fun parseLists(doc: com.google.firebase.firestore.DocumentSnapshot): UserLists {
        fun parse(field: String): List<MangaEntry> {
            val arr = doc.get(field) as? List<*> ?: return emptyList()
            return arr.mapNotNull { item ->
                val map = item as? Map<*, *> ?: return@mapNotNull null
                MangaEntry(
                    id = map["id"] as? String ?: "",
                    title = map["title"] as? String ?: "",
                    cover = map["cover"] as? String ?: ""
                )
            }
        }
        return UserLists(
            favorites = parse("favorites"),
            watchLater = parse("watchLater"),
            wantToWatch = parse("wantToWatch"),
            completed = parse("completed")
        )
    }
}
