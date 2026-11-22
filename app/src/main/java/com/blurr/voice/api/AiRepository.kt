package com.blurr.voice.api

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.blurr.voice.MyApplication
import com.blurr.voice.utilities.GeminiKeyManager
import com.blurr.voice.utilities.NetworkConnectivityManager
import com.blurr.voice.utilities.NetworkNotifier
import com.blurr.voice.v2.llm.ContentBlockedException
import com.blurr.voice.v2.llm.GeminiMessage
import com.blurr.voice.v2.llm.MessageRole
import com.blurr.voice.v2.llm.TextPart as V2TextPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

interface AiRepository {
    val isFirebaseAiAvailable: Boolean

    suspend fun generateContent(
        chat: List<Pair<String, List<Any>>>,
        images: List<Bitmap> = emptyList(),
        modelName: String = DEFAULT_MODEL,
        maxRetry: Int = 3,
        context: Context? = null
    ): String?

    suspend fun generateAgentJson(
        messages: List<GeminiMessage>,
        modelName: String = DEFAULT_MODEL,
        context: Context,
        maxRetry: Int = 3
    ): String?

    suspend fun generateGroundedText(
        prompt: String,
        modelName: String = DEFAULT_MODEL
    ): String?

    companion object {
        const val DEFAULT_MODEL = "gemini-3.8-flash"
    }
}

object AiRepositoryProvider {
    fun get(context: Context? = null): AiRepository = GeminiRestAiRepository
}

object GeminiRestAiRepository : AiRepository {
    private const val TAG = "GeminiRestAiRepo"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    private const val REQUEST_TIMEOUT_MS = 60_000L
    private const val MAX_IMAGE_DIM = 1280

    override val isFirebaseAiAvailable: Boolean = true

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun resolveModelName(requested: String): String {
        val trimmed = requested.trim()
        if (trimmed.isEmpty() ||
            trimmed.startsWith("gemini-1.") ||
            trimmed.startsWith("gemini-2.") ||
            trimmed == "gemini-pro"
        ) {
            return AiRepository.DEFAULT_MODEL
        }
        return trimmed
    }

    private fun getUserApiKey(context: Context?): String? {
        val appCtx = context?.applicationContext ?: MyApplication.appContext
        return GeminiKeyManager(appCtx).getApiKey()
    }

