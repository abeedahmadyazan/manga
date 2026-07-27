package com.yazan.manga.data

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * AnnouncementManager — checks for admin announcements + force updates.
 *
 * On app start, calls the Vercel API to get the latest announcement.
 * If forceUpdate=true and the app's versionCode < minVersionCode:
 *   → Shows a non-dismissible dialog that opens the download URL.
 * If forceUpdate=false:
 *   → Shows a dismissible dialog with the announcement message.
 */
object AnnouncementManager {
    private const val TAG = "Announcement"
    private const val API_URL = "https://yz-manga-api.vercel.app/api/announcements"
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun checkAnnouncement(activity: Activity) {
        Thread {
            try {
                val token = getAuthToken()
                if (token == null) return@Thread
                
                val req = Request.Builder()
                    .url(API_URL)
                    .header("Authorization", "Bearer $token")
                    .header("X-App-Version", getAppVersionCode(activity).toString())
                    .header("X-User-Email", getUserEmail())
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body?.string() ?: return@use
                    val root = JsonParser.parseString(body).asJsonObject
                    val ann = root.getAsJsonObject("announcement") ?: return@use
                    
                    val title = ann.get("title")?.asString ?: return@use
                    val message = ann.get("message")?.asString ?: ""
                    val forceUpdate = ann.get("forceUpdate")?.asBoolean ?: false
                    val minVersionCode = ann.get("minVersionCode")?.asLong ?: 0L
                    val updateUrl = ann.get("updateUrl")?.asString 
                        ?: "https://github.com/abeedahmadyazan/mangaapp/releases/latest"
                    val annId = ann.get("id")?.asString ?: ""
                    
                    // Check if this announcement was already dismissed
                    val prefs = activity.getSharedPreferences("announcements", android.content.Context.MODE_PRIVATE)
                    val dismissedId = prefs.getString("dismissed_id", "")
                    
                    if (annId == dismissedId && !forceUpdate) return@use
                    
                    // Check if force update applies
                    val currentVersion = getAppVersionCode(activity)
                    val needsForceUpdate = forceUpdate && currentVersion < minVersionCode
                    
                    activity.runOnUiThread {
                        showAnnouncement(activity, title, message, needsForceUpdate, updateUrl, annId)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Announcement check failed: ${e.message}")
            }
        }.start()
    }

    private fun showAnnouncement(
        activity: Activity,
        title: String,
        message: String,
        forceUpdate: Boolean,
        updateUrl: String,
        annId: String
    ) {
        val builder = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("تحديث الآن") { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    activity.startActivity(intent)
                } catch (e: Exception) {}
            }

        if (!forceUpdate) {
            builder.setNegativeButton("لاحقاً") { dialog, _ ->
                // Save dismissed announcement ID
                activity.getSharedPreferences("announcements", android.content.Context.MODE_PRIVATE)
                    .edit().putString("dismissed_id", annId).apply()
                dialog.dismiss()
            }
        } else {
            // Force update: non-dismissible
            builder.setCancelable(false)
        }

        builder.show()
    }

    private fun getAuthToken(): String? {
        val user = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser ?: return null
        return try {
            com.google.android.gms.tasks.Tasks.await(user.getIdToken(false))?.token
        } catch (e: Exception) { null }
    }

    private fun getAppVersionCode(context: android.content.Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) { 1 }
    }

    private fun getUserEmail(): String {
        return try {
            val context = com.yazan.manga.MangaApp.appContext
            val prefs = context.getSharedPreferences("manga_auth", android.content.Context.MODE_PRIVATE)
            val userJson = prefs.getString("current_user", "") ?: ""
            if (userJson.isNotEmpty()) {
                val json = JsonParser.parseString(userJson).asJsonObject
                json.get("email")?.asString ?: ""
            } else ""
        } catch (e: Exception) { "" }
    }
}
