package com.blurr.voice.utilities

import com.blurr.voice.MyApplication

/**
 * Provides the user-configured Gemini API key from Settings for embedding and REST utilities.
 */
object ApiKeyManager {

    fun hasKeys(): Boolean {
        return try {
            GeminiKeyManager(MyApplication.appContext).hasApiKey()
        } catch (_: Exception) {
            false
        }
    }

    fun getNextKey(): String {
        return try {
            GeminiKeyManager(MyApplication.appContext).getApiKey().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}