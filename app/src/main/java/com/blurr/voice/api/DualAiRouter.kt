package com.blurr.voice.api

import android.content.Context
import android.graphics.Bitmap
import com.blurr.voice.utilities.AiEnginePreferences
import com.blurr.voice.utilities.AiEnginePreferences.TextRoutingMode
import com.blurr.voice.utilities.GeminiKeyManager
import com.blurr.voice.utilities.GroqKeyManager

data class DualAiTextResponse(
    val text: String,
    val provider: String,
    val modelId: String
)

/**
 * Coordinates Gemini (primary real-time voice + multimodal reasoning)
 * and Groq (secondary fast text processing and reasoning + optional Whisper STT).
 *
 * Voice output policy:
 * - Gemini Live native audio is the default voice output.
 * - Gemini responses are never converted into Groq TTS.
 */
object DualAiRouter {

    suspend fun generateText(
        context: Context,
        chat: List<Pair<String, List<Any>>>,
        images: List<Bitmap> = emptyList(),
        modeOverride: TextRoutingMode? = null,
        groqClient: GroqApiClient = GroqApiClient.shared
    ): DualAiTextResponse {
        val appCtx = context.applicationContext
        val geminiKeyManager = GeminiKeyManager(appCtx)
        val groqKeyManager = GroqKeyManager(appCtx)
        val hasGemini = geminiKeyManager.hasApiKey()
        val hasGroq = groqKeyManager.hasApiKey()
        val mode = modeOverride ?: AiEnginePreferences.getTextRoutingMode(appCtx)

        val hasImages = images.isNotEmpty() || chat.any { (_, parts) ->
            parts.any { it is ImagePart || it is Bitmap }
        }

        // Multimodal image requests always use Gemini when configured.
        if (hasImages) {
            if (!hasGemini) {
                return DualAiTextResponse(
                    text = "Please set your Gemini API key in Settings to analyze images with Gemini 3.8 Flash.",
                    provider = "None",
                    modelId = AiRepository.DEFAULT_MODEL
                )
            }
            val reply = GeminiApi.generateContent(chat = chat, images = images, context = appCtx)
                ?: "Unable to get a response from Gemini right now. Please check your network and API key."
            return DualAiTextResponse(reply, "Gemini", AiRepository.DEFAULT_MODEL)
        }

        val groqModel = AiEnginePreferences.getGroqModel(appCtx)
        val preferGroq = when (mode) {
            TextRoutingMode.GROQ_FAST -> hasGroq
            TextRoutingMode.GEMINI_PRIMARY -> !hasGemini && hasGroq
            TextRoutingMode.AUTO_DUAL -> !hasGemini && hasGroq
        }

        if (preferGroq) {
            val groqKey = groqKeyManager.getApiKey().orEmpty()
            val groqMessages = toGroqMessages(chat)
            val groqResult = groqClient.chatCompletion(
                apiKey = groqKey,
                messages = groqMessages,
                modelId = groqModel
            )
            groqResult.getOrNull()?.let { text ->
                return DualAiTextResponse(text, "Groq", groqModel)
            }

            // Fallback to Gemini if Groq failed and Gemini key is configured
            if (hasGemini) {
                val geminiReply = GeminiApi.generateContent(chat = chat, context = appCtx)
                if (!geminiReply.isNullOrBlank() && !isKeyErrorMessage(geminiReply)) {
                    return DualAiTextResponse(geminiReply, "Gemini (Fallback)", AiRepository.DEFAULT_MODEL)
                }
            }

            val errMsg = groqResult.exceptionOrNull()?.message
                ?: "Groq could not complete the request. Please verify your Groq API key and model in Settings."
            return DualAiTextResponse(errMsg, "Groq (Error)", groqModel)
        }

        // Primary Gemini path
        if (hasGemini) {
            val geminiReply = GeminiApi.generateContent(chat = chat, context = appCtx)
            if (!geminiReply.isNullOrBlank() && !isKeyErrorMessage(geminiReply)) {
                return DualAiTextResponse(geminiReply, "Gemini", AiRepository.DEFAULT_MODEL)
            }

            // In AUTO_DUAL mode, fail over to Groq for text reasoning if Gemini had a transient failure
            if (mode == TextRoutingMode.AUTO_DUAL && hasGroq) {
                val groqKey = groqKeyManager.getApiKey().orEmpty()
                val groqResult = groqClient.chatCompletion(
                    apiKey = groqKey,
                    messages = toGroqMessages(chat),
                    modelId = groqModel
                )
                groqResult.getOrNull()?.let { text ->
                    return DualAiTextResponse(text, "Groq (Fallback)", groqModel)
                }
            }

            return DualAiTextResponse(
                text = geminiReply ?: "Unable to get a response from Gemini right now. Please check your network and API key.",
                provider = "Gemini",
                modelId = AiRepository.DEFAULT_MODEL
            )
        }

        // Neither Gemini nor Groq configured
        return DualAiTextResponse(
            text = "Please add your Gemini API key (or Groq API key for fast text reasoning) in Settings.",
            provider = "None",
            modelId = AiRepository.DEFAULT_MODEL
        )
    }

    private fun toGroqMessages(chat: List<Pair<String, List<Any>>>): List<GroqChatMessage> {
        return chat.mapNotNull { (role, parts) ->
            val text = parts.mapNotNull { part ->
                when (part) {
                    is TextPart -> part.text
                    is String -> part
                    else -> null
                }
            }.joinToString("\n").trim()
            if (text.isEmpty()) null else GroqChatMessage(role = role, content = text)
        }
    }

    private fun isKeyErrorMessage(reply: String): Boolean {
        return reply.startsWith("Please set your Gemini API key", ignoreCase = true) ||
            reply.startsWith("Invalid or unauthorized Gemini API key", ignoreCase = true)
    }
}
