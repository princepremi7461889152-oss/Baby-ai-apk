package com.blurr.voice.utilities

import android.content.Context
import android.content.Intent
import android.util.Log
import com.blurr.voice.MyApplication
import com.blurr.voice.R
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore

object FirebaseProviders {

    private const val TAG = "FirebaseProviders"
    private const val FIRESTORE_ERROR_TAG = "FirebaseErrorHandler"

    enum class OperationType {
        CREATE, UPDATE, DELETE, GET, LIST, WRITE
    }

    /**
     * Retained as a safe no-op after removing Firebase App Check and Firebase Auth.
     */
    fun configureAppCheck(context: Context, intent: Intent?) {
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                Log.d(TAG, "Firebase initialized; App Check disabled.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase check skipped: ${e.message}")
        }
    }

    fun firestore(context: Context = MyApplication.appContext): FirebaseFirestore {
        return FirebaseFirestore.getInstance(context.getString(R.string.firestore_database_id))
    }

    fun handleFirestoreError(
        error: Exception?,
        operationType: OperationType,
        path: String
    ) {
        val localUserId = try {
            UserIdManager(MyApplication.appContext).getOrCreateUserId()
        } catch (_: Exception) {
            "local"
        }
        val errMsg = error?.message ?: "Unknown Firestore error"
        val jsonLog = """
            {
              "error": "${errMsg.replace("\"", "'")}",
              "operationType": "${operationType.name.lowercase()}",
              "path": "$path",
              "userId": "$localUserId"
            }
        """.trimIndent()
        Log.e(FIRESTORE_ERROR_TAG, "Firestore Operation Failed: $jsonLog", error)
    }
}
