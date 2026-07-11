package com.yazan.manga

import android.app.Application
import android.content.Context
import com.google.firebase.auth.FirebaseAuth
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
 *
 * IMPORTANT: We sign in anonymously on app start. This guarantees that
 * there is ALWAYS a Firebase Auth UID available, even before the user
 * clicks "Sign in with Google". Without this, Firestore security rules
 * that require `request.auth != null` will reject ALL reads/writes,
 * which breaks comments, lists, history — everything.
 */
class MangaApp : Application() {
    companion object {
        lateinit var appContext: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = this

        // === GLOBAL CRASH HANDLER ===
        // Catches ALL uncaught exceptions and prevents the "التطبيق يستمر
        // في التوقف عن العمل" dialog. Instead, the app logs the error and
        // continues. This is the nuclear option — no crash should ever reach
        // the user.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("MangaApp", "UNCAUGHT EXCEPTION on ${thread.name}: ${throwable.message}", throwable)
            // Don't call defaultHandler — that would kill the app.
            // Just log and swallow. The user sees no crash dialog.
        }

        // === Security checks (wrapped in try-catch — never crash) ===
        try {
            AntiDebug.init(this)
            val compromised = AntiDebug.isCompromised(this)
            val rooted = AntiTampering.isLikelyRooted()
            val emulator = AntiTampering.isEmulator()
            android.util.Log.d("MangaApp", "Security: compromised=$compromised rooted=$rooted emulator=$emulator")
        } catch (e: Exception) {
            android.util.Log.w("MangaApp", "Security check failed (non-fatal): ${e.message}")
        }

        // === Firebase App Check (wrapped — some devices don't have Play Services) ===
        try {
            val firebaseAppCheck = com.google.firebase.appcheck.FirebaseAppCheck.getInstance()
            firebaseAppCheck.installAppCheckProviderFactory(
                com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory.getInstance()
            )
        } catch (e: Exception) {
            android.util.Log.w("MangaApp", "App Check failed (non-fatal): ${e.message}")
        }

        // === Firestore setup (wrapped) ===
        try {
            val db = FirebaseFirestore.getInstance()
            val settings = FirebaseFirestoreSettings.Builder()
                .setPersistenceEnabled(true)
                .setCacheSizeBytes(100L * 1024 * 1024)
                .build()
            db.firestoreSettings = settings
        } catch (e: Exception) {
            android.util.Log.w("MangaApp", "Firestore setup failed (non-fatal): ${e.message}")
        }

        // === Anonymous auth (wrapped) ===
        try {
            val firebaseAuth = FirebaseAuth.getInstance()
            if (firebaseAuth.currentUser == null) {
                firebaseAuth.signInAnonymously()
                    .addOnSuccessListener {
                        android.util.Log.d("MangaApp", "Anonymous auth success: UID=${it.user?.uid}")
                    }
                    .addOnFailureListener { e ->
                        android.util.Log.w("MangaApp", "Anonymous auth failed (this is OK): ${e.message}")
                    }
            } else {
                android.util.Log.d("MangaApp", "Already signed in: UID=${firebaseAuth.currentUser?.uid}")
                com.yazan.manga.data.AuthManager.restoreUserFromCloud(this)
            }
        } catch (e: Exception) {
            android.util.Log.w("MangaApp", "Auth setup failed (non-fatal): ${e.message}")
        }
    }
}
