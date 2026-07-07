package com.yazan.manga.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object ReportsManager {

    private const val PREFS_NAME = "manga_reports"
    private const val KEY_REPORTS = "all_reports"

    data class Report(
        val id: String,
        val commentId: String,
        val commentText: String,
        val reportedBy: String,
        val reportedByName: String,
        val reason: String,
        val reportedAt: Long,
        val status: String
    )

    fun reportComment(
        context: Context,
        commentId: String,
        commentText: String,
        reason: String
    ): String? {
        val user = AuthManager.getCurrentUser(context) ?: return "يجب تسجيل الدخول"

        val existing = getAllReports(context)
        if (existing.any { it.commentId == commentId && it.reportedBy == user.email }) {
            return "لقد أبلغت عن هذا التعليق مسبقاً"
        }

        val report = Report(
            id = UUID.randomUUID().toString(),
            commentId = commentId,
            commentText = commentText,
            reportedBy = user.email,
            reportedByName = user.name,
            reason = reason.trim(),
            reportedAt = System.currentTimeMillis(),
            status = "pending"
        )

        val list = getAllReports(context).toMutableList()
        list.add(report)
        saveReports(context, list)
        return null
    }

    fun getAllReports(context: Context): List<Report> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_REPORTS, "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = mutableListOf<Report>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(Report(
                id = o.getString("id"),
                commentId = o.getString("commentId"),
                commentText = o.getString("commentText"),
                reportedBy = o.getString("reportedBy"),
                reportedByName = o.getString("reportedByName"),
                reason = o.getString("reason"),
                reportedAt = o.getLong("reportedAt"),
                status = o.getString("status")
            ))
        }
        return list
    }

    fun getPendingReports(context: Context): List<Report> {
        return getAllReports(context).filter { it.status == "pending" }
    }

    private fun saveReports(context: Context, list: List<Report>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("commentId", r.commentId)
                put("commentText", r.commentText)
                put("reportedBy", r.reportedBy)
                put("reportedByName", r.reportedByName)
                put("reason", r.reason)
                put("reportedAt", r.reportedAt)
                put("status", r.status)
            })
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_REPORTS, arr.toString()).apply()
    }
}
