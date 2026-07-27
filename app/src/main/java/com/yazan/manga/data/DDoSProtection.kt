package com.yazan.manga.data

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * DDoSProtection — client-side rate limiting + circuit breaker.
 *
 * Why client-side? Because we can't trust Firestore quota limits to protect
 * the app — a single malicious user with a script can blow through the
 * 50,000 free-tier reads/day in minutes. We enforce strict client-side
 * budgets BEFORE any network call is made.
 *
 * Three layers of defense:
 *
 * 1. Per-action rate limit: max N calls per minute per action type
 *    (e.g. max 20 manga searches / minute, max 5 chapter reads / minute)
 *
 * 2. Global daily budget: max N network calls / day. If exceeded, refuse
 *    all network calls until midnight. Prevents a runaway loop or
 *    attacker from exhausting the daily quota.
 *
 * 3. Circuit breaker: if the last K consecutive requests failed, "trip"
 *    the circuit and refuse all calls for a cooldown period. This
 *    protects against hammering a dead server.
 *
 * All state is in-memory (per app session). Persisting daily budget
 * across sessions would require SharedPreferences, but for our use case
 * (per-session protection) in-memory is fine.
 */
object DDoSProtection {

    private const val TAG = "DDoSProtection"

    // ============================================================
    //  Per-action rate limits
    // ============================================================

    enum class Action(val maxPerMinute: Int) {
        MANGA_LIST(30),        // homepage + pagination
        MANGA_SEARCH(20),      // search
        MANGA_DETAILS(20),     // open manga
        CHAPTER_PAGES(60),     // reader (each page = 1 call)
        CHAPTER_DOWNLOAD(5),   // downloads (1 per chapter)
        COMMENT_POST(2),       // posting comments
        COMMENT_LIST(30),      // fetching comments
        AUTH(5),               // login attempts
        GENERIC(60)            // anything else
    }

    private val actionHits: ConcurrentHashMap<Action, MutableList<Long>> =
        ConcurrentHashMap()

    /**
     * Returns true if the caller is allowed to perform [action] right now.
     * Side effect: records the timestamp of the call.
     */
    @Synchronized
    fun tryAcquire(action: Action): Boolean {
        if (isCircuitTripped()) {
            Log.w(TAG, "Circuit tripped — refusing $action")
            return false
        }
        if (isDailyBudgetExhausted()) {
            Log.w(TAG, "Daily budget exhausted — refusing $action")
            return false
        }

        val now = System.currentTimeMillis()
        val windowMs = 60_000L
        val hits = actionHits.getOrPut(action) { mutableListOf() }

        // Drop hits older than 1 minute
        hits.removeAll { it < now - windowMs }

        if (hits.size >= action.maxPerMinute) {
            Log.w(TAG, "Rate limit hit for $action: ${hits.size}/${action.maxPerMinute} per min")
            // If user keeps hitting the rate limit repeatedly, that's a
            // signal they're a bot. Count consecutive violations and trip
            // the circuit if they pile up.
            recordViolation()
            return false
        }

        hits.add(now)
        recordSuccess()
        return true
    }

    // ============================================================
    //  Daily budget (global across all actions)
    // ============================================================

    private const val DAILY_BUDGET = 4_000  // leave 46k of the 50k Firestore budget as headroom

    private val dailyCount = AtomicInteger(0)
    private val dailyResetDay = AtomicInteger(-1)

    private fun isDailyBudgetExhausted(): Boolean {
        val today = (System.currentTimeMillis() / (24 * 60 * 60 * 1000)).toInt()
        if (dailyResetDay.get() != today) {
            dailyResetDay.set(today)
            dailyCount.set(0)
        }
        return dailyCount.incrementAndGet() > DAILY_BUDGET
    }

    /** How much of the daily budget is left (for diagnostics / UI). */
    fun dailyBudgetRemaining(): Int = (DAILY_BUDGET - dailyCount.get()).coerceAtLeast(0)

    // ============================================================
    //  Circuit breaker
    // ============================================================

    private const val CIRCUIT_TRIP_THRESHOLD = 8     // 8 consecutive failures → trip
    private const val CIRCUIT_COOLDOWN_MS = 30_000L  // 30s cooldown
    private const val VIOLATION_TRIP_THRESHOLD = 5   // 5 rate-limit violations in 1 min → trip

    private val consecutiveFailures = AtomicInteger(0)
    private val circuitTrippedUntil = AtomicLong(0)
    private val recentViolations = mutableListOf<Long>()

    @Synchronized
    private fun recordFailure() {
        val fails = consecutiveFailures.incrementAndGet()
        if (fails >= CIRCUIT_TRIP_THRESHOLD) {
            circuitTrippedUntil.set(System.currentTimeMillis() + CIRCUIT_COOLDOWN_MS)
            Log.w(TAG, "Circuit tripped for ${CIRCUIT_COOLDOWN_MS}ms after $fails failures")
            consecutiveFailures.set(0)
        }
    }

    private fun recordSuccess() {
        consecutiveFailures.set(0)
    }

    @Synchronized
    private fun recordViolation() {
        val now = System.currentTimeMillis()
        recentViolations.removeAll { it < now - 60_000 }
        recentViolations.add(now)
        if (recentViolations.size >= VIOLATION_TRIP_THRESHOLD) {
            circuitTrippedUntil.set(now + CIRCUIT_COOLDOWN_MS)
            Log.w(TAG, "Circuit tripped: ${recentViolations.size} violations in 1 min")
            recentViolations.clear()
        }
    }

    private fun isCircuitTripped(): Boolean {
        return circuitTrippedUntil.get() > System.currentTimeMillis()
    }

    /** Public: report that a network call failed. Used by repositories. */
    fun reportFailure() = recordFailure()

    /** Public: report that a network call succeeded. */
    fun reportSuccess() = recordSuccess()

    /** Public: check the circuit state (for UI / debug). */
    fun circuitState(): String = when {
        isCircuitTripped() -> "tripped"
        isDailyBudgetExhausted() -> "budget_exhausted"
        else -> "ok"
    }
}
