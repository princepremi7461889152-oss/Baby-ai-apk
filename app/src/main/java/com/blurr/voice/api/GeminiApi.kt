package com.blurr.voice.api

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.blurr.voice.MyApplication
import com.blurr.voice.utilities.FirebaseProviders
import com.blurr.voice.utilities.NetworkConnectivityManager
import com.blurr.voice.utilities.NetworkNotifier
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class TextPart(val text: String)

data class ImagePart(val image: Bitmap)

/**
 * Gemini API client backed by Firebase AI Logic (`Firebase.ai`) and App Check attestation.
 * Requires no client-side Gemini API key.
 */
object GeminiApi {

    private const val TAG = "GeminiApi"
    private const val DEFAULT_MODEL = "gemini-3.8-flash"
    private const val REQUEST_TIMEOUT_MS = 60_000L

    private val modelCache = ConcurrentHashMap<String, GenerativeModel>()

    private fun getModel(modelName: String): GenerativeModel {
        val resolvedModel = resolveModelName(modelName)
        return modelCache.getOrPut(resolvedModel) {
            Firebase.ai.generativeModel(modelName = resolvedModel)
        }
    }

    private fun resolveModelName(requested: String): String {
        val trimmed = requested.trim()
        if (trimmed.isEmpty() ||
            trimmed.startsWith("gemini-1.") ||
            trimmed.startsWith("gemini-2.") ||
            trimmed == "gemini-pro"
        ) {
            return DEFAULT_MODEL
        }
        return trimmed
    }

    suspend fun generateContent(
        chat: List<Pair<String, List<Any>>>,
        images: List<Bitmap> = emptyList(),
        modelName: String = DEFAULT_MODEL,
        maxRetry: Int = 3,
        context: Context? = null
    ): String? {
        val appCtx = context?.applicationContext ?: MyApplication.appContext
        try {
            val isOnline = NetworkConnectivityManager(appCtx).isNetworkAvailable()
            if (!isOnline) {
                Log.e(TAG, "No internet connection. Skipping generateContent call.")
                NetworkNotifier.notifyOffline()
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network check failed, assuming offline. ${e.message}")
            return null
        }

        val lastUserPrompt = chat.lastOrNull { it.first == "user" }
            ?.second
            ?.filterIsInstance<TextPart>()
            ?.joinToString(separator = "\n") { it.text } ?: "No text prompt found"

        val sdkContents = buildSdkContents(chat, images)
        if (sdkContents.isEmpty()) {
            Log.w(TAG, "No valid content parts to send to Firebase AI Logic.")
            return null
        }

        val effectiveModelName = resolveModelName(modelName)
        var attempts = 0
        while (attempts < maxRetry) {
            val attemptStartTime = System.currentTimeMillis()
            Log.d(TAG, "=== FIREBASE AI REQUEST (Attempt ${attempts + 1}) model=$effectiveModelName ===")

            try {
                val model = getModel(effectiveModelName)
                val response = withTimeout(REQUEST_TIMEOUT_MS) {
                    model.generateContent(sdkContents)
                }
                val responseEndTime = System.currentTimeMillis()
                val totalTime = responseEndTime - attemptStartTime
                val text = response.text

                if (text.isNullOrBlank()) {
                    throw IllegalStateException("Firebase AI returned an empty response.")
                }

                Log.d(TAG, "=== FIREBASE AI RESPONSE (Attempt ${attempts + 1}, ${totalTime}ms) ===")

                val logEntry = createLogEntry(
                    attempt = attempts + 1,
                    modelName = effectiveModelName,
                    prompt = lastUserPrompt,
                    imagesCount = images.size,
                    responseTime = totalTime,
                    responseBody = text
                )
                saveLogToFile(appCtx, logEntry)
                logToFirestore(
                    appCtx,
                    createLogDataMap(
                        attempt = attempts + 1,
                        modelName = effectiveModelName,
                        prompt = lastUserPrompt,
                        imagesCount = images.size,
                        responseTime = totalTime,
                        status = "pass",
                        responseBody = text
                    )
                )

                return text
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) {
                    val totalTime = System.currentTimeMillis() - attemptStartTime
                    Log.e(TAG, "=== FIREBASE AI TIMEOUT (Attempt ${attempts + 1}) ===", e)
                    recordFailure(appCtx, attempts + 1, effectiveModelName, lastUserPrompt, images.size, totalTime, e)
                } else {
                    throw e
                }
            } catch (e: Exception) {
                val totalTime = System.currentTimeMillis() - attemptStartTime
                Log.e(TAG, "=== FIREBASE AI ERROR (Attempt ${attempts + 1}) ===", e)
                recordFailure(appCtx, attempts + 1, effectiveModelName, lastUserPrompt, images.size, totalTime, e)
            }

            attempts++
            if (attempts < maxRetry) {
                val delayTime = 1000L * attempts
                Log.d(TAG, "Retrying in ${delayTime}ms...")
                delay(delayTime)
            }
        }

        Log.e(TAG, "Request failed after all $maxRetry retries.")
        return null
    }

