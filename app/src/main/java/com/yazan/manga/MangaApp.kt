package com.yazan.manga

import android.app.Application
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

        // Sign in anonymously on app start. This guarantees a Firebase Auth
        // UID is always available, so Firestore rules that check
        // `request.auth != null` will pass.
        //
        // If the user later signs in with Google, the anonymous account is
        // upgraded (same UID is kept, just gets Google credentials linked).
        val firebaseAuth = FirebaseAuth.getInstance()
        if (firebaseAuth.currentUser == null) {
            firebaseAuth.signInAnonymously()
                .addOnSuccessListener {
                    android.util.Log.d("MangaApp", "Anonymous auth success: UID=${it.user?.uid}")
                }
                .addOnFailureListener { e ->
                    android.util.Log.w("MangaApp", "Anonymous auth failed: ${e.message}")
                }
        } else {
            android.util.Log.d("MangaApp", "Already signed in: UID=${firebaseAuth.currentUser?.uid}")
        }
    }
}
