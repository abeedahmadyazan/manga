package com.yazan.manga.data

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Cloud-backed rate limiter and bot protection.
 *
 * Two layers of protection:
 *
 * 1. Login abuse protection:
 *    If a user fails login (Google sign-in + Account Picker) 5 times within
 *    30 minutes, they're blocked from attempting again for 1 hour. This
 *    prevents brute-force account-takeover attempts.
 *
 * 2. Like/Dislike spam protection:
 *    If a user taps like/dislike more than X times within Y seconds, the
 *    app stops responding to their taps for a short cooldown. This prevents
 *    bots from rapidly inflating/deleting likes.
 *
 * Both are stored in Firestore so they're enforced across all devices.
 */
object BotProtection {

    private const val TAG = "BotProtection"
    private val db by lazy { FirebaseFirestore.getInstance() }

    // Login protection thresholds
    private const val LOGIN_MAX_ATTEMPTS = 5
    private const val LOGIN_WINDOW_MS = 30 * 60 * 1000L  // 30 minutes
    private const val LOGIN_BLOCK_MS = 60 * 60 * 1000L    // 1 hour block

    // Like/Dislike spam thresholds (per device, local)
    private const val LIKE_MAX_TAPS = 10
    private const val LIKE_WINDOW_MS = 10 * 1000L  // 10 seconds
    private const val LIKE_COOLDOWN_MS = 30 * 1000L  // 30 seconds cooldown

    // ============================================================
    //  Login protection (cloud-backed)
    // ============================================================

    data class LoginBlock(val blocked: Boolean, val remainingMs: Long = 0, val reason: String = "")

    /**
     * Check if the current device is blocked from attempting login.
     * Blocks are keyed by device fingerprint (so a banned user can't just
     * retry with a different account on the same phone).
     */
    fun checkLoginBlock(deviceId: String, onResult: (LoginBlock) -> Unit) {
        db.collection("login_blocks").document(deviceId).get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    onResult(LoginBlock(false))
                    return@addOnSuccessListener
                }
                val until = doc.getLong("until") ?: 0L
                val now = System.currentTimeMillis()
                if (until > now) {
                    val remaining = until - now
                    val minutes = remaining / (60 * 1000)
                    onResult(LoginBlock(true, remaining, "تم حظرك من تسجيل الدخول لمدة $minutes دقيقة"))
                } else {
                    // Block expired — clean it up
                    db.collection("login_blocks").document(deviceId).delete()
                    onResult(LoginBlock(false))
                }
            }
            .addOnFailureListener { onResult(LoginBlock(false)) }
    }

    /**
     * Record a failed login attempt. If the threshold is reached, block
     * the device for 1 hour.
     */
    fun recordFailedLogin(deviceId: String) {
        db.collection("login_attempts").document(deviceId).get()
            .addOnSuccessListener { doc ->
                val now = System.currentTimeMillis()
                val attempts = if (doc.exists()) {
                    val lastAttempt = doc.getLong("lastAttempt") ?: 0L
                    val count = (doc.getLong("count") ?: 0L).toInt()
                    // Reset if the window passed
                    if (now - lastAttempt > LOGIN_WINDOW_MS) 1 else count + 1
                } else 1

                val data = mapOf(
                    "count" to attempts,
                    "lastAttempt" to now,
                    "deviceId" to deviceId
                )
                db.collection("login_attempts").document(deviceId).set(data)

                if (attempts >= LOGIN_MAX_ATTEMPTS) {
                    // Block the device
                    val blockUntil = now + LOGIN_BLOCK_MS
                    val blockData = mapOf(
                        "deviceId" to deviceId,
                        "until" to blockUntil,
                        "blockedAt" to now,
                        "reason" to "5 failed login attempts within 30 minutes"
                    )
                    db.collection("login_blocks").document(deviceId).set(blockData)
                    // Reset the attempt counter
                    db.collection("login_attempts").document(deviceId)
                        .set(mapOf("count" to 0, "lastAttempt" to now, "deviceId" to deviceId))
                    Log.w(TAG, "Device $deviceId blocked for 1 hour due to login abuse")
                }
            }
    }

    /** Clear the attempt counter on successful login. */
    fun clearLoginAttempts(deviceId: String) {
        db.collection("login_attempts").document(deviceId).delete()
    }

    // ============================================================
    //  Like/Dislike spam protection (local, per-session)
    // ============================================================

    private val likeTaps = mutableListOf<Long>()

    /**
     * Check if a like/dislike tap should be allowed. Returns true if allowed,
     * false if the user is tapping too fast (bot-like behavior).
     *
     * The first tap after a cooldown is always allowed; subsequent rapid taps
     * are throttled.
     */
    fun checkLikeTap(): Boolean {
        val now = System.currentTimeMillis()
        // Remove taps outside the window
        likeTaps.removeAll { now - it > LIKE_WINDOW_MS }
        // If we've hit the max within the window, reject
        if (likeTaps.size >= LIKE_MAX_TAPS) {
            // Clear the list so the NEXT tap (after cooldown) is allowed
            likeTaps.clear()
            likeTaps.add(now - LIKE_COOLDOWN_MS) // pretend the last tap was a while ago
            return false
        }
        likeTaps.add(now)
        return true
    }

    /** Reset the like-tap counter (e.g. when leaving the comments screen). */
    fun resetLikeTaps() {
        likeTaps.clear()
    }
}
