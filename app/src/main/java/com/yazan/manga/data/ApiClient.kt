package com.yazan.manga.data

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * ApiClient — HTTP client for the Vercel API (server-side protection layer).
 *
 * Every request to Firestore now goes through this API instead of using
 * the Firestore SDK directly. The API enforces:
 * - Firebase Auth token verification
 * - Rate limiting (60s cooldown between comments)
 * - Spam filter (banned words, caps, length)
 * - Max 2 comments per chapter per user
 * - Owner checks on edit/delete
 * - Admin checks on admin operations
 *
 * Even if an attacker decompiles the APK and removes the client-side
 * DDoSProtection/SpamFilter, the server-side checks still enforce all rules.
 *
 * NOTE: All methods are blocking (synchronous). Call them from
 * Dispatchers.IO only.
 */
object ApiClient {
    private const val TAG = "ApiClient"
    private const val BASE_URL = "https://yz-manga-api.vercel.app"
    private val JSON = "application/json".toMediaType()
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Build the app's User-Agent string.
     *
     * The server uses this to distinguish real app traffic from browser / curl
     * traffic. Format: "YZ-Manga/{versionCode} (Android {release})".
     */
    private fun getUserAgent(): String {
        return try {
            val version = getAppVersionCode()
            val android = android.os.Build.VERSION.RELEASE ?: "unknown"
            "YZ-Manga/$version (Android $android)"
        } catch (e: Exception) {
            "YZ-Manga/unknown"
        }
    }

    /**
     * Get the current user's email (from SharedPreferences).
     * Sent as X-User-Email header because the Firebase token might
     * be from an anonymous session (no email).
     */
    private fun getUserEmail(): String {
        return try {
            val context = com.yazan.manga.MangaApp.appContext
            val prefs = context.getSharedPreferences("manga_auth", android.content.Context.MODE_PRIVATE)
            val userJson = prefs.getString("current_user", "") ?: ""
            if (userJson.isNotEmpty()) {
                val json = com.google.gson.JsonParser.parseString(userJson).asJsonObject
                json.get("email")?.asString ?: ""
            } else ""
        } catch (e: Exception) { "" }
    }

