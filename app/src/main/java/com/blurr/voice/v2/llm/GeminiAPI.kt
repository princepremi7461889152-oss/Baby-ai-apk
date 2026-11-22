package com.blurr.voice.v2.llm

import android.content.Context
import android.util.Log
import com.blurr.voice.api.AiRepository
import com.blurr.voice.api.AiRepositoryProvider
import com.blurr.voice.v2.AgentOutput
import com.blurr.voice.v2.logging.TaskLogger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GeminiApi(
    private val modelName: String = AiRepository.DEFAULT_MODEL,
    private val context: Context,
    private val maxRetry: Int = 3
) {

    companion object {
        private const val TAG = "GeminiV2Api"
    }

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun generateAgentOutput(messages: List<GeminiMessage>): AgentOutput? {
        val jsonString = AiRepositoryProvider.get(context).generateAgentJson(
            messages = messages,
            modelName = modelName,
            context = context,
            maxRetry = maxRetry
        ) ?: return null

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

    suspend fun generateGroundedContent(prompt: String): String? {
        return AiRepositoryProvider.get(context).generateGroundedText(
            prompt = prompt,
            modelName = modelName
        )
    }
}

class ContentBlockedException(message: String) : Exception(message)