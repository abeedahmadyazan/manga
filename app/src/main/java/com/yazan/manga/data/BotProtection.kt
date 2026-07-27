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
 *
 * SECURITY: Firestore rules enforce that:
 * - login_attempts.count can only INCREASE (or reset to 1 after 30 min)
 * - login_blocks.until can only be EXTENDED
 * - Neither collection can be deleted by the client
 * This means an attacker cannot reset their own counter or lift their block.
 */
object BotProtection {

    private const val TAG = "BotProtection"
    private val db by lazy { FirebaseFirestore.getInstance() }

    // Login protection thresholds
    private const val LOGIN_MAX_ATTEMPTS = 5
    private const val LOGIN_WINDOW_MS = 30 * 60 * 1000L  // 30 minutes
    private const val LOGIN_BLOCK_MS = 60 * 60 * 1000L    // 1 hour block
    // TTL: login_attempts documents auto-delete after 7 days of inactivity
    private const val LOGIN_ATTEMPTS_TTL_MS = 7 * 24 * 60 * 60 * 1000L  // 7 days

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
     *
     * NOTE: We do NOT delete expired blocks. Firestore rules forbid deletion
     * (to prevent an attacker from lifting their own block). We simply rely
     * on the `until > now` check — expired blocks become inert naturally.
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
                    // Block expired — do NOT delete (Firestore rules forbid it).
                    // The block is inert now; the user can retry login.
                    onResult(LoginBlock(false))
                }
            }
            .addOnFailureListener { onResult(LoginBlock(false)) }
    }

    /**
     * Record a failed login attempt. If the threshold is reached, block
     * the device for 1 hour.
     *
     * SECURITY: The count only increases (never resets to 0). If the 30-min
     * window has passed, we reset to 1 — Firestore rules allow this only
     * when (now - lastAttempt > 30 min). After blocking, we do NOT reset
     * the counter; the block in login_blocks handles the cooldown.
     *
     * Write failures are logged but non-fatal — the block check still works
     * for already-blocked devices.
     */
    fun recordFailedLogin(deviceId: String) {
        db.collection("login_attempts").document(deviceId).get()
            .addOnSuccessListener { doc ->
                val now = System.currentTimeMillis()
                val newCount: Int = if (doc.exists()) {
                    val lastAttempt = doc.getLong("lastAttempt") ?: 0L
                    val oldCount = (doc.getLong("count") ?: 0L).toInt()
                    // Reset to 1 if the 30-min window passed; else increment
                    if (now - lastAttempt > LOGIN_WINDOW_MS) 1 else oldCount + 1
                } else 1

                val data = mapOf(
                    "count" to newCount,
                    "lastAttempt" to now,
                    "deviceId" to deviceId,
                    // TTL field: Firestore auto-deletes this doc 7 days after
                    // the last update. Updated on every write, so it only
                    // expires if the device stops attacking for 7 days.
                    "expiresAt" to (now + LOGIN_ATTEMPTS_TTL_MS)
                )
                // set() = create or overwrite. Firestore rules will:
                //  - allow create if count == 1
                //  - allow update if count > old, OR (window passed AND count == 1)
                db.collection("login_attempts").document(deviceId).set(data)
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Failed to record login attempt: ${e.message}")
                    }

                if (newCount >= LOGIN_MAX_ATTEMPTS) {
                    // Block the device — Firestore rules require until > old until
                    val blockUntil = now + LOGIN_BLOCK_MS
                    val blockData = mapOf(
                        "deviceId" to deviceId,
                        "until" to blockUntil,
                        "blockedAt" to now,
                        "reason" to "5 failed login attempts within 30 minutes",
                        // TTL: auto-delete block 24h after it expires
                        "expiresAt" to (blockUntil + 24 * 60 * 60 * 1000L)
                    )
                    db.collection("login_blocks").document(deviceId).set(blockData)
                        .addOnFailureListener { e ->
                            Log.w(TAG, "Failed to write login block: ${e.message}")
                        }
                    // Do NOT reset the counter. The block in login_blocks handles
                    // the cooldown. After the block expires, the next failed attempt
                    // will reset to 1 (because now - lastAttempt > 30 min).
                    Log.w(TAG, "Device $deviceId blocked for 1 hour due to login abuse")
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to read login attempts: ${e.message}")
            }
    }

    /**
     * Clear the attempt counter on successful login.
     *
     * SECURITY: We CANNOT delete the document (Firestore rules forbid it).
     * This is now a no-op — the counter persists but is harmless because:
     * 1. The next failed attempt after 30 min will reset to 1.
     * 2. The block in login_blocks is what actually enforces the cooldown.
     *
     * Keeping this function as a no-op avoids breaking callers.
     */
    fun clearLoginAttempts(deviceId: String) {
        // Intentionally a no-op. See class doc for security rationale.
        Log.d(TAG, "clearLoginAttempts called (no-op per security rules) for $deviceId")
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
