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
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("MangaApp", "UNCAUGHT on ${thread.name}: ${throwable.message}", throwable)
        }

        // === ALL heavy initialization moved to BACKGROUND THREAD ===
        // This prevents ANR ("التطبيق لا يستجيب") on app startup.
        // The UI thread stays free to render the screen immediately.
        Thread {
            try {
                // Security checks (non-blocking now — on background thread)
                AntiDebug.init(this)
                val compromised = AntiDebug.isCompromised(this)
                val rooted = AntiTampering.isLikelyRooted()
                val emulator = AntiTampering.isEmulator()
                android.util.Log.d("MangaApp", "Security: compromised=$compromised rooted=$rooted emulator=$emulator")
            } catch (e: Exception) {
                android.util.Log.w("MangaApp", "Security check failed: ${e.message}")
            }

            try {
                // Firestore setup
                val db = FirebaseFirestore.getInstance()
                val settings = FirebaseFirestoreSettings.Builder()
                    .setPersistenceEnabled(true)
                    .setCacheSizeBytes(100L * 1024 * 1024)
                    .build()
                db.firestoreSettings = settings
            } catch (e: Exception) {
                android.util.Log.w("MangaApp", "Firestore setup failed: ${e.message}")
            }

            try {
                // Anonymous auth
                val firebaseAuth = FirebaseAuth.getInstance()
                if (firebaseAuth.currentUser == null) {
                    firebaseAuth.signInAnonymously()
                        .addOnSuccessListener {
                            android.util.Log.d("MangaApp", "Anonymous auth success")
                        }
                        .addOnFailureListener { e ->
                            android.util.Log.w("MangaApp", "Anonymous auth failed: ${e.message}")
                        }
                } else {
                    com.yazan.manga.data.AuthManager.restoreUserFromCloud(this)
                }
            } catch (e: Exception) {
                android.util.Log.w("MangaApp", "Auth setup failed: ${e.message}")
            }
        }.start()

        // NOTE: FirebaseAppCheck REMOVED — it was causing ANR by blocking
        // the main thread while contacting Google Play Services. The app
        // works fine without it; Firestore rules still protect the data.
    }
}
