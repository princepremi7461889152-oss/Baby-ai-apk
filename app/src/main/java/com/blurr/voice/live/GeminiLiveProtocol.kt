package com.blurr.voice.live

import android.util.Base64
import com.blurr.voice.utilities.AiEnginePreferences
import org.json.JSONArray
import org.json.JSONObject

sealed interface LiveServerEvent {
    data object SetupComplete : LiveServerEvent
    data class AudioChunk(val pcm24kBytes: ByteArray, val mimeType: String) : LiveServerEvent
    data class TextChunk(val text: String) : LiveServerEvent
    data class InputTranscript(val text: String) : LiveServerEvent
    data class OutputTranscript(val text: String) : LiveServerEvent
    data object Interrupted : LiveServerEvent
    data object TurnComplete : LiveServerEvent
    data class ServerError(val message: String, val code: Int? = null) : LiveServerEvent
}

enum class LiveSessionState {
    DISCONNECTED,
    CONNECTING,
    READY_LISTENING,
    SPEAKING,
    INTERRUPTED,
    MUTED,
    ERROR
}

/**
 * Pure protocol serializer/parser for Gemini Live API (BidiGenerateContent WebSocket).
 * Keeps one consistent prebuilt voice for the entire conversation session and supports
 * natural Hindi, Hinglish, and English real-time audio dialogue.
 */
object GeminiLiveProtocol {

    const val BIDI_ENDPOINT_URL =
        "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    const val INPUT_SAMPLE_RATE = 16000
    const val OUTPUT_SAMPLE_RATE = 24000

    fun buildDefaultSystemInstruction(
        userName: String? = null,
        memoriesSummary: String? = null,
        screenSummary: String? = null
    ): String {
        val namePart = userName?.takeIf { it.isNotBlank() }?.let { "The user's name is $it." } ?: ""
        val memoriesPart = memoriesSummary?.takeIf { it.isNotBlank() }?.let {
            "\nKnown user memories:\n$it"
        } ?: ""
        val screenPart = screenSummary?.takeIf { it.isNotBlank() }?.let {
            "\nCurrent phone screen context:\n$it"
        } ?: ""

        return """
            You are Baby, a friendly, warm, and responsive real-time voice assistant.
            You naturally understand and speak Hindi, Hinglish (Hindi + English mix), and English — seamlessly matching the user's language and tone in each conversation.
            Keep replies concise, natural, conversational, and easy to listen to over audio (avoid markdown bullets, asterisks, or robotic phrasing).
            If the user interrupts while you are speaking, stop immediately and respond to their new request.
            $namePart$memoriesPart$screenPart
        """.trimIndent()
    }

    fun buildSetupMessage(
        modelId: String = AiEnginePreferences.DEFAULT_GEMINI_LIVE_MODEL,
        voiceName: String = AiEnginePreferences.DEFAULT_GEMINI_LIVE_VOICE,
        systemInstruction: String = buildDefaultSystemInstruction()
    ): String {
        val cleanModel = modelId.trim().ifEmpty { AiEnginePreferences.DEFAULT_GEMINI_LIVE_MODEL }
        val normalizedModel = if (cleanModel.startsWith("models/")) cleanModel else "models/$cleanModel"
        val cleanVoice = voiceName.trim().ifEmpty { AiEnginePreferences.DEFAULT_GEMINI_LIVE_VOICE }

        val setupObj = JSONObject().apply {
            put("model", normalizedModel)
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", cleanVoice)
                        })
                    })
                })
            })
            put("inputAudioTranscription", JSONObject())
            put("outputAudioTranscription", JSONObject())
            if (systemInstruction.isNotBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", systemInstruction)))
                })
            }
        }

        return JSONObject().put("setup", setupObj).toString()
    }

    fun buildAudioChunkMessage(pcm16kMonoBytes: ByteArray): String {
        val encoded = Base64.encodeToString(pcm16kMonoBytes, Base64.NO_WRAP)
        val chunk = JSONObject().apply {
            put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE")
            put("data", encoded)
        }
        return JSONObject().put(
            "realtimeInput",
            JSONObject().put("mediaChunks", JSONArray().put(chunk))
        ).toString()
    }

    fun buildClientTextMessage(text: String, endOfTurn: Boolean = true): String {
        val turn = JSONObject().apply {
            put("role", "user")
            put("parts", JSONArray().put(JSONObject().put("text", text)))
        }
        return JSONObject().put(
            "clientContent",
            JSONObject().apply {
                put("turns", JSONArray().put(turn))
                put("turnComplete", endOfTurn)
            }
        ).toString()
    }

    fun parseServerMessage(rawJson: String): List<LiveServerEvent> {
        val events = mutableListOf<LiveServerEvent>()
        val root = try {
            JSONObject(rawJson)
        } catch (_: Exception) {
            return emptyList()
        }

        root.optJSONObject("error")?.let { err ->
            val msg = err.optString("message", "Gemini Live server error")
            val code = if (err.has("code")) err.optInt("code") else null
            events.add(LiveServerEvent.ServerError(msg, code))
            return events
        }

        if (root.has("setupComplete")) {
            events.add(LiveServerEvent.SetupComplete)
        }

        val serverContent = root.optJSONObject("serverContent") ?: return events

        if (serverContent.optBoolean("interrupted", false)) {
            events.add(LiveServerEvent.Interrupted)
        }

        serverContent.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            events.add(LiveServerEvent.InputTranscript(it))
        }

        serverContent.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotEmpty() }?.let {
            events.add(LiveServerEvent.OutputTranscript(it))
        }

        val modelTurn = serverContent.optJSONObject("modelTurn")
        val parts = modelTurn?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                part.optJSONObject("inlineData")?.let { inline ->
                    val mime = inline.optString("mimeType", "audio/pcm;rate=$OUTPUT_SAMPLE_RATE")
                    val b64 = inline.optString("data", "")
                    if (b64.isNotEmpty()) {
                        val decoded = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                        if (decoded != null && decoded.isNotEmpty()) {
                            events.add(LiveServerEvent.AudioChunk(decoded, mime))
                        }
                    }
                }
                part.optString("text", "").takeIf { it.isNotEmpty() }?.let { text ->
                    events.add(LiveServerEvent.TextChunk(text))
                }
            }
        }

        if (serverContent.optBoolean("turnComplete", false)) {
            events.add(LiveServerEvent.TurnComplete)
        }

        return events
    }
}
