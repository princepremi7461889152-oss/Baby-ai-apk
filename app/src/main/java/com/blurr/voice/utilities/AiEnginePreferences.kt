package com.blurr.voice.utilities

import android.content.Context
import androidx.core.content.edit

/**
 * Central preferences for the Dual AI Engine (Gemini + Groq).
 * Ensures one consistent Gemini Live voice per conversation, configurable Groq models,
 * optional Groq Whisper STT, and clear routing between voice and text providers.
 */
object AiEnginePreferences {

    private const val PREFS_NAME = "BlurrDualAiPrefs"

    private const val KEY_USE_GEMINI_LIVE = "use_gemini_live_voice"
    private const val KEY_GEMINI_LIVE_MODEL = "gemini_live_model_id"
    private const val KEY_GEMINI_LIVE_VOICE = "gemini_live_voice_name"
    private const val KEY_GROQ_MODEL = "groq_model_id"
    private const val KEY_GROQ_CUSTOM_MODEL = "groq_custom_model_id"
    private const val KEY_GROQ_STT_ENABLED = "groq_whisper_stt_enabled"
    private const val KEY_GROQ_STT_MODEL = "groq_stt_model_id"
    private const val KEY_TEXT_ROUTING_MODE = "dual_ai_text_routing_mode"

    const val DEFAULT_GEMINI_LIVE_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
    const val DEFAULT_GEMINI_LIVE_VOICE = "Aoede"
    const val DEFAULT_GROQ_MODEL = "llama-3.3-70b-versatile"
    const val DEFAULT_GROQ_STT_MODEL = "whisper-large-v3-turbo"

    enum class TextRoutingMode(val id: String, val displayLabel: String) {
        AUTO_DUAL("auto_dual", "Auto Dual (Gemini + Groq)"),
        GEMINI_PRIMARY("gemini_primary", "Gemini Primary (3.8 Flash)"),
        GROQ_FAST("groq_fast", "Groq Fast Reasoning");

        companion object {
            fun fromId(id: String?): TextRoutingMode {
                return entries.firstOrNull { it.id == id } ?: AUTO_DUAL
            }
        }
    }

    data class ModelOption(val id: String, val displayLabel: String)

    val SUPPORTED_GROQ_MODELS = listOf(
        ModelOption("llama-3.3-70b-versatile", "Llama 3.3 70B Versatile (Recommended)"),
        ModelOption("llama-3.1-8b-instant", "Llama 3.1 8B Instant (Ultra-Fast)"),
        ModelOption("openai/gpt-oss-120b", "GPT-OSS 120B (Deep Reasoning)"),
        ModelOption("openai/gpt-oss-20b", "GPT-OSS 20B (Balanced Fast)")
    )

    val SUPPORTED_GROQ_STT_MODELS = listOf(
        ModelOption("whisper-large-v3-turbo", "Whisper Large v3 Turbo (Fast Hindi/English)"),
        ModelOption("whisper-large-v3", "Whisper Large v3 (High Accuracy)")
    )

    val SUPPORTED_GEMINI_LIVE_VOICES = listOf(
        ModelOption("Aoede", "Aoede (Warm, Natural)"),
        ModelOption("Kore", "Kore (Clear, Calm)"),
        ModelOption("Puck", "Puck (Upbeat, Expressive)"),
        ModelOption("Zephyr", "Zephyr (Bright, Conversational)"),
        ModelOption("Charon", "Charon (Deep, Steady)")
    )

    val SUPPORTED_GEMINI_LIVE_MODELS = listOf(
        ModelOption("gemini-2.5-flash-native-audio-preview-12-2025", "Gemini 2.5 Flash Native Audio (Live)"),
        ModelOption("gemini-2.5-flash-native-audio-latest", "Gemini 2.5 Flash Native Audio Latest")
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isGeminiLiveVoiceEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_GEMINI_LIVE, true)

    fun setGeminiLiveVoiceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_USE_GEMINI_LIVE, enabled) }
    }

    fun getGeminiLiveModel(context: Context): String =
        prefs(context).getString(KEY_GEMINI_LIVE_MODEL, DEFAULT_GEMINI_LIVE_MODEL)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_GEMINI_LIVE_MODEL

    fun setGeminiLiveModel(context: Context, modelId: String) {
        val clean = modelId.trim().ifEmpty { DEFAULT_GEMINI_LIVE_MODEL }
        prefs(context).edit { putString(KEY_GEMINI_LIVE_MODEL, clean) }
    }

    fun getGeminiLiveVoice(context: Context): String =
        prefs(context).getString(KEY_GEMINI_LIVE_VOICE, DEFAULT_GEMINI_LIVE_VOICE)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_GEMINI_LIVE_VOICE

    fun setGeminiLiveVoice(context: Context, voiceName: String) {
        val clean = voiceName.trim().ifEmpty { DEFAULT_GEMINI_LIVE_VOICE }
        prefs(context).edit { putString(KEY_GEMINI_LIVE_VOICE, clean) }
    }

    fun getGroqModel(context: Context): String {
        val custom = prefs(context).getString(KEY_GROQ_CUSTOM_MODEL, null)?.trim()
        if (!custom.isNullOrEmpty()) return custom
        return prefs(context).getString(KEY_GROQ_MODEL, DEFAULT_GROQ_MODEL)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_GROQ_MODEL
    }

    fun getSelectedPresetGroqModel(context: Context): String =
        prefs(context).getString(KEY_GROQ_MODEL, DEFAULT_GROQ_MODEL)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_GROQ_MODEL

    fun getCustomGroqModel(context: Context): String =
        prefs(context).getString(KEY_GROQ_CUSTOM_MODEL, "")?.trim().orEmpty()

    fun setGroqModel(context: Context, modelId: String) {
        val clean = modelId.trim().ifEmpty { DEFAULT_GROQ_MODEL }
        prefs(context).edit { putString(KEY_GROQ_MODEL, clean) }
    }

    fun setCustomGroqModel(context: Context, customModelId: String) {
        val clean = customModelId.trim()
        prefs(context).edit {
            if (clean.isEmpty()) remove(KEY_GROQ_CUSTOM_MODEL)
            else putString(KEY_GROQ_CUSTOM_MODEL, clean)
        }
    }

    fun isGroqSttEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GROQ_STT_ENABLED, false)

    fun setGroqSttEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_GROQ_STT_ENABLED, enabled) }
    }

    fun getGroqSttModel(context: Context): String =
        prefs(context).getString(KEY_GROQ_STT_MODEL, DEFAULT_GROQ_STT_MODEL)
            ?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_GROQ_STT_MODEL

    fun setGroqSttModel(context: Context, modelId: String) {
        val clean = modelId.trim().ifEmpty { DEFAULT_GROQ_STT_MODEL }
        prefs(context).edit { putString(KEY_GROQ_STT_MODEL, clean) }
    }

    fun getTextRoutingMode(context: Context): TextRoutingMode =
        TextRoutingMode.fromId(prefs(context).getString(KEY_TEXT_ROUTING_MODE, TextRoutingMode.AUTO_DUAL.id))

    fun setTextRoutingMode(context: Context, mode: TextRoutingMode) {
        prefs(context).edit { putString(KEY_TEXT_ROUTING_MODE, mode.id) }
    }

    fun clearAll(context: Context) {
        prefs(context).edit { clear() }
    }
}
