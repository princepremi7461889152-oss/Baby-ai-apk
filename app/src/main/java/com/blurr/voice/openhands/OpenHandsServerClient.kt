package com.blurr.voice.openhands

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class RemoteConversationSummary(
    val id: String,
    val title: String,
    val status: String
)

/**
 * Optional REST client for connecting Baby to an external OpenHands Agent Server
 * (`openhands-agent-server` / OpenHands Web API). Works without mandatory auth;
 * user-supplied URL & token are passed in explicitly at runtime.
 */
object OpenHandsServerClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun normalizeBaseUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    private fun Request.Builder.applyOptionalBearer(token: String): Request.Builder {
        val clean = token.trim()
        if (clean.isNotEmpty()) {
            header("Authorization", "Bearer $clean")
        }
        return this
    }

    suspend fun checkHealth(rawUrl: String, token: String): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = normalizeBaseUrl(rawUrl)
        if (baseUrl.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Enter an OpenHands Server URL first."))
        }
        try {
            val configReq = Request.Builder()
                .url("$baseUrl/api/options/config")
                .applyOptionalBearer(token)
                .build()
            client.newCall(configReq).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string().orEmpty()
                    val mode = try {
                        JSONObject(body).optString("APP_MODE", "connected")
                    } catch (_: Exception) {
                        "connected"
                    }
                    return@withContext Result.success("Connected ($baseUrl • mode=$mode)")
                }
            }

            val healthReq = Request.Builder()
                .url("$baseUrl/health")
                .applyOptionalBearer(token)
                .build()
            client.newCall(healthReq).execute().use { res ->
                if (res.isSuccessful) {
                    Result.success("Healthy ($baseUrl • HTTP ${res.code})")
                } else {
                    Result.failure(IllegalStateException("Server responded HTTP ${res.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listRemoteConversations(
        rawUrl: String,
        token: String
    ): Result<List<RemoteConversationSummary>> = withContext(Dispatchers.IO) {
        val baseUrl = normalizeBaseUrl(rawUrl)
        if (baseUrl.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Server URL is empty."))
        }
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/conversations")
                .applyOptionalBearer(token)
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("HTTP ${res.code}"))
                }
                val items = parseRemoteConversations(body)
                Result.success(items)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun parseRemoteConversations(rawJson: String): List<RemoteConversationSummary> {
        return try {
            val trimmed = rawJson.trim()
            val array: JSONArray = if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                val obj = JSONObject(trimmed)
                obj.optJSONArray("results") ?: obj.optJSONArray("conversations") ?: JSONArray()
            }
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val id = item.optString("conversation_id")
                        .ifBlank { item.optString("id") }
                    if (id.isNotBlank()) {
                        add(
                            RemoteConversationSummary(
                                id = id,
                                title = item.optString("title").ifBlank { "Conversation $id" },
                                status = item.optString("status").ifBlank { "unknown" }
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun createRemoteConversation(
        rawUrl: String,
        token: String,
        initialPrompt: String
    ): Result<RemoteConversationSummary> = withContext(Dispatchers.IO) {
        val baseUrl = normalizeBaseUrl(rawUrl)
        if (baseUrl.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Server URL is empty."))
        }
        try {
            val payload = JSONObject().apply {
                put("initial_user_msg", initialPrompt)
            }.toString()
            val req = Request.Builder()
                .url("$baseUrl/api/conversations")
                .post(payload.toRequestBody("application/json".toMediaType()))
                .applyOptionalBearer(token)
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("HTTP ${res.code}: ${body.take(160)}"))
                }
                val obj = JSONObject(body)
                val id = obj.optString("conversation_id").ifBlank { obj.optString("id", "remote") }
                Result.success(
                    RemoteConversationSummary(
                        id = id,
                        title = obj.optString("title").ifBlank { initialPrompt.take(48) },
                        status = obj.optString("status").ifBlank { "started" }
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
