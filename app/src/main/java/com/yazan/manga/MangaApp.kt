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

        // === App Check: verify app integrity before any Firebase call ===
        // This prevents scripts/bots from using Firestore directly with our
        // API keys. Tokens are auto-refreshed (TTL 1h).
        val firebaseAppCheck = com.google.firebase.appcheck.FirebaseAppCheck.getInstance()
        firebaseAppCheck.installAppCheckProviderFactory(
            com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory.getInstance()
        )

        // Set up Firestore with offline persistence
        val db = FirebaseFirestore.getInstance()
        val settings = FirebaseFirestoreSettings.Builder()
            .setPersistenceEnabled(true)
            .setCacheSizeBytes(100L * 1024 * 1024)
            .build()
        db.firestoreSettings = settings

        // Sign in anonymously on app start. This is OPTIONAL — if it fails,
        // the app still works (just without cloud sync). The Account Picker
        // fallback in ProfileActivity handles the case where Google Sign-In
        // doesn't work.
        //
        // We keep this because it helps when Anonymous is enabled in Firebase
        // Console — gives a UID so Firestore rules pass.
        val firebaseAuth = FirebaseAuth.getInstance()
        if (firebaseAuth.currentUser == null) {
            try {
                firebaseAuth.signInAnonymously()
                    .addOnSuccessListener {
                        android.util.Log.d("MangaApp", "Anonymous auth success: UID=${it.user?.uid}")
                        // After anonymous auth, try to restore user from cloud.
                        // This handles the reinstall case where local data is gone
                        // but the user's email is in Firebase Auth.
                        com.yazan.manga.data.AuthManager.restoreUserFromCloud(this)
                    }
                    .addOnFailureListener { e ->
                        android.util.Log.w("MangaApp", "Anonymous auth failed (this is OK): ${e.message}")
                    }
            } catch (e: Exception) {
                android.util.Log.w("MangaApp", "Anonymous auth exception: ${e.message}")
            }
        } else {
            android.util.Log.d("MangaApp", "Already signed in: UID=${firebaseAuth.currentUser?.uid}")
            // User already signed in — try to restore their profile from cloud.
            // This is the key fix: even if local data is gone (reinstall),
            // we restore name/avatar/username from Firestore.
            com.yazan.manga.data.AuthManager.restoreUserFromCloud(this)
        }
    }
}
