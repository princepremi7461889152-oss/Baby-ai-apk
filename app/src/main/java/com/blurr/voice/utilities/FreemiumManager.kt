package com.blurr.voice.utilities

import android.util.Log
import com.blurr.voice.MyApplication
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.tasks.await

class FreemiumManager {

    private fun isFirebaseConfigured(): Boolean {
        return try {
            FirebaseApp.getApps(MyApplication.appContext).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private val db by lazy { FirebaseProviders.firestore(MyApplication.appContext) }
    private val localUserId by lazy { UserIdManager(MyApplication.appContext).getOrCreateUserId() }

    companion object {
        const val DAILY_TASK_LIMIT = 15
        private const val PRO_SKU = "pro"
    }

    suspend fun getDeveloperMessage(): String {
        if (!isFirebaseConfigured()) return ""
        return try {
            val document = db.collection("settings").document("freemium").get().await()
            document.getString("developerMessage") ?: ""
        } catch (e: Exception) {
            FirebaseProviders.handleFirestoreError(
                e,
                FirebaseProviders.OperationType.GET,
                "settings/freemium"
            )
            Log.e("FreemiumManager", "Error fetching developer message from Firestore.", e)
            ""
        }
    }

    suspend fun isUserSubscribed(): Boolean {
        if (!isFirebaseConfigured()) return false
        return try {
            val document = db.collection("users").document(localUserId).get().await()
            if (document.exists()) {
                document.getString("plan") == PRO_SKU
            } else {
                false
            }
        } catch (e: Exception) {
            FirebaseProviders.handleFirestoreError(
                e,
                FirebaseProviders.OperationType.GET,
                "users/$localUserId"
            )
            Logger.e("FreemiumManager", "Error checking user plan from Firestore", e)
            false
        }
    }

    suspend fun provisionUserIfNeeded() {
        if (!isFirebaseConfigured()) return
        val userDocRef = db.collection("users").document(localUserId)
        val userEmail = UserProfileManager(MyApplication.appContext).getEmail().orEmpty()

        try {
            val document = userDocRef.get().await()
            if (!document.exists()) {
                Logger.d("FreemiumManager", "Provisioning local user doc: $localUserId")
                val newUser = hashMapOf(
                    "email" to userEmail,
                    "plan" to "free",
                    "tasksRemaining" to DAILY_TASK_LIMIT.toLong(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "memories" to emptyList<Map<String, Any>>(),
                    "taskHistory" to emptyList<Map<String, Any>>(),
                    "conversationHistory" to emptyList<Map<String, Any>>(),
                    "messageHistory" to emptyList<Map<String, Any>>()
                )
                userDocRef.set(newUser).await()
            } else if (document.get("tasksRemaining") == null) {
                userDocRef.update("tasksRemaining", DAILY_TASK_LIMIT.toLong()).await()
            }
        } catch (e: Exception) {
            FirebaseProviders.handleFirestoreError(
                e,
                FirebaseProviders.OperationType.WRITE,
                "users/$localUserId"
            )
            Logger.e("FreemiumManager", "Error provisioning user", e)
        }
    }

    suspend fun getTasksRemaining(): Long? {
        if (!isFirebaseConfigured()) return DAILY_TASK_LIMIT.toLong()
        if (isUserSubscribed()) return Long.MAX_VALUE
        return try {
            val document = db.collection("users").document(localUserId).get().await()
            document.getLong("tasksRemaining") ?: DAILY_TASK_LIMIT.toLong()
        } catch (e: Exception) {
            FirebaseProviders.handleFirestoreError(
                e,
                FirebaseProviders.OperationType.GET,
                "users/$localUserId"
            )
            Logger.e("FreemiumManager", "Error fetching tasks remaining", e)
            DAILY_TASK_LIMIT.toLong()
        }
    }

    suspend fun canPerformTask(): Boolean {
        if (!isFirebaseConfigured()) return true
        if (isUserSubscribed()) return true

        return try {
            val document = db.collection("users").document(localUserId).get().await()
            val tasksRemaining = document.getLong("tasksRemaining") ?: DAILY_TASK_LIMIT.toLong()
            Logger.d("FreemiumManager", "User has $tasksRemaining tasks remaining.")
            tasksRemaining > 0L
        } catch (e: Exception) {
            FirebaseProviders.handleFirestoreError(
                e,
                FirebaseProviders.OperationType.GET,
                "users/$localUserId"
            )
            Logger.e("FreemiumManager", "Error fetching user task count", e)
            true
        }
    }

    suspend fun decrementTaskCount() {
        if (!isFirebaseConfigured()) return
        if (isUserSubscribed()) return

        val userDocRef = db.collection("users").document(localUserId)

        try {
            userDocRef.update("tasksRemaining", FieldValue.increment(-1)).await()
            Logger.d("FreemiumManager", "Successfully decremented task count for user $localUserId.")
        } catch (e: Exception) {
            FirebaseProviders.handleFirestoreError(
                e,
                FirebaseProviders.OperationType.UPDATE,
                "users/$localUserId"
            )
            Logger.e("FreemiumManager", "Failed to decrement task count.", e)
        }
    }
}