package com.blurr.voice.api

import android.util.Log
import com.blurr.voice.utilities.AiEnginePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

data class GroqChatMessage(
    val role: String,
    val content: String
)

/**
 * Secondary AI inference client backed by Groq's OpenAI-compatible API.
 * Supports fast text reasoning (chat completions), optional Whisper STT, and connection verification.
 * Never logs API keys or authorization headers.
 */
class GroqApiClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: OkHttpClient = defaultClient
) {

    companion object {
        private const val TAG = "GroqApiClient"
        const val DEFAULT_BASE_URL = "https://api.groq.com/openai/v1"

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(25, TimeUnit.SECONDS)
                .build()
        }

        val shared: GroqApiClient by lazy { GroqApiClient() }

        /**
         * Wraps raw 16-bit mono PCM bytes into a standard WAV container for Whisper transcription.
         */
        fun pcm16ToWav(pcmData: ByteArray, sampleRate: Int = 16000, channels: Int = 1): ByteArray {
            val byteRate = sampleRate * channels * 2
            val dataSize = pcmData.size
            val chunkSize = 36 + dataSize

            val out = ByteArrayOutputStream(44 + dataSize)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(chunkSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16) // PCM subchunk size
            header.putShort(1) // AudioFormat = 1 (PCM)
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(byteRate)
            header.putShort((channels * 2).toShort())
            header.putShort(16) // BitsPerSample
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)
            out.write(header.array())
            out.write(pcmData)
            return out.toByteArray()
        }
    }

    suspend fun chatCompletion(
        apiKey: String,
        messages: List<GroqChatMessage>,
        modelId: String = AiEnginePreferences.DEFAULT_GROQ_MODEL,
        systemPrompt: String? = null,
        temperature: Double = 0.4,
        maxTokens: Int = 1024
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext Result.failure(
                IllegalArgumentException("Groq API key is missing. Please add it in Settings.")
            )
        }

        val effectiveModel = modelId.trim().ifEmpty { AiEnginePreferences.DEFAULT_GROQ_MODEL }
        val jsonMessages = JSONArray()
        if (!systemPrompt.isNullOrBlank()) {
            jsonMessages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })
        }
        messages.forEach { msg ->
            if (msg.content.isNotBlank()) {
                val role = when (msg.role.lowercase()) {
                    "model", "assistant" -> "assistant"
                    "system" -> "system"
                    else -> "user"
                }
                jsonMessages.put(JSONObject().apply {
                    put("role", role)
                    put("content", msg.content)
                })
            }
        }

        if (jsonMessages.length() == 0) {
            return@withContext Result.failure(IllegalArgumentException("No prompt text provided for Groq."))
        }

        val payload = JSONObject().apply {
            put("model", effectiveModel)
            put("messages", jsonMessages)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
        }

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $cleanKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val friendly = formatGroqError(response.code, bodyStr, effectiveModel)
                    Log.w(TAG, "Groq chat completion failed with status ${response.code} for model=$effectiveModel")
                    return@withContext Result.failure(IllegalStateException(friendly))
                }
                val content = parseChatContent(bodyStr)
                if (content.isBlank()) {
                    return@withContext Result.failure(IllegalStateException("Groq returned an empty response."))
                }
                Result.success(content)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Groq chat request exception: ${e.javaClass.simpleName}")
            Result.failure(IllegalStateException("Network error contacting Groq: ${e.message ?: "Connection failed"}"))
        }
    }

    suspend fun transcribeWav(
        apiKey: String,
        wavBytes: ByteArray,
        modelId: String = AiEnginePreferences.DEFAULT_GROQ_STT_MODEL,
        languageHint: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            return@withContext Result.failure(
                IllegalArgumentException("Groq API key is missing. Please add it in Settings.")
            )
        }
        if (wavBytes.size <= 44) {
            return@withContext Result.failure(IllegalArgumentException("Audio recording is empty."))
        }

        val effectiveModel = modelId.trim().ifEmpty { AiEnginePreferences.DEFAULT_GROQ_STT_MODEL }
        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "speech.wav",
                wavBytes.toRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", effectiveModel)
            .addFormDataPart("response_format", "json")

        if (!languageHint.isNullOrBlank()) {
            multipartBuilder.addFormDataPart("language", languageHint.trim())
        }

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/audio/transcriptions")
            .addHeader("Authorization", "Bearer $cleanKey")
            .post(multipartBuilder.build())
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val friendly = formatGroqError(response.code, bodyStr, effectiveModel)
                    Log.w(TAG, "Groq STT failed with status ${response.code} for model=$effectiveModel")
                    return@withContext Result.failure(IllegalStateException(friendly))
                }
                val text = JSONObject(bodyStr).optString("text", "").trim()
                Result.success(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Groq STT exception: ${e.javaClass.simpleName}")
            Result.failure(IllegalStateException("Network error during Groq STT: ${e.message ?: "Connection failed"}"))
        }
    }

    suspend fun testConnection(
        apiKey: String,
        modelId: String = AiEnginePreferences.DEFAULT_GROQ_MODEL
    ): ConnectionTestResult {
        val cleanKey = apiKey.trim()
        val effectiveModel = modelId.trim().ifEmpty { AiEnginePreferences.DEFAULT_GROQ_MODEL }
        if (cleanKey.isEmpty()) {
            return ConnectionTestResult(
                success = false,
                provider = "Groq",
                model = effectiveModel,
                message = "Please enter your Groq API key first."
            )
        }

        val startedAt = System.currentTimeMillis()
        val result = chatCompletion(
            apiKey = cleanKey,
            messages = listOf(GroqChatMessage("user", "Reply with only: OK")),
            modelId = effectiveModel,
            temperature = 0.0,
            maxTokens = 8
        )
        val latency = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)

        return result.fold(
            onSuccess = {
                ConnectionTestResult(
                    success = true,
                    provider = "Groq",
                    model = effectiveModel,
                    latencyMs = latency,
                    message = "Connected to Groq ($effectiveModel) in ${latency}ms."
                )
            },
            onFailure = { error ->
                ConnectionTestResult(
                    success = false,
                    provider = "Groq",
                    model = effectiveModel,
                    latencyMs = latency,
                    message = error.message ?: "Failed to connect to Groq."
                )
            }
        )
    }

    private fun parseChatContent(responseBody: String): String {
        val json = JSONObject(responseBody)
        val choices = json.optJSONArray("choices") ?: return ""
        if (choices.length() == 0) return ""
        val messageObj = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
        return messageObj.optString("content", "").trim()
    }

    private fun formatGroqError(statusCode: Int, responseBody: String, modelId: String): String {
        val apiMessage = try {
            JSONObject(responseBody).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) {
            ""
        }
        return when (statusCode) {
            401 -> "Invalid Groq API key (HTTP 401). Please check your credentials in Settings."
            403 -> "Groq access forbidden (HTTP 403). Verify key permissions${if (apiMessage.isNotBlank()) ": $apiMessage" else "."}"
            404 -> "Groq model '$modelId' was not found (HTTP 404). Choose a supported model."
            429 -> "Groq rate limit or quota exceeded (HTTP 429). Please retry in a moment."
            in 500..599 -> "Groq service temporarily unavailable (HTTP $statusCode). Please retry."
            else -> if (apiMessage.isNotBlank()) "Groq error (HTTP $statusCode): $apiMessage" else "Groq request failed (HTTP $statusCode)."
        }
    }
}
