package com.blurr.voice.api

import android.content.Context
import android.graphics.Bitmap

data class TextPart(val text: String)

data class ImagePart(val image: Bitmap)

/**
 * Gemini API client delegating to [AiRepository] (Gemini 3.8 Flash REST API
 * using the user's Gemini API key configured in Settings).
 */
object GeminiApi {

    private const val DEFAULT_MODEL = AiRepository.DEFAULT_MODEL

    suspend fun generateContent(
        chat: List<Pair<String, List<Any>>>,
        images: List<Bitmap> = emptyList(),
        modelName: String = DEFAULT_MODEL,
        maxRetry: Int = 3,
        context: Context? = null
    ): String? {
        return AiRepositoryProvider.get(context).generateContent(
            chat = chat,
            images = images,
            modelName = modelName,
            maxRetry = maxRetry,
            context = context
        )
    }
}