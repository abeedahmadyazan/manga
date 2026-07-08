package com.yazan.manga

import android.app.Application
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings

/**
 * Application class — sets up Firebase Firestore with offline persistence
 * enabled so the app works better offline and consumes fewer reads when
 * the user revisits the same data.
 */
class MangaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val db = FirebaseFirestore.getInstance()
        val settings = FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(true)
            // Cache 100 MB of Firestore data locally (default is 40 MB)
            .setCacheSizeBytes(100L * 1024 * 1024)
            .build()
        db.firestoreSettings = settings
    }
}
