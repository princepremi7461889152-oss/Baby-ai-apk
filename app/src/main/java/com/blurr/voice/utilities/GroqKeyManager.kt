package com.blurr.voice.utilities

import android.content.Context
import androidx.core.content.edit

/**
 * Manages the user's personal Groq API key entered in Settings.
 * No API keys are hardcoded in source code, build configuration, or logs.
 */
class GroqKeyManager(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "BlurrGroqKeyPrefs"
        private const val KEY_GROQ_API_KEY = "user_groq_api_key"
    }

    fun saveApiKey(apiKey: String) {
        val trimmed = apiKey.trim()
        prefs.edit {
            if (trimmed.isEmpty()) {
                remove(KEY_GROQ_API_KEY)
            } else {
                putString(KEY_GROQ_API_KEY, trimmed)
            }
        }
    }

    fun getApiKey(): String? {
        return prefs.getString(KEY_GROQ_API_KEY, null)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun hasApiKey(): Boolean = !getApiKey().isNullOrBlank()

    fun clearApiKey() {
        prefs.edit { remove(KEY_GROQ_API_KEY) }
    }

    fun getMaskedKey(): String {
        val key = getApiKey() ?: return "Not configured"
        return if (key.length > 6) {
            "••••••${key.takeLast(4)}"
        } else {
            "Configured"
        }
    }
}
