package com.yazan.manga

import android.app.Application
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.yazan.manga.data.AntiDebug
import com.yazan.manga.data.AntiTampering

/**
 * Application class — sets up Firebase Firestore with offline persistence
 * enabled so the app works better offline and consumes fewer reads when
 * the user revisits the same data.
 *
 * Also runs startup security checks (AntiDebug, AntiTampering).
 */
class MangaApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize AntiDebug with the app context (it needs it for
        // package manager queries and process name lookups).
        AntiDebug.init(this)

        // Run startup security checks. We don't crash on detection —
        // we let AntiDebug mark the device as compromised so DDoSProtection
        // and other layers can apply stricter rules silently.
        val compromised = AntiDebug.isCompromised(this)
        val rooted = AntiTampering.isLikelyRooted()
        val emulator = AntiTampering.isEmulator()

        // Log for debugging (will be stripped in release by ProGuard)
        android.util.Log.d("MangaApp", "Security: compromised=$compromised rooted=$rooted emulator=$emulator")

        // Set up Firestore with offline persistence
        val db = FirebaseFirestore.getInstance()
        val settings = FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(true)
            .setCacheSizeBytes(100L * 1024 * 1024)
            .build()
        db.firestoreSettings = settings
    }
}

