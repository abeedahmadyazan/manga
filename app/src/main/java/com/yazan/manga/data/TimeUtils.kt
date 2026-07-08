package com.yazan.manga.data

import java.util.concurrent.TimeUnit

/**
 * Converts a timestamp (milliseconds) to a relative Arabic time string:
 *  - < 1 minute  → "منذ ثوان"
 *  - 1-59 min    → "منذ X دقيقة"
 *  - 1-23 hours  → "منذ X ساعة"
 *  - 1-29 days   → "منذ X يوم"
 *  - 30-364 days → "منذ X شهر"
 *  - >= 365 days → "منذ X سنة"
 */
fun relativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    if (diff < 0) return "الآن"

    val seconds = TimeUnit.MILLISECONDS.toSeconds(diff)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)

    return when {
        seconds < 60 -> "منذ ثوان"
        minutes < 60 -> {
            val m = minutes.toInt()
            "منذ $m ${if (m == 1) "دقيقة" else if (m == 2) "دقيقتين" else "دقيقة"}"
        }
        hours < 24 -> {
            val h = hours.toInt()
            "منذ $h ${if (h == 1) "ساعة" else if (h == 2) "ساعتين" else "ساعة"}"
        }
        days < 30 -> {
            val d = days.toInt()
            "منذ $d ${if (d == 1) "يوم" else if (d == 2) "يومين" else "يوم"}"
        }
        days < 365 -> {
            val months = (days / 30).toInt()
            "منذ $months ${if (months == 1) "شهر" else if (months == 2) "شهرين" else "شهر"}"
        }
        else -> {
            val years = (days / 365).toInt()
            "منذ $years ${if (years == 1) "سنة" else if (years == 2) "سنتين" else "سنة"}"
        }
    }
}
