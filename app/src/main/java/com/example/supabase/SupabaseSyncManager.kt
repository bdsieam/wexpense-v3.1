package com.example.supabase

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

object SupabaseSyncManager {

    private const val TAG = "SupabaseSyncManager"

    const val SUPABASE_URL = "https://ydntfvpsrsxegupxyvtz.supabase.co"
    const val SUPABASE_PUBLISHABLE_KEY = "sb_publishable_zoHyPI-t9Dpu2L17qUoewA_aIL82GBX"
    const val SUPABASE_SECRET_KEY = "sb_secret_qfB44dMdt40fGPIqD_YsJA_vIBI2xMU"
    const val BUCKET_NAME = "wexpense-backups"

    private val client = OkHttpClient()

    /**
     * Helper to safely format any username or email into a compliant format that Supabase accepts.
     * If the user already enters a valid email address, we use it as-is.
     * Otherwise, we sanitize the name (letters and digits only) and form a valid email address.
     */
    fun toSafeEmail(input: String): String {
        val trimmed = input.trim().lowercase()
        if (trimmed.contains("@") && trimmed.contains(".")) {
            return trimmed
        }
        val clean = trimmed.filter { it.isLetterOrDigit() }
        val prefix = if (clean.isEmpty()) "user${System.currentTimeMillis()}" else clean
        return "$prefix.wexpense@gmail.com"
    }