    override suspend fun generateContent(
        chat: List<Pair<String, List<Any>>>,
        images: List<Bitmap>,
        modelName: String,
        maxRetry: Int,
        context: Context?
    ): String? {
        val appCtx = context?.applicationContext ?: MyApplication.appContext
        val lastUserPrompt = chat.lastOrNull { it.first == "user" }
            ?.second
            ?.filterIsInstance<TextPart>()
            ?.joinToString(separator = "\n") { it.text } ?: ""

        val apiKey = getUserApiKey(appCtx)
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "Gemini API key is not configured in Settings.")
            if (lastUserPrompt.contains("memory extraction agent", ignoreCase = true)) {
                return "NO_MEMORIES"
            }
            return "Please set your Gemini API key in Settings to use Gemini 3.8 Flash."
        }

        try {
            val isOnline = NetworkConnectivityManager(appCtx).isNetworkAvailable()
            if (!isOnline) {
                Log.e(TAG, "No internet connection. Skipping generateContent call.")
                NetworkNotifier.notifyOffline()
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network check failed: ${e.message}")
            return null
        }

        val contentsArray = buildRestContents(chat, images)
        if (contentsArray.length() == 0) {
            Log.w(TAG, "No valid content parts to send to Gemini.")
            return null
        }

        val effectiveModelName = resolveModelName(modelName)
        val payload = JSONObject().apply {
            put("contents", contentsArray)
        }

        var attempts = 0
        while (attempts < maxRetry) {
            val attemptStartTime = System.currentTimeMillis()
            Log.d(TAG, "=== GEMINI REST REQUEST (Attempt ${attempts + 1}) model=$effectiveModelName ===")

            try {
                val text = withTimeout(REQUEST_TIMEOUT_MS) {
                    executeGenerateContent(effectiveModelName, apiKey, payload)
                }
                val totalTime = System.currentTimeMillis() - attemptStartTime
                saveLogToFile(
                    appCtx,
                    createLogEntry(
                        attempt = attempts + 1,
                        modelName = effectiveModelName,
                        prompt = lastUserPrompt.ifBlank { "Prompt" },
                        imagesCount = images.size,
                        responseTime = totalTime,
                        responseBody = text
                    )
                )
                return text
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) {
                    val totalTime = System.currentTimeMillis() - attemptStartTime
                    Log.e(TAG, "=== GEMINI TIMEOUT (Attempt ${attempts + 1}) ===", e)
                    saveLogToFile(
                        appCtx,
                        createLogEntry(attempts + 1, effectiveModelName, lastUserPrompt, images.size, totalTime, null, e.message)
                    )
                } else {
                    throw e
                }
            } catch (e: NonRetryableApiException) {
                val totalTime = System.currentTimeMillis() - attemptStartTime
                Log.e(TAG, "=== GEMINI NON-RETRYABLE ERROR (${e.statusCode}) ===", e)
                saveLogToFile(
                    appCtx,
                    createLogEntry(attempts + 1, effectiveModelName, lastUserPrompt, images.size, totalTime, null, e.message)
                )
                if (e.statusCode == 401 || e.statusCode == 403) {
                    return "Invalid or unauthorized Gemini API key. Please verify your key in Settings."
                }
                return null
            } catch (e: Exception) {
                val totalTime = System.currentTimeMillis() - attemptStartTime
                Log.e(TAG, "=== GEMINI ERROR (Attempt ${attempts + 1}) ===", e)
                saveLogToFile(
                    appCtx,
                    createLogEntry(attempts + 1, effectiveModelName, lastUserPrompt, images.size, totalTime, null, e.message)
                )
            }

            attempts++
            if (attempts < maxRetry) {
                delay(1000L * attempts)
            }
        }

        Log.e(TAG, "Request failed after all $maxRetry retries.")
        return null
    }

    override suspend fun generateAgentJson(
        messages: List<GeminiMessage>,
        modelName: String,
        context: Context,
        maxRetry: Int
    ): String? {
        val apiKey = getUserApiKey(context)
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "Gemini API key not configured; returning actionable done JSON.")
            return buildMissingKeyAgentJson()
        }

        val contentsArray = convertV2HistoryToRest(messages)
        if (contentsArray.length() == 0) return null

        val effectiveModelName = resolveModelName(modelName)
        val payload = JSONObject().apply {
            put("contents", contentsArray)
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
            })
        }

        var currentDelay = 1000L
        repeat(maxRetry) { attempt ->
            try {
                return withTimeout(REQUEST_TIMEOUT_MS) {
                    executeGenerateContent(effectiveModelName, apiKey, payload)
                }
            } catch (e: CancellationException) {
                if (e is TimeoutCancellationException) {
                    Log.e(TAG, "Attempt ${attempt + 1}/$maxRetry timed out", e)
                    if (attempt == maxRetry - 1) return null
                } else {
                    throw e
                }
            } catch (e: NonRetryableApiException) {
                Log.e(TAG, "Non-retryable Gemini error (${e.statusCode}): ${e.message}")
                if (e.statusCode == 401 || e.statusCode == 403) {
                    return buildInvalidKeyAgentJson()
                }
                return null
            } catch (e: Exception) {
                Log.e(TAG, "Attempt ${attempt + 1}/$maxRetry failed: ${e.message}", e)
                if (attempt == maxRetry - 1) return null
            }
            delay(currentDelay)
            currentDelay = (currentDelay * 2).coerceAtMost(16_000L)
        }
        return null
    }

    override suspend fun generateGroundedText(
        prompt: String,
        modelName: String
    ): String? {
        val apiKey = getUserApiKey(MyApplication.appContext)
        if (apiKey.isNullOrBlank()) {
            return "Please set your Gemini API key in Settings to use Gemini 3.8 Flash."
        }

        val effectiveModelName = resolveModelName(modelName)
        val payload = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().put("text", prompt))
                    })
                })
            })
        }

        return try {
            withTimeout(REQUEST_TIMEOUT_MS) {
                executeGenerateContent(effectiveModelName, apiKey, payload)
            }
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                Log.e(TAG, "Grounded content timed out", e)
                null
            } else {
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Gemini REST call", e)
            null
        }
    }

    private suspend fun executeGenerateContent(
        modelName: String,
        apiKey: String,
        payload: JSONObject
    ): String = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/$modelName:generateContent"
        val request = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val msg = extractApiErrorMessage(bodyString, response.code)
                if (response.code in listOf(400, 401, 403, 404)) {
                    throw NonRetryableApiException(response.code, msg)
                }
                throw IllegalStateException(msg)
            }
            parseResponseText(bodyString)
        }
    }

    private fun parseResponseText(responseBody: String): String {
        val json = JSONObject(responseBody)
        val promptFeedback = json.optJSONObject("promptFeedback")
        val blockReason = promptFeedback?.optString("blockReason").orEmpty()
        if (blockReason.isNotEmpty()) {
            throw ContentBlockedException("Blocked by Gemini safety filter: $blockReason")
        }

        val candidates = json.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            throw IllegalStateException("Gemini returned no candidates.")
        }

        val firstCandidate = candidates.getJSONObject(0)
        val content = firstCandidate.optJSONObject("content")
        val parts = content?.optJSONArray("parts")
        val builder = StringBuilder()
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val text = parts.optJSONObject(i)?.optString("text").orEmpty()
                if (text.isNotEmpty()) {
                    if (builder.isNotEmpty()) builder.append("\n")
                    builder.append(text)
                }
            }
        }

        val result = builder.toString().trim()
        if (result.isEmpty()) {
            val finishReason = firstCandidate.optString("finishReason", "UNKNOWN")
            throw IllegalStateException("Gemini returned empty text (finishReason=$finishReason).")
        }
        return result
    }

    private fun extractApiErrorMessage(bodyString: String, code: Int): String {
        return try {
            val errorObj = JSONObject(bodyString).optJSONObject("error")
            val message = errorObj?.optString("message").orEmpty()
            if (message.isNotEmpty()) "HTTP $code: $message" else "HTTP $code: $bodyString"
        } catch (_: Exception) {
            "HTTP $code: $bodyString"
        }
    }

    private fun convertV2HistoryToRest(messages: List<GeminiMessage>): JSONArray {
        val array = JSONArray()
        messages.forEach { message ->
            val role = when (message.role) {
                MessageRole.USER -> "user"
                MessageRole.MODEL -> "model"
                MessageRole.TOOL -> "user"
            }
            val partsArray = JSONArray()
            message.parts.forEach { part ->
                if (part is V2TextPart && part.text.isNotEmpty()) {
                    partsArray.put(JSONObject().put("text", part.text))
                }
            }
            if (partsArray.length() > 0) {
                array.put(JSONObject().apply {
                    put("role", role)
                    put("parts", partsArray)
                })
            }
        }
        return array
    }

    private fun buildRestContents(
        chat: List<Pair<String, List<Any>>>,
        extraImages: List<Bitmap>
    ): JSONArray {
        val contents = JSONArray()
        chat.forEachIndexed { index, (role, parts) ->
            val restRole = if (role.equals("model", ignoreCase = true)) "model" else "user"
            val isLast = index == chat.lastIndex
            val partsArray = JSONArray()

            parts.forEach { part ->
                when (part) {
                    is TextPart -> if (part.text.isNotEmpty()) {
                        partsArray.put(JSONObject().put("text", part.text))
                    }
                    is ImagePart -> partsArray.put(encodeBitmapPart(part.image))
                    is Bitmap -> partsArray.put(encodeBitmapPart(part))
                    is String -> if (part.isNotEmpty()) {
                        partsArray.put(JSONObject().put("text", part))
                    }
                }
            }

            if (isLast && restRole == "user") {
                extraImages.forEach { bitmap ->
                    partsArray.put(encodeBitmapPart(bitmap))
                }
            }

            if (partsArray.length() > 0) {
                contents.put(JSONObject().apply {
                    put("role", restRole)
                    put("parts", partsArray)
                })
            }
        }

        if (contents.length() == 0 && extraImages.isNotEmpty()) {
            val partsArray = JSONArray()
            extraImages.forEach { bitmap -> partsArray.put(encodeBitmapPart(bitmap)) }
            contents.put(JSONObject().apply {
                put("role", "user")
                put("parts", partsArray)
            })
        }
        return contents
    }

    private fun encodeBitmapPart(bitmap: Bitmap): JSONObject {
        val scaled = scaleBitmapIfNeeded(bitmap)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
        val base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return JSONObject().put(
            "inline_data",
            JSONObject().apply {
                put("mime_type", "image/jpeg")
                put("data", base64)
            }
        )
    }

    private fun scaleBitmapIfNeeded(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val maxSide = maxOf(width, height)
        if (maxSide <= MAX_IMAGE_DIM || width <= 0 || height <= 0) return bitmap
        val ratio = MAX_IMAGE_DIM.toFloat() / maxSide.toFloat()
        val newW = (width * ratio).toInt().coerceAtLeast(1)
        val newH = (height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    private fun buildMissingKeyAgentJson(): String = """
        {
          "thinking": "No Gemini API key is configured in Settings.",
          "evaluationPreviousGoal": "None",
          "memory": "",
          "nextGoal": "Ask the user to add their Gemini API key in Settings.",
          "action": [
            {
              "done": {
                "success": false,
                "text": "Please open Settings and add your personal Gemini API key to run Gemini 3.8 Flash."
              }
            }
          ]
        }
    """.trimIndent()

    private fun buildInvalidKeyAgentJson(): String = """
        {
          "thinking": "The configured Gemini API key was rejected.",
          "evaluationPreviousGoal": "None",
          "memory": "",
          "nextGoal": "Ask the user to verify their Gemini API key in Settings.",
          "action": [
            {
              "done": {
                "success": false,
                "text": "Your Gemini API key appears invalid or unauthorized. Please update it in Settings."
              }
            }
          ]
        }
    """.trimIndent()

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
            appendLine("=== GEMINI REST DEBUG LOG ===")
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

    private class NonRetryableApiException(val statusCode: Int, message: String) : Exception(message)
}
