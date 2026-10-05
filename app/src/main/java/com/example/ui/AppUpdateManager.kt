package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class AppReleaseInfo(
    val tagName: String,
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val isNewer: Boolean
)

object AppUpdateManager {
    const val GITHUB_OWNER = "bdsieam"
    const val GITHUB_REPO = "wexpense-v3.1"

    suspend fun checkLatestRelease(currentVersionName: String): Result<AppReleaseInfo?> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "WeXpense-Android-App")
                connectTimeout = 8000
                readTimeout = 8000
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = reader.use { it.readText() }
                val json = JSONObject(response)

                val tagName = json.optString("tag_name", "")
                val releaseTitle = json.optString("name", tagName)
                val releaseNotes = json.optString("body", "Bug fixes and improvements.")

                val remoteVersionClean = tagName.removePrefix("v").trim()
                val currentVersionClean = currentVersionName.removePrefix("v").trim()

                // Find APK download URL from assets
                var apkDownloadUrl = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            apkDownloadUrl = asset.optString("browser_download_url", "")
                            break
                        }
                    }
                }
                if (apkDownloadUrl.isEmpty()) {
                    apkDownloadUrl = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO/releases/download/$tagName/wexpense.apk"
                }

                val isNewer = isVersionNewer(remoteVersionClean, currentVersionClean)

                Result.success(
                    AppReleaseInfo(
                        tagName = tagName,
                        versionName = remoteVersionClean,
                        releaseTitle = releaseTitle,
                        releaseNotes = releaseNotes,
                        downloadUrl = apkDownloadUrl,
                        isNewer = isNewer
                    )
                )
            } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                Result.failure(Exception("No release found or repository is Private. Make repository Public for open updates."))
            } else {
                Result.failure(Exception("GitHub API HTTP $responseCode"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun isVersionNewer(remote: String, current: String): Boolean {
        return try {
            val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
            false
        } catch (e: Exception) {
            remote != current
        }
    }

    fun startApkDownload(context: Context, downloadUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open download link: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