    private fun recordFailure(
        appCtx: Context,
        attempt: Int,
        modelName: String,
        prompt: String,
        imagesCount: Int,
        totalTime: Long,
        error: Exception
    ) {
        val logEntry = createLogEntry(
            attempt = attempt,
            modelName = modelName,
            prompt = prompt,
            imagesCount = imagesCount,
            responseTime = totalTime,
            responseBody = null,
            error = error.message
        )
        saveLogToFile(appCtx, logEntry)
        logToFirestore(
            appCtx,
            createLogDataMap(
                attempt = attempt,
                modelName = modelName,
                prompt = prompt,
                imagesCount = imagesCount,
                responseTime = totalTime,
                status = "error",
                responseBody = null,
                error = error.message
            )
        )
    }

    private fun buildSdkContents(
        chat: List<Pair<String, List<Any>>>,
        extraImages: List<Bitmap>
    ): List<Content> {
        val contents = mutableListOf<Content>()
        chat.forEachIndexed { index, (role, parts) ->
            val sdkRole = if (role.equals("model", ignoreCase = true)) "model" else "user"
            val isLast = index == chat.lastIndex
            val built = content(sdkRole) {
                parts.forEach { part ->
                    when (part) {
                        is TextPart -> if (part.text.isNotEmpty()) text(part.text)
                        is ImagePart -> image(part.image)
                        is Bitmap -> image(part)
                        is String -> if (part.isNotEmpty()) text(part)
                    }
                }
                if (isLast && sdkRole == "user") {
                    extraImages.forEach { bitmap -> image(bitmap) }
                }
            }
            if (built.parts.isNotEmpty()) {
                contents.add(built)
            }
        }

        if (contents.isEmpty() && extraImages.isNotEmpty()) {
            contents.add(
                content("user") {
                    extraImages.forEach { bitmap -> image(bitmap) }
                }
            )
        }
        return contents
    }

    private fun saveLogToFile(context: Context, logEntry: String) {
        try {
            val logDir = File(context.filesDir, "gemini_logs")
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            val logFile = File(logDir, "gemini_api_log.txt")
            FileWriter(logFile, true).use { writer ->
                writer.append(logEntry)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save log to file", e)
        }
    }

    private fun logToFirestore(context: Context, logData: Map<String, Any?>) {
        val uid = try {
            Firebase.auth.currentUser?.uid
        } catch (_: Exception) {
            null
        } ?: return

        val timestamp = System.currentTimeMillis()
        val promptSnippet = (logData["prompt"] as? String)?.take(40) ?: "log"
        val sanitizedPrompt = promptSnippet.replace(Regex("[^a-zA-Z0-9]"), "_")
        val documentId = "${timestamp}_$sanitizedPrompt"
        val path = "users/$uid/gemini_logs/$documentId"

        try {
            FirebaseProviders.firestore(context)
                .collection("users")
                .document(uid)
                .collection("gemini_logs")
                .document(documentId)
                .set(logData + ("userId" to uid))
                .addOnSuccessListener {
                    Log.d(TAG, "Log sent to Firestore: $path")
                }
                .addOnFailureListener { e ->
                    FirebaseProviders.handleFirestoreError(
                        e,
                        FirebaseProviders.OperationType.CREATE,
                        path
                    )
                }
        } catch (e: Exception) {
            FirebaseProviders.handleFirestoreError(
                e,
                FirebaseProviders.OperationType.CREATE,
                path
            )
        }
    }

    private fun createLogEntry(
        attempt: Int,
        modelName: String,
        prompt: String,
        imagesCount: Int,
        responseTime: Long,
        responseBody: String?,
        error: String? = null
    ): String {
        return buildString {
            appendLine("=== FIREBASE AI DEBUG LOG ===")
            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())}")
            appendLine("Attempt: $attempt")
            appendLine("Model: $modelName")
            appendLine("Images count: $imagesCount")
            appendLine("Prompt length: ${prompt.length}")
            appendLine("Response time: ${responseTime}ms")
            if (error != null) {
                appendLine("Error: $error")
            } else {
                appendLine("Response body: $responseBody")
            }
            appendLine("=== END LOG ===")
        }
    }

    private fun createLogDataMap(
        attempt: Int,
        modelName: String,
        prompt: String,
        imagesCount: Int,
        responseTime: Long,
        status: String,
        responseBody: String?,
        error: String? = null
    ): Map<String, Any?> {
        return mapOf(
            "timestamp" to FieldValue.serverTimestamp(),
            "status" to status,
            "attempt" to attempt,
            "model" to modelName,
            "prompt" to prompt.take(2000),
            "imagesCount" to imagesCount,
            "responseTimeMs" to responseTime,
            "llmReply" to responseBody?.take(4000),
            "error" to error?.take(1000)
        )
    }
}