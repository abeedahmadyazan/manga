package com.yazan.manga

import android.app.Application
import android.content.Context
import android.util.Log
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
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
        private const val TAG = "MangaApp"
        lateinit var appContext: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = this

        // NOTE: Global crash handler REMOVED. It was swallowing real errors
        // and leaving the app in a broken state → eventually ANR.
        // Instead, every function has proper try-catch error handling.

        // ALL heavy initialization on BACKGROUND THREAD (prevents ANR)
        Thread {
            try {
                AntiDebug.init(this)
                AntiDebug.isCompromised(this)
                AntiTampering.isLikelyRooted()
                AntiTampering.isEmulator()
            } catch (e: Exception) {
                Log.w(TAG, "Security: ${e.message}")
            }

            try {
                val db = FirebaseFirestore.getInstance()
                val settings = FirebaseFirestoreSettings.Builder()
                    .setPersistenceEnabled(true)
                    .setCacheSizeBytes(100L * 1024 * 1024)
                    .build()
                db.firestoreSettings = settings
            } catch (e: Exception) {
                Log.w(TAG, "Firestore: ${e.message}")
            }

            // App Check — async, non-blocking, never crashes the app.
            // We use PlayIntegrity for release builds. The token is fetched
            // lazily on the first API call, so we just install the factory
            // and pre-warm the token.
            //
            // IMPORTANT: Run this in the background thread because Play
            // Integrity makes a network call to Google Play Services that
            // would cause ANR if run on the main thread.
            initAppCheckAsync()

            try {
                val firebaseAuth = FirebaseAuth.getInstance()
                if (firebaseAuth.currentUser == null) {
                    firebaseAuth.signInAnonymously()
                        .addOnFailureListener { e ->
                            Log.w(TAG, "Auth: ${e.message}")
                        }
                } else {
                    com.yazan.manga.data.AuthManager.restoreUserFromCloud(this)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Auth: ${e.message}")
            }
        }.start()
    }

    /**
     * Initialize Firebase App Check asynchronously.
     *
     * App Check verifies app authenticity via Play Integrity. It must run
     * in the background because:
     * 1. Play Integrity makes a network call to Google Play Services.
     * 2. Running it on the main thread causes ANR.
     *
     * Failure is non-fatal — the app still works; App Check just won't
     * enforce (Firebase will be in "log only" mode until you switch to
     * "enforce" in the console).
     */
    private fun initAppCheckAsync() {
        try {
            val firebaseAppCheck = FirebaseAppCheck.getInstance()
            firebaseAppCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
            // Pre-warm the token so the first API call doesn't fail.
            // This is async — it won't block the thread.
            firebaseAppCheck.getAppCheckToken(false)
                .addOnSuccessListener { Log.d(TAG, "App Check token ready") }
                .addOnFailureListener { e ->
                    Log.w(TAG, "App Check token deferred (non-fatal): ${e.message}")
                }
        } catch (e: Exception) {
            Log.w(TAG, "App Check init failed (non-fatal): ${e.message}")
        }
    }
}
