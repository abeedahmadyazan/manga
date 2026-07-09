package com.yazan.manga.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * InAppUpdateManager — checks GitHub Releases for new versions and prompts
 * the user to update.
 *
 * The check runs on app start. If a newer version is found, a dialog appears
 * with two options:
 *  - "تحديث الآن" → opens the download URL in the browser
 *  - "لاحقاً" → dismisses (will check again next launch)
 *
 * Version comparison uses versionCode (monotonically increasing integer).
 * The current versionCode is read from the app's PackageInfo.
 */
object InAppUpdateManager {
    private const val TAG = "InAppUpdate"
    private const val GITHUB_API = "https://api.github.com/repos/abeedahmadyazan/mangaapp/releases/latest"
    private const val DOWNLOAD_URL = "https://github.com/abeedahmadyazan/mangaapp/releases/latest/download/app-release.apk"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Check for updates. If a newer version exists, show a dialog.
     * Call this from Activity.onCreate (after setContentView).
     */
    fun checkForUpdate(activity: Activity) {
        Thread {
            try {
                val currentVersionCode = getCurrentVersionCode(activity)
                val (latestVersionCode, releaseName) = fetchLatestRelease() ?: return@Thread

                Log.d(TAG, "Current: $currentVersionCode, Latest: $latestVersionCode")

                if (latestVersionCode > currentVersionCode) {
                    activity.runOnUiThread {
                        showUpdateDialog(activity, releaseName)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.message}")
            }
        }.start()
    }

    private fun getCurrentVersionCode(context: Context): Int {
        return try {
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

    private fun fetchLatestRelease(): Pair<Int, String>? {
        return try {
            val req = Request.Builder()
                .url(GITHUB_API)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "YZ-Manga-App")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val root = JsonParser.parseString(body).asJsonObject
                val tagName = root.get("tag_name")?.asString ?: return null
                val name = root.get("name")?.asString ?: tagName

                // Extract version code from tag_name (format: "v1.0.213" → 213)
                // Or from name (format: "Manga App Build 213")
                val versionCode = extractVersionCode(tagName, name) ?: return null
                Pair(versionCode, name)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchLatestRelease failed: ${e.message}")
            null
        }
    }

    private fun extractVersionCode(tagName: String, name: String): Int? {
        // Try tag_name first: "v1.0.213" → 213
        val tagMatch = Regex("""v?(\d+)\.(\d+)\.(\d+)""").find(tagName)
        if (tagMatch != null) {
            return tagMatch.groupValues[3].toIntOrNull()
        }
        // Try name: "Manga App Build 213" → 213
        val nameMatch = Regex("""Build\s+(\d+)""", RegexOption.IGNORE_CASE).find(name)
        if (nameMatch != null) {
            return nameMatch.groupValues[1].toIntOrNull()
        }
        // Fallback: any number in tag_name
        val numMatch = Regex("""(\d+)""").find(tagName)
        return numMatch?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun showUpdateDialog(activity: Activity, releaseName: String) {
        AlertDialog.Builder(activity)
            .setTitle("تحديث جديد متاح! 🚀")
            .setMessage("يتوفر إصدار جديد من التطبيق ($releaseName).\n\nيرجى التحديث للحصول على أحدث المميزات والإصلاحات الأمنية.")
            .setPositiveButton("تحديث الآن") { _, _ ->
                openDownloadUrl(activity)
            }
            .setNegativeButton("لاحقاً") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun openDownloadUrl(activity: Activity) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open download URL: ${e.message}")
        }
    }
}
