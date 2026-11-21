package com.blurr.voice.utilities

import android.content.Context
import android.content.Intent
import android.util.Log
import com.blurr.voice.BuildConfig
import com.blurr.voice.MyApplication
import com.blurr.voice.R
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

object FirebaseProviders {

    private const val APP_CHECK_TAG = "AppCheckInit"
    private const val FIRESTORE_ERROR_TAG = "FirebaseErrorHandler"

    // App Check contract keys defined by the Firebase SDK and runtime harness.
    private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
    private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
    private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
    private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

    enum class OperationType {
        CREATE, UPDATE, DELETE, GET, LIST, WRITE
    }

    @Volatile
    private var appCheckConfigured = false

    fun configureAppCheck(context: Context, intent: Intent?) {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }

            if (appCheckConfigured) {
                return
            }

            if (BuildConfig.DEBUG) {
                val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
                if (!debugToken.isNullOrEmpty()) {
                    val persistenceKey = FirebaseApp.getInstance().persistenceKey
                    val prefs = context.getSharedPreferences(
                        "$DEBUG_STORE_PREFIX$persistenceKey",
                        Context.MODE_PRIVATE
                    )
                    // Synchronously commit before installing DebugAppCheckProviderFactory
                    prefs.edit()
                        .putString(DEBUG_SECRET_KEY, debugToken)
                        .commit()
                    Log.d(APP_CHECK_TAG, "App Check debug secret configured successfully.")
                } else {
                    Log.w(APP_CHECK_TAG, "No debug token found in Intent extra.")
                }

                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
                Log.d(APP_CHECK_TAG, "DebugAppCheckProviderFactory installed.")
            } else {
                val resId = context.resources.getIdentifier(
                    RECAPTCHA_SITE_KEY_RES,
                    "string",
                    context.packageName
                )
                if (resId != 0) {
                    val siteKey = context.getString(resId)
                    if (siteKey.isNotBlank()) {
                        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                            RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                        )
                        Log.d(APP_CHECK_TAG, "RecaptchaAppCheckProviderFactory installed.")
                    } else {
                        Log.e(APP_CHECK_TAG, "recaptcha_site_key resource string is blank.")
                    }
                } else {
                    Log.e(APP_CHECK_TAG, "recaptcha_site_key string resource not found in package.")
                }
            }
            appCheckConfigured = true
        } catch (e: Exception) {
            Log.e(APP_CHECK_TAG, "Failed to initialize Firebase App Check", e)
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
        val currentUser = try {
            FirebaseAuth.getInstance().currentUser
        } catch (_: Exception) {
            null
        }
        val errMsg = error?.message ?: "Unknown Firestore error"
        val jsonLog = """
            {
              "error": "${errMsg.replace("\"", "'")}",
              "operationType": "${operationType.name.lowercase()}",
              "path": "$path",
              "authInfo": {
                "userId": "${currentUser?.uid ?: "null"}",
                "email": "${currentUser?.email ?: "null"}",
                "emailVerified": ${currentUser?.isEmailVerified ?: false},
                "isAnonymous": ${currentUser?.isAnonymous ?: false}
              }
            }
        """.trimIndent()
        Log.e(FIRESTORE_ERROR_TAG, "Firestore Operation Failed: $jsonLog", error)
    }
}
