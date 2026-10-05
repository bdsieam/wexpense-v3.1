package com.example.supabase

import android.content.Context
import android.content.SharedPreferences
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
    const val SUPABASE_JWKS_URL = "https://ydntfvpsrsxegupxyvtz.supabase.co/auth/v1/.well-known/jwks.json"
    const val BUCKET_NAME = "wexpense-backups"

    private val client = OkHttpClient()
    private var prefs: SharedPreferences? = null

    @Volatile private var currentAccessToken: String? = null
    @Volatile private var currentRefreshToken: String? = null
    @Volatile private var storedUsername: String? = null
    @Volatile private var storedPassword: String? = null
    @Volatile var isConnected: Boolean = true
        private set

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences("supabase_sync_prefs", Context.MODE_PRIVATE)
            currentAccessToken = prefs?.getString("access_token", null)
            currentRefreshToken = prefs?.getString("refresh_token", null)
            storedUsername = prefs?.getString("username", null)
            storedPassword = prefs?.getString("password", null)
            Log.d(TAG, "SupabaseSyncManager initialized. Target: $SUPABASE_URL")
            testConnection { success, msg ->
                isConnected = success
                Log.d(TAG, "Supabase Connection Status: $success ($msg)")
            }
        }
    }

    /**
     * Tests connectivity to Supabase using the JWKS endpoint and publishable key.
     */
    fun testConnection(onResult: (Boolean, String) -> Unit) {
        val request = Request.Builder()
            .url(SUPABASE_JWKS_URL)
            .addHeader("apikey", SUPABASE_PUBLISHABLE_KEY)
            .get()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Supabase connection test failed: ${e.localizedMessage}")
                isConnected = false
                onResult(false, e.localizedMessage ?: "Network error")
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (resp.isSuccessful) {
                        Log.i(TAG, "✅ Supabase database connection verified: $SUPABASE_URL")
                        isConnected = true
                        onResult(true, "Connected")
                    } else {
                        Log.w(TAG, "Supabase response code: ${resp.code}")
                        onResult(false, "Code ${resp.code}")
                    }
                }
            }
        })
    }

    private fun saveSession(token: String?, refresh: String?, username: String? = null, password: String? = null) {
        currentAccessToken = token
        currentRefreshToken = refresh
        if (username != null) storedUsername = username
        if (password != null) storedPassword = password

        prefs?.edit()?.apply {
            putString("access_token", token)
            putString("refresh_token", refresh)
            if (username != null) putString("username", username)
            if (password != null) putString("password", password)
            apply()
        }
    }

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
                        try {
                            val json = JSONObject(bodyString)
                            val token = json.optString("access_token", null)
                            val refresh = json.optString("refresh_token", null)
                            saveSession(token, refresh, username, password)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not parse session on signup: ${e.message}")
                        }
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
                        try {
                            val json = JSONObject(bodyString)
                            val token = json.optString("access_token", null)
                            val refresh = json.optString("refresh_token", null)
                            saveSession(token, refresh, username, password)
                            Log.i(TAG, "Supabase session stored with token length: ${token?.length ?: 0}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not parse session on login: ${e.message}")
                        }
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
     * Re-authenticates silently if we have stored credentials to refresh the token.
     */
    private fun reAuthenticateSilently(onDone: (Boolean) -> Unit) {
        val u = storedUsername
        val p = storedPassword
        if (!u.isNullOrBlank() && !p.isNullOrBlank()) {
            loginWithSupabase(u, p) { success, _ ->
                onDone(success)
            }
        } else {
            onDone(false)
        }
    }

    fun getStoredAccountEmail(): String {
        val u = storedUsername ?: prefs?.getString("username", null)
        if (!u.isNullOrBlank()) {
            return toSafeEmail(u)
        }
        val e = prefs?.getString("sync_email", null)
        if (!e.isNullOrBlank()) {
            return toSafeEmail(e)
        }
        return ""
    }

    fun clearAccount() {
        storedUsername = null
        storedPassword = null
        currentAccessToken = null
        currentRefreshToken = null
        prefs?.edit()?.apply {
            remove("username")
            remove("password")
            remove("sync_email")
            remove("access_token")
            remove("refresh_token")
            apply()
        }
        Log.i(TAG, "Cleared active sync account session")
    }

    fun saveAccount(usernameOrEmail: String) {
        val safeEmail = toSafeEmail(usernameOrEmail)
        storedUsername = usernameOrEmail
        prefs?.edit()?.apply {
            putString("username", usernameOrEmail)
            putString("sync_email", safeEmail)
            apply()
        }
        Log.i(TAG, "Saved active sync account: $usernameOrEmail -> $safeEmail")
    }

    /**
     * Uploads the backup JSON data to the Supabase Storage Bucket.
     * Uses upsert to overwrite the user's existing backup file and immediately update
     * the 'Last modified' timestamp in the Supabase Dashboard.
     */
    fun uploadBackupToSupabase(
        email: String,
        jsonData: String,
        onComplete: (Boolean) -> Unit
    ) {
        val targetEmail = if (email.isBlank()) getStoredAccountEmail() else email
        val normalizedEmail = if (!targetEmail.contains("@")) toSafeEmail(targetEmail) else targetEmail
        val safeEmail = normalizedEmail.replace("@", "_at_").replace(".", "_dot_")
        val filename = "${safeEmail}_backup.json"
        val url = "$SUPABASE_URL/storage/v1/object/$BUCKET_NAME/$filename"

        Log.i(TAG, "Uploading backup to Supabase Storage: $url (filename: $filename)")
        val requestBody = jsonData.toByteArray(Charsets.UTF_8).toRequestBody("application/json".toMediaTypeOrNull())

        // Use the Supabase publishable key as Bearer token for seamless upsert on the storage bucket
        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", SUPABASE_PUBLISHABLE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_PUBLISHABLE_KEY")
            .addHeader("x-upsert", "true")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Supabase upload network failure: ${e.localizedMessage}")
                onComplete(false)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    val code = resp.code
                    val body = resp.body?.string() ?: ""

                    if (resp.isSuccessful) {
                        Log.i(TAG, "✅ Supabase backup uploaded & modified timestamp updated for: $filename")
                        onComplete(true)
                    } else {
                        Log.e(TAG, "❌ Supabase upload failed with code: $code - $body")
                        onComplete(false)
                    }
                }
            }
        })
    }

    /**
     * Downloads the backup JSON data from the Supabase Storage Bucket.
     * Dedicated to the specific user's backup file (e.g. {safeEmail}_backup.json).
     */
    fun downloadBackupFromSupabase(
        email: String,
        onResult: (String?) -> Unit
    ) {
        val targetEmail = if (email.isBlank()) getStoredAccountEmail() else email
        val normalizedEmail = if (!targetEmail.contains("@")) toSafeEmail(targetEmail) else targetEmail
        val safeEmail = normalizedEmail.replace("@", "_at_").replace(".", "_dot_")
        val filename = "${safeEmail}_backup.json"
        
        val authUrl = "$SUPABASE_URL/storage/v1/object/authenticated/$BUCKET_NAME/$filename"
        Log.i(TAG, "Attempting Supabase backup download from: $authUrl")

        val request = Request.Builder()
            .url(authUrl)
            .addHeader("apikey", SUPABASE_PUBLISHABLE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_PUBLISHABLE_KEY")
            .get()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Supabase download failed for $filename: ${e.localizedMessage}")
                tryPublicFallback(filename, onResult)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (resp.isSuccessful) {
                        val bodyString = resp.body?.string()
                        if (!bodyString.isNullOrBlank() && bodyString.contains("groups")) {
                            Log.i(TAG, "Supabase backup downloaded successfully from $filename (${bodyString.length} bytes)")
                            onResult(bodyString)
                            return
                        }
                    }
                    // Try public endpoint fallback
                    tryPublicFallback(filename, onResult)
                }
            }
        })
    }

    private fun tryPublicFallback(filename: String, onResult: (String?) -> Unit) {
        val publicUrl = "$SUPABASE_URL/storage/v1/object/public/$BUCKET_NAME/$filename"
        val fallbackReq = Request.Builder()
            .url(publicUrl)
            .addHeader("apikey", SUPABASE_PUBLISHABLE_KEY)
            .addHeader("Authorization", "Bearer $SUPABASE_PUBLISHABLE_KEY")
            .get()
            .build()

        client.newCall(fallbackReq).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "Public fallback failed for $filename: ${e.localizedMessage}")
                onResult(null)
            }
            override fun onResponse(call: okhttp3.Call, fallbackResp: okhttp3.Response) {
                fallbackResp.use { fResp ->
                    val fBody = fResp.body?.string()
                    if (fResp.isSuccessful && !fBody.isNullOrBlank() && fBody.contains("groups")) {
                        Log.i(TAG, "Supabase backup downloaded from public endpoint $filename")
                        onResult(fBody)
                    } else {
                        onResult(null)
                    }
                }
            }
        })
    }
}

