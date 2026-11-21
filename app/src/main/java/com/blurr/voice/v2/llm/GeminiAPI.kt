package com.blurr.voice.v2.llm

import android.content.Context
import android.util.Log
import com.blurr.voice.v2.AgentOutput
import com.blurr.voice.v2.logging.TaskLogger
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Gemini API v2 client powered by Firebase AI Logic (`Firebase.ai`) and App Check.
 * Uses lazy model initialization, realistic coroutine timeouts, and structured JSON generation.
 */
class GeminiApi(
    private val modelName: String = DEFAULT_MODEL,
    private val context: Context,
    private val maxRetry: Int = 3
) {

    companion object {
        private const val TAG = "GeminiV2Api"
        private const val DEFAULT_MODEL = "gemini-3.8-flash"
        private const val REQUEST_TIMEOUT_MS = 60_000L
    }

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
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

    private val jsonModel by lazy {
        Firebase.ai.generativeModel(
            modelName = resolveModelName(modelName),
            generationConfig = generationConfig {
                responseMimeType = "application/json"
            }
        )
    }

    private val textModel by lazy {
        Firebase.ai.generativeModel(
            modelName = resolveModelName(modelName)
        )
    }

    suspend fun generateAgentOutput(messages: List<GeminiMessage>): AgentOutput? {
        val jsonString = retryWithBackoff(times = maxRetry) {
            performDirectApiCall(messages)
        } ?: return null

        try {
            val input = jsonParser.encodeToString(messages)
            TaskLogger.log(context, input, jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log task: ${e.message}")
        }

        val cleanedJson = jsonString.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            Log.d(TAG, "Parsing JSON response: $cleanedJson")
            jsonParser.decodeFromString<AgentOutput>(cleanedJson)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON into AgentOutput. Error: ${e.message}", e)
            null
        }
    }

    private suspend fun performDirectApiCall(messages: List<GeminiMessage>): String {
        val history = convertToSdkHistory(messages)
        val response = withTimeout(REQUEST_TIMEOUT_MS) {
            jsonModel.generateContent(history)
        }
        response.text?.let {
            Log.d(TAG, "Successfully received response from Firebase AI Logic.")
            return it
        }
        val reason = response.promptFeedback?.blockReason?.name ?: "UNKNOWN"
        throw ContentBlockedException("Blocked or empty response from Firebase AI. Reason: $reason")
    }

    private fun convertToSdkHistory(messages: List<GeminiMessage>): List<Content> {
        return messages.mapNotNull { message ->
            val role = when (message.role) {
                MessageRole.USER -> "user"
                MessageRole.MODEL -> "model"
                MessageRole.TOOL -> "user"
            }

            val built = content(role) {
                message.parts.forEach { part ->
                    if (part is TextPart && part.text.isNotEmpty()) {
                        text(part.text)
                    }
                }
            }
            if (built.parts.isNotEmpty()) built else null
        }
    }

    suspend fun generateGroundedContent(prompt: String): String? {
        return try {
            val response = withTimeout(REQUEST_TIMEOUT_MS) {
                textModel.generateContent(prompt)
            }
            response.text
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                Log.e(TAG, "Grounded content timed out", e)
                null
            } else {
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during Firebase AI call", e)
            null
        }
    }
}

class ContentBlockedException(message: String) : Exception(message)

private suspend fun <T> retryWithBackoff(
    times: Int,
    initialDelay: Long = 1000L,
    maxDelay: Long = 16000L,
    factor: Double = 2.0,
    block: suspend () -> T
): T? {
    var currentDelay = initialDelay
    repeat(times) { attempt ->
        try {
            return block()
        } catch (e: CancellationException) {
            if (e is TimeoutCancellationException) {
                Log.e("RetryUtil", "Attempt ${attempt + 1}/$times timed out", e)
                if (attempt == times - 1) return null
            } else {
                throw e
            }
        } catch (e: Exception) {
            Log.e("RetryUtil", "Attempt ${attempt + 1}/$times failed: ${e.message}", e)
            if (attempt == times - 1) {
                Log.e("RetryUtil", "All $times retry attempts failed.")
                return null
            }
        }
        delay(currentDelay)
        currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
    }
    return null
}