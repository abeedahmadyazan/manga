package com.yazan.manga.data

import android.content.Context
import android.util.Log

/**
 * SpamFilter — client-side comment content moderation.
 *
 * Three layers:
 *
 * 1. Banned words: a list of slurs / insults / spam keywords. Comments
 *    containing them are rejected outright with a friendly message.
 *
 * 2. Repetition check: if the same user posts the same text 3+ times
 *    within an hour, refuse further identical posts.
 *
 * 3. Length & format rules: min 1 char, max 500 chars, no excessive
 *    caps (>70% uppercase = shouting), no excessive emoji (>5 emoji).
 *
 * The banned-words list is intentionally short — we're not building a
 * general-purpose moderation system. The goal is to catch the most
 * obvious cases and let the report system handle the rest.
 */
object SpamFilter {

    private const val PREFS_NAME = "spam_filter"
    private const val KEY_RECENT_POSTS = "recent_posts"
    private const val MAX_POST_LENGTH = 500
    private const val MIN_POST_LENGTH = 1
    private const val MAX_RECENT_POSTS = 20
    private const val REPEAT_WINDOW_MS = 60 * 60 * 1000L  // 1 hour
    private const val REPEAT_THRESHOLD = 3

    /**
     * Banned words/phrases. Matched case-insensitively as substrings.
     * Keep this list short and obvious — false positives are worse than
     * false negatives here.
     */
    private val BANNED_WORDS = listOf(
        // Generic insults (Arabic)
        "كلب", "حمار", "خنزير", "ابن", "قحبة", "عاهرة", "شاذ",
        // English equivalents
        "fuck", "shit", "bitch", "asshole", "dick", "pussy",
        // Spam patterns
        "http://", "https://", "www.", ".com", "telegram.me", "t.me/",
        "whatsapp.com", "bit.ly", "tinyurl"
    )

    /**
     * Returns null if the comment is OK, or an Arabic error message
     * explaining why it was rejected.
     */
    fun validate(context: Context, text: String): String? {
        val trimmed = text.trim()

        // Length rules
        if (trimmed.length < MIN_POST_LENGTH) {
            return "التعليق فارغ"
        }
        if (trimmed.length > MAX_POST_LENGTH) {
            return "التعليق طويل جداً (الحد الأقصى $MAX_POST_LENGTH حرف)"
        }

        // Banned words
        val lower = trimmed.lowercase()
        for (word in BANNED_WORDS) {
            if (lower.contains(word)) {
                Log.d("SpamFilter", "Banned word matched: $word")
                return "التعليق يحتوي على كلمات غير مسموح بها"
            }
        }

        // Excessive caps (shouting)
        val letters = trimmed.filter { it.isLetter() }
        if (letters.length >= 8) {
            val upperCount = letters.count { it.isUpperCase() }
            if (upperCount.toFloat() / letters.length > 0.7f) {
                return "الرجاء عدم استخدام الأحرف الكبيرة بكثرة"
            }
        }

        // Excessive emoji
        val emojiCount = trimmed.codePoints().filter { it > 0x1F000 }.count().toInt()
        if (emojiCount > 5) {
            return "الرجاء عدم استخدام الإيموجي بكثرة"
        }

        // Repetition check
        if (isRepetition(context, trimmed)) {
            return "لقد نشرت هذا التعليق مرة أخرى مؤخراً"
        }

        // Record this post
        recordPost(context, trimmed)
        return null
    }

    // =============================================================
    //  Repetition tracking (SharedPreferences)
    // =============================================================

    private data class RecentPost(val text: String, val timestamp: Long)

    private fun loadRecentPosts(context: Context): List<RecentPost> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECENT_POSTS, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("||", limit = 2)
            if (parts.size == 2) {
                RecentPost(parts[0], parts[1].toLongOrNull() ?: 0L)
            } else null
        }
    }

    private fun saveRecentPosts(context: Context, posts: List<RecentPost>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = posts.joinToString("\n") { "${it.text}||${it.timestamp}" }
        prefs.edit().putString(KEY_RECENT_POSTS, raw).apply()
    }

    private fun isRepetition(context: Context, text: String): Boolean {
        val now = System.currentTimeMillis()
        val recent = loadRecentPosts(context)
            .filter { it.timestamp > now - REPEAT_WINDOW_MS }
            .filter { it.text == text }
        return recent.size >= REPEAT_THRESHOLD - 1  // -1 because we haven't recorded this one yet
    }

    private fun recordPost(context: Context, text: String) {
        val now = System.currentTimeMillis()
        val recent = loadRecentPosts(context)
            .filter { it.timestamp > now - REPEAT_WINDOW_MS }
            .toMutableList()
        recent.add(RecentPost(text, now))
        // Keep only the most recent MAX_RECENT_POSTS entries
        val trimmed = recent.takeLast(MAX_RECENT_POSTS)
        saveRecentPosts(context, trimmed)
    }

    /** Clear all tracked posts. Called from Settings → "Clear spam filter". */
    fun clearHistory(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_RECENT_POSTS).apply()
    }
}
