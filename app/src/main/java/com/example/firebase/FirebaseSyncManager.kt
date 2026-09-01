package com.example.firebase

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object FirebaseSyncManager {

    private const val WEB_CLIENT_ID = "999830758914-f58g2lka5a9d8c6b2j3p6v0n9m8q7r6s.apps.googleusercontent.com"

    /**
     * Checks if Firebase is initialized and ready to be used.
     * If not, attempts to initialize it.
     */
    fun isFirebaseReady(context: Context): Boolean {
        return try {
            FirebaseApp.getInstance()
            true
        } catch (e: Exception) {
            try {
                FirebaseApp.initializeApp(context)
                true
            } catch (ex: Exception) {
                false
            }
        }
    }

    /**
     * Triggers real Google Sign-In via Android Credential Manager
     */
    fun signInWithGoogle(
        activity: Activity,
        onSuccess: (email: String, displayName: String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (!isFirebaseReady(activity)) {
            onFailure("Firebase is not initialized. Please ensure your google-services.json is correctly placed in the app directory.")
            return
        }

        val credentialManager = CredentialManager.create(activity)

        // Set up the Google ID Option for the Credential Manager request
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(WEB_CLIENT_ID)
            .setAutoSelectEnabled(false)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = credentialManager.getCredential(
                    context = activity,
                    request = request
                )

                val credential = result.credential
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val idToken = googleIdTokenCredential.idToken
                    
                    // Sign in to Firebase with the Google ID Token
                    val firebaseAuth = FirebaseAuth.getInstance()
                    val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
                    
                    firebaseAuth.signInWithCredential(firebaseCredential)
                        .addOnSuccessListener { authResult ->
                            val user = authResult.user
                            val email = user?.email ?: googleIdTokenCredential.id
                            val displayName = user?.displayName ?: googleIdTokenCredential.displayName ?: "Google User"
                            onSuccess(email, displayName)
                        }
                        .addOnFailureListener { exception ->
                            onFailure("Firebase Authentication Failed: ${exception.localizedMessage}")
                        }
                } else {
                    onFailure("Unsupported credential type: ${credential.type}")
                }
            } catch (e: Exception) {
                onFailure("Google Sign-In failed: ${e.localizedMessage}\n\nHint: Verify that your SHA-1 signature and Package Name are added to your Firebase project, and google-services.json is uploaded.")
            }
        }
    }

    /**
     * Uploads the entire Room DB as JSON to Firestore
     */
    fun uploadDataToFirestore(
        context: Context,
        email: String,
        jsonData: String,
        onComplete: (Boolean) -> Unit = {}
    ) {
        if (!isFirebaseReady(context)) {
            onComplete(false)
            return
        }

        try {
            val firestore = FirebaseFirestore.getInstance()
            val backupDoc = firestore.collection("users").document(email).collection("backup").document("data")

            val dataMap = hashMapOf(
                "email" to email,
                "data" to jsonData,
                "lastSync" to System.currentTimeMillis()
            )

            backupDoc.set(dataMap, SetOptions.merge())
                .addOnSuccessListener {
                    onComplete(true)
                }
                .addOnFailureListener {
                    onComplete(false)
                }
        } catch (e: Exception) {
            onComplete(false)
        }
    }

    /**
     * Downloads and Restores the Room DB from Firestore
     */
    fun downloadDataFromFirestore(
        context: Context,
        email: String,
        onResult: (String?) -> Unit
    ) {
        if (!isFirebaseReady(context)) {
            onResult(null)
            return
        }

        try {
            val firestore = FirebaseFirestore.getInstance()
            val backupDoc = firestore.collection("users").document(email).collection("backup").document("data")

            backupDoc.get()
                .addOnSuccessListener { document ->
                    if (document != null && document.exists()) {
                        val jsonData = document.getString("data")
                        onResult(jsonData)
                    } else {
                        onResult(null)
                    }
                }
                .addOnFailureListener {
                    onResult(null)
                }
        } catch (e: Exception) {
            onResult(null)
        }
    }
}