    /**
     * Get the app's versionCode (for the X-App-Version header).
     * The server uses this to reject requests from old app versions.
     */
    private fun getAppVersionCode(): Int {
        return try {
            val context = com.yazan.manga.MangaApp.appContext
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }

    /**
     * Check if the device is compromised (Frida, debugger, emulator, root).
     * Sends the result as X-Device-Status header so the server can
     * shadow-ban compromised devices (block writes, allow reads).
     */
    private fun getDeviceStatus(): String {
        return try {
            val context = com.yazan.manga.MangaApp.appContext
            val compromised = com.yazan.manga.data.AntiDebug.isCompromised(context)
            if (compromised) "compromised" else "ok"
        } catch (e: Exception) {
            "ok"
        }
    }

    /**
     * Get the current user's Firebase ID token (blocking).
     * Returns null if not signed in or token fetch fails.
     */
    private fun getIdToken(): String? {
        val user = FirebaseAuth.getInstance().currentUser ?: return null
        return try {
            // Use Tasks.await() to get the token synchronously
            val result = com.google.android.gms.tasks.Tasks.await(
                user.getIdToken(false)
            )
            result?.token
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get ID token: ${e.message}")
            null
        }
    }

    private fun requestNoAuth(
        method: String,
        path: String,
        body: JsonObject? = null,
        query: Map<String, String> = emptyMap()
    ): Pair<Int, JsonObject?> {
        val urlBuilder = StringBuilder("$BASE_URL$path")
        if (query.isNotEmpty()) {
            urlBuilder.append("?")
            query.entries.joinTo(urlBuilder, "&") {
                "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
            }
        }
        val reqBuilder = Request.Builder()
            .url(urlBuilder.toString())
            .header("Content-Type", "application/json")
            .header("User-Agent", getUserAgent())
            .header("X-App-Version", getAppVersionCode().toString())
            .header("X-User-Email", getUserEmail())
            .header("X-Device-Status", getDeviceStatus())
        when (method) {
            "GET" -> reqBuilder.get()
            "POST", "PUT", "DELETE" -> {
                val bodyStr = body?.toString() ?: "{}"
                reqBuilder.method(method, bodyStr.toRequestBody(JSON))
            }
        }
        return try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                val responseBody = response.body?.string()
                val json = if (responseBody != null && responseBody.isNotEmpty()) {
                    try { JsonParser.parseString(responseBody).asJsonObject } catch (e: Exception) { null }
                } else null
                Pair(response.code, json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${e.message}")
            Pair(0, null)
        }
    }

    /**
     * Make an authenticated HTTP request to the API (blocking).
     */
    private fun request(
        method: String,
        path: String,
        body: JsonObject? = null,
        query: Map<String, String> = emptyMap()
    ): Pair<Int, JsonObject?> {
        val token = getIdToken() ?: return Pair(401, null)

        val urlBuilder = StringBuilder("$BASE_URL$path")
        if (query.isNotEmpty()) {
            urlBuilder.append("?")
            query.entries.joinTo(urlBuilder, "&") {
                "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
            }
        }

        val reqBuilder = Request.Builder()
            .url(urlBuilder.toString())
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("User-Agent", getUserAgent())
            .header("X-App-Version", getAppVersionCode().toString())
            .header("X-User-Email", getUserEmail())
            .header("X-Device-Status", getDeviceStatus())

        when (method) {
            "GET" -> reqBuilder.get()
            "POST", "PUT", "DELETE" -> {
                val bodyStr = body?.toString() ?: "{}"
                reqBuilder.method(method, bodyStr.toRequestBody(JSON))
            }
        }

        return try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                val responseBody = response.body?.string()
                val json = if (responseBody != null && responseBody.isNotEmpty()) {
                    try { JsonParser.parseString(responseBody).asJsonObject } catch (e: Exception) { null }
                } else null
                Pair(response.code, json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${e.message}")
            Pair(0, null)
        }
    }

    // =============================================================
    //  Comments
    // =============================================================

    fun getComments(contextId: String): List<CloudCommentsManager.Comment> {
        val (code, json) = request("GET", "/api/comments", query = mapOf("contextId" to contextId))
        if (code != 200 || json == null) return emptyList()
        val arr = json.getAsJsonArray("comments") ?: return emptyList()
        return arr.map { it.asJsonObject }.map { parseComment(it) }
    }

    fun addComment(
        contextId: String,
        contextType: String,
        text: String,
        parentId: String?
    ): CloudCommentsManager.Comment? {
        val body = JsonObject().apply {
            addProperty("contextId", contextId)
            addProperty("contextType", contextType)
            addProperty("text", text)
            if (parentId != null) addProperty("parentId", parentId)
        }
        val (code, json) = request("POST", "/api/comments", body)
        if (code != 201 || json == null) return null
        return parseComment(json)
    }

    fun editComment(commentId: String, text: String): Boolean {
        val body = JsonObject().apply { addProperty("text", text) }
        val (code, _) = request("PUT", "/api/comments", body, mapOf("id" to commentId))
        return code == 200
    }

    fun deleteComment(commentId: String): Boolean {
        val (code, _) = request("DELETE", "/api/comments", query = mapOf("id" to commentId))
        return code == 200
    }

    fun reactComment(commentId: String, type: String): Pair<List<String>, List<String>> {
        val body = JsonObject().apply { addProperty("type", type) }
        val (code, json) = request("POST", "/api/comments/react", body, mapOf("id" to commentId))
        if (code != 200 || json == null) return Pair(emptyList(), emptyList())
        val likes = json.getAsJsonArray("likes")?.map { it.asString } ?: emptyList()
        val dislikes = json.getAsJsonArray("dislikes")?.map { it.asString } ?: emptyList()
        return Pair(likes, dislikes)
    }

    /**
     * Returns the error message if the request failed, null on success.
     */
    fun addCommentWithError(
        contextId: String,
        contextType: String,
        text: String,
        parentId: String?
    ): Pair<CloudCommentsManager.Comment?, String?> {
        val body = JsonObject().apply {
            addProperty("contextId", contextId)
            addProperty("contextType", contextType)
            addProperty("text", text)
            if (parentId != null) addProperty("parentId", parentId)
        }
        val (code, json) = request("POST", "/api/comments", body)
        if (code == 201 && json != null) return Pair(parseComment(json), null)
        val error = json?.get("error")?.asString ?: "حدث خطأ"
        return Pair(null, error)
    }

    // =============================================================
    //  Reports
    // =============================================================

    fun addReport(
        commentId: String,
        commentText: String,
        reason: String,
        reportedEmail: String,
        reportedName: String
    ): Boolean {
        val body = JsonObject().apply {
            addProperty("commentId", commentId)
            addProperty("commentText", commentText)
            addProperty("reason", reason)
            addProperty("reportedEmail", reportedEmail)
            addProperty("reportedName", reportedName)
        }
        val (code, _) = request("POST", "/api/reports", body)
        return code == 201
    }

    fun getReports(): List<CloudCommentsManager.Report> {
        val (code, json) = request("GET", "/api/admin/reports")
        if (code != 200 || json == null) return emptyList()
        val arr = json.getAsJsonArray("reports") ?: return emptyList()
        return arr.map { it.asJsonObject }.map { parseReport(it) }
    }

    fun resolveReport(reportId: String): Boolean {
        val (code, _) = request("PUT", "/api/admin/reports", query = mapOf("id" to reportId))
        return code == 200
    }

    // =============================================================
    //  User profile
    // =============================================================

    fun getUserProfile(email: String): AuthManager.CloudUser? {
        val (code, json) = request("GET", "/api/profile", query = mapOf("email" to email))
        if (code != 200 || json == null) return null
        val userObj = json.getAsJsonObject("user") ?: return null
        return AuthManager.CloudUser(
            email = email,
            name = userObj.get("name")?.asString ?: "",
            username = userObj.get("username")?.asString ?: "",
            avatarBase64 = userObj.get("avatarBase64")?.asString ?: "",
            isAdmin = userObj.get("isAdmin")?.asBoolean ?: false,
            birthDate = userObj.get("birthDate")?.asString ?: "",
            country = userObj.get("country")?.asString ?: "",
            createdAt = userObj.get("createdAt")?.asLong ?: 0L
        )
    }

    fun updateProfile(
        name: String? = null,
        username: String? = null,
        avatarBase64: String? = null,
        birthDate: String? = null,
        country: String? = null
    ): Pair<Boolean, String?> {
        val body = JsonObject().apply {
            if (name != null) addProperty("name", name)
            if (username != null) addProperty("username", username)
            if (avatarBase64 != null) addProperty("avatarBase64", avatarBase64)
            if (birthDate != null) addProperty("birthDate", birthDate)
            if (country != null) addProperty("country", country)
        }
        val (code, json) = request("PUT", "/api/profile", body)
        if (code == 200 && json != null) {
            val msg = json.get("message")?.asString ?: "تم التحديث بنجاح"
            return Pair(true, msg)
        }
        val errorMsg = json?.get("error")?.asString ?: "فشل الاتصال بالسيرفر"
        return Pair(false, errorMsg)
    }

    // =============================================================
    //  Lists
    // =============================================================

    fun getLists(): Map<String, List<String>> {
        val (code, json) = request("GET", "/api/lists")
        if (code != 200 || json == null) return emptyMap()
        val lists = json.getAsJsonObject("lists") ?: return emptyMap()
        return lists.entrySet().associate { 
            it.key to (it.value.asJsonArray?.map { v -> v.asString } ?: emptyList()) 
        }
    }

    fun updateList(type: String, items: List<String>): Boolean {
        val body = JsonObject().apply { add(type, gson.toJsonTree(items)) }
        val (code, _) = request("PUT", "/api/lists", body)
        return code == 200
    }

    // =============================================================
    //  History
    // =============================================================

    fun getHistory(): List<ReadingHistoryManager.HistoryEntry> {
        val (code, json) = request("GET", "/api/history")
        if (code != 200 || json == null) return emptyList()
        val arr = json.getAsJsonArray("history") ?: return emptyList()
        return arr.map { it.asJsonObject }.map { parseHistoryEntry(it) }
    }

    fun addHistory(entry: ReadingHistoryManager.HistoryEntry): Boolean {
        val body = JsonObject().apply {
            addProperty("mangaId", entry.mangaId)
            addProperty("mangaTitle", entry.mangaTitle)
            addProperty("mangaCover", entry.mangaCover)
            addProperty("chapterId", entry.chapterId)
            addProperty("chapterNumber", entry.chapterNumber)
            addProperty("chapterTitle", entry.chapterTitle)
        }
        val (code, _) = request("POST", "/api/history", body)
        return code == 200
    }

    fun clearHistory(): Boolean {
        val (code, _) = request("DELETE", "/api/history")
        return code == 200
    }

    // =============================================================
    //  Admin
    // =============================================================

    fun banUser(email: String, reason: String, duration: Long): Boolean {
        val body = JsonObject().apply {
            addProperty("email", email)
            addProperty("reason", reason)
            addProperty("duration", duration)
        }
        val (code, _) = request("POST", "/api/admin/ban", body)
        return code == 200
    }

    fun unbanUser(email: String): Boolean {
        val (code, _) = request("DELETE", "/api/admin/ban", query = mapOf("email" to email))
        return code == 200
    }

    // =============================================================
    //  Parsers
    // =============================================================

    private fun parseComment(obj: JsonObject): CloudCommentsManager.Comment {
        return CloudCommentsManager.Comment(
            id = obj.get("id")?.asString ?: "",
            contextId = obj.get("contextId")?.asString ?: "",
            contextType = obj.get("contextType")?.asString ?: "manga",
            text = obj.get("text")?.asString ?: "",
            authorEmail = obj.get("authorEmail")?.asString ?: "",
            authorName = obj.get("authorName")?.asString ?: "",
            authorAvatar = obj.get("authorAvatar")?.asString ?: "",
            isAdmin = obj.get("isAdmin")?.asBoolean ?: false,
            parentId = obj.get("parentId")?.takeIf { !it.isJsonNull }?.asString,
            createdAt = obj.get("createdAt")?.asLong ?: 0L,
            likes = obj.getAsJsonArray("likes")?.map { it.asString }?.toMutableList() ?: mutableListOf(),
            dislikes = obj.getAsJsonArray("dislikes")?.map { it.asString }?.toMutableList() ?: mutableListOf(),
            editedAt = obj.get("editedAt")?.takeIf { !it.isJsonNull }?.asLong
        )
    }

    private fun parseReport(obj: JsonObject): CloudCommentsManager.Report {
        return CloudCommentsManager.Report(
            id = obj.get("id")?.asString ?: "",
            commentId = obj.get("commentId")?.asString ?: "",
            commentText = obj.get("commentText")?.asString ?: "",
            commentContextId = obj.get("commentContextId")?.asString ?: "",
            commentContextTitle = obj.get("commentContextTitle")?.asString ?: "",
            reportedEmail = obj.get("reportedEmail")?.asString ?: "",
            reportedName = obj.get("reportedName")?.asString ?: "",
            reportedByEmail = obj.get("reportedByEmail")?.asString ?: "",
            reportedByName = obj.get("reportedByName")?.asString ?: "",
            reason = obj.get("reason")?.asString ?: "",
            createdAt = obj.get("createdAt")?.asLong ?: 0L,
            resolved = obj.get("resolved")?.asBoolean ?: false,
            resolvedBy = obj.get("resolvedBy")?.takeIf { !it.isJsonNull }?.asString,
            resolvedAt = obj.get("resolvedAt")?.takeIf { !it.isJsonNull }?.asLong
        )
    }

    private fun parseHistoryEntry(obj: JsonObject): ReadingHistoryManager.HistoryEntry {
        return ReadingHistoryManager.HistoryEntry(
            mangaId = obj.get("mangaId")?.asString ?: "",
            mangaTitle = obj.get("mangaTitle")?.asString ?: "",
            mangaCover = obj.get("mangaCover")?.asString ?: "",
            chapterId = obj.get("chapterId")?.asString ?: "",
            chapterNumber = obj.get("chapterNumber")?.asString ?: "",
            chapterTitle = obj.get("chapterTitle")?.asString ?: ""
        )
    }
}