    /**
     * Registers a new user with a Username and Password on Supabase.
     * Maps the username to a standard valid pseudo-email (e.g. username.wexpense@gmail.com)
     */
    fun registerWithSupabase(
        username: String,
        password: String,
        onResult: (Boolean, String) -> Unit
    ) {
        val email = toSafeEmail(username)
        val url = "$SUPABASE_URL/auth/v1/signup"

        val jsonBody = """
            {
              "email": "$email",
              "password": "$password",
              "data": {
                "display_name": "${username.trim()}"
              }
            }
        """.trimIndent()

        val requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", SUPABASE_PUBLISHABLE_KEY)
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Supabase signup request failed: ${e.localizedMessage}")
                onResult(false, e.localizedMessage ?: "Network error occurred")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    val bodyString = resp.body?.string() ?: ""
                    if (resp.isSuccessful) {
                        Log.i(TAG, "Supabase signup successful for username: $username")
                        onResult(true, "Account created successfully!")
                    } else {
                        Log.e(TAG, "Supabase signup failed with code: ${resp.code} - $bodyString")
                        val errorMessage = try {
                            JSONObject(bodyString).optString("msg", "Signup failed")
                        } catch (e: Exception) {
                            "Signup failed: code ${resp.code}"
                        }
                        onResult(false, errorMessage)
                    }
                }
            }
        })
    }

    /**
     * Logs in an existing user with Username and Password on Supabase.
     */
    fun loginWithSupabase(
        username: String,
        password: String,
        onResult: (Boolean, String) -> Unit
    ) {
        val email = toSafeEmail(username)
        val url = "$SUPABASE_URL/auth/v1/token?grant_type=password"

        val jsonBody = """
            {
              "email": "$email",
              "password": "$password"
            }
        """.trimIndent()

        val requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", SUPABASE_PUBLISHABLE_KEY)
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Supabase login request failed: ${e.localizedMessage}")
                onResult(false, e.localizedMessage ?: "Network error occurred")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    val bodyString = resp.body?.string() ?: ""
                    if (resp.isSuccessful) {
                        Log.i(TAG, "Supabase login successful for username: $username")
                        onResult(true, "Login successful!")
                    } else {
                        Log.e(TAG, "Supabase login failed with code: ${resp.code} - $bodyString")
                        val errorMessage = try {
                            val json = JSONObject(bodyString)
                            json.optString("error_description", json.optString("error", "Invalid username or password"))
                        } catch (e: Exception) {
                            "Invalid credentials or connection issue"
                        }
                        onResult(false, errorMessage)
                    }
                }
            }
        })
    }

    /**
     * Ensures that the backup storage bucket exists on Supabase.
     * If not, attempts to create it programmatically using the service key.
     */
    private fun ensureBucketExists() {
        val url = "$SUPABASE_URL/storage/v1/bucket"
        
        // JSON body to create a public bucket
        val jsonBody = """
            {
              "id": "$BUCKET_NAME",
              "name": "$BUCKET_NAME",
              "public": true,
              "file_size_limit": 52428800,
              "allowed_mime_types": ["application/json"]
            }
        """.trimIndent()

        val requestBody = jsonBody.toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", SUPABASE_SECRET_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_SECRET_KEY")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Failed to ensure bucket exists: ${e.localizedMessage}")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    val code = resp.code
                    if (code == 201 || code == 200) {
                        Log.i(TAG, "Storage bucket '$BUCKET_NAME' verified/created successfully.")
                    } else if (code == 409) {
                        Log.i(TAG, "Storage bucket '$BUCKET_NAME' already exists.")
                    } else {
                        Log.w(TAG, "Unexpected response from bucket creation: $code - ${resp.body?.string()}")
                    }
                }
            }
        })
    }

    /**
     * Uploads the backup JSON data to the Supabase Storage Bucket.
     * Uses upsert to overwrite any existing backup file for this user.
     */
    fun uploadBackupToSupabase(
        email: String,
        jsonData: String,
        onComplete: (Boolean) -> Unit
    ) {
        // Ensure bucket is ready first asynchronously
        ensureBucketExists()

        val normalizedEmail = if (!email.contains("@")) toSafeEmail(email) else email
        val safeEmail = normalizedEmail.replace("@", "_at_").replace(".", "_dot_")
        val filename = "${safeEmail}_backup.json"
        val url = "$SUPABASE_URL/storage/v1/object/$BUCKET_NAME/$filename"

        val requestBody = jsonData.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaTypeOrNull())

        // Supabase Storage uses POST with x-upsert header to overwrite/upload
        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", SUPABASE_SECRET_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_SECRET_KEY")
            .addHeader("x-upsert", "true")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Supabase upload failed: ${e.localizedMessage}")
                onComplete(false)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (resp.isSuccessful) {
                        Log.i(TAG, "Supabase backup uploaded successfully for: $normalizedEmail")
                        onComplete(true)
                    } else {
                        Log.e(TAG, "Supabase upload failed with code: ${resp.code} - ${resp.body?.string()}")
                        onComplete(false)
                    }
                }
            }
        })
    }

    /**
     * Downloads the backup JSON data from the Supabase Storage Bucket.
     */
    fun downloadBackupFromSupabase(
        email: String,
        onResult: (String?) -> Unit
    ) {
        val normalizedEmail = if (!email.contains("@")) toSafeEmail(email) else email
        val safeEmail = normalizedEmail.replace("@", "_at_").replace(".", "_dot_")
        val filename = "${safeEmail}_backup.json"
        val publicUrl = "$SUPABASE_URL/storage/v1/object/public/$BUCKET_NAME/$filename"

        Log.i(TAG, "Attempting Supabase backup download from: $publicUrl")

        val request = Request.Builder()
            .url(publicUrl)
            .addHeader("apikey", SUPABASE_PUBLISHABLE_KEY)
            .get()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Supabase download failed: ${e.localizedMessage}")
                onResult(null)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (resp.isSuccessful) {
                        val bodyString = resp.body?.string()
                        Log.i(TAG, "Supabase backup downloaded successfully (${bodyString?.length ?: 0} bytes)")
                        onResult(bodyString)
                    } else if (resp.code == 404 && email != normalizedEmail) {
                        // Fallback: try raw email
                        val rawSafeEmail = email.replace("@", "_at_").replace(".", "_dot_")
                        val fallbackFilename = "${rawSafeEmail}_backup.json"
                        val fallbackUrl = "$SUPABASE_URL/storage/v1/object/public/$BUCKET_NAME/$fallbackFilename"
                        val fallbackRequest = Request.Builder().url(fallbackUrl).get().build()
                        client.newCall(fallbackRequest).enqueue(object : okhttp3.Callback {
                            override fun onFailure(call: okhttp3.Call, e: IOException) {
                                onResult(null)
                            }
                            override fun onResponse(call: okhttp3.Call, fallbackResp: okhttp3.Response) {
                                fallbackResp.use { fResp ->
                                    if (fResp.isSuccessful) {
                                        onResult(fResp.body?.string())
                                    } else {
                                        onResult(null)
                                    }
                                }
                            }
                        })
                    } else {
                        Log.w(TAG, "Supabase download failed with code: ${resp.code} (File might not exist yet)")
                        onResult(null)
                    }
                }
            }
        })
    }
}
