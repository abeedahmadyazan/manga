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
                android.util.Log.w("MangaApp", "Security: ${e.message}")
            }

            try {
                val db = FirebaseFirestore.getInstance()
                val settings = FirebaseFirestoreSettings.Builder()
                    .setPersistenceEnabled(true)
                    .setCacheSizeBytes(100L * 1024 * 1024)
                    .build()
                db.firestoreSettings = settings
            } catch (e: Exception) {
                android.util.Log.w("MangaApp", "Firestore: ${e.message}")
            }

            try {
                val firebaseAuth = FirebaseAuth.getInstance()
                if (firebaseAuth.currentUser == null) {
                    firebaseAuth.signInAnonymously()
                        .addOnFailureListener { e ->
                            android.util.Log.w("MangaApp", "Auth: ${e.message}")
                        }
                } else {
                    com.yazan.manga.data.AuthManager.restoreUserFromCloud(this)
                }
            } catch (e: Exception) {
                android.util.Log.w("MangaApp", "Auth: ${e.message}")
            }
        }.start()
    }
}
