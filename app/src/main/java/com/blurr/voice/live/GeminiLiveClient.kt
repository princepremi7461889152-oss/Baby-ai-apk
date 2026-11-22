package com.blurr.voice.live

import android.util.Log
import com.blurr.voice.utilities.AiEnginePreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

/**
 * WebSocket transport for Gemini Live BidiGenerateContent.
 * Authenticates via x-goog-api-key header so API credentials never appear in URLs or logs.
 */
class GeminiLiveClient(
    private val endpointUrl: String = GeminiLiveProtocol.BIDI_ENDPOINT_URL,
    private val httpClient: OkHttpClient = defaultWsClient
) {

    companion object {
        private const val TAG = "GeminiLiveClient"

        private val defaultWsClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // Persistent streaming WebSocket
                .writeTimeout(15, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _events = MutableSharedFlow<LiveServerEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<LiveServerEvent> = _events.asSharedFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var setupCompleteDeferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var activeVoiceName: String = AiEnginePreferences.DEFAULT_GEMINI_LIVE_VOICE

    fun getActiveVoiceName(): String = activeVoiceName

    suspend fun connect(
        apiKey: String,
        modelId: String,
        voiceName: String,
        systemInstruction: String,
        timeoutMs: Long = 12_000L
    ): Boolean {
        close()
        val cleanKey = apiKey.trim()
        if (cleanKey.isEmpty()) {
            _connectionError.value = "Gemini API key is not configured."
            return false
        }

        // Lock one consistent voice for the lifetime of this session.
        activeVoiceName = voiceName.trim().ifEmpty { AiEnginePreferences.DEFAULT_GEMINI_LIVE_VOICE }
        _connectionError.value = null

        val setupDeferred = CompletableDeferred<Boolean>()
        setupCompleteDeferred = setupDeferred

        val request = Request.Builder()
            .url(endpointUrl)
            .addHeader("x-goog-api-key", cleanKey)
            .build()

        val setupJson = GeminiLiveProtocol.buildSetupMessage(
            modelId = modelId,
            voiceName = activeVoiceName,
            systemInstruction = systemInstruction
        )

        webSocket = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Gemini Live WebSocket opened; sending setup with voice=$activeVoiceName")
                webSocket.send(setupJson)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingPayload(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleIncomingPayload(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Gemini Live WebSocket closing: code=$code reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Gemini Live WebSocket closed: code=$code")
                if (!setupDeferred.isCompleted) {
                    if (reason.isNotBlank()) {
                        _connectionError.value = reason
                    }
                    setupDeferred.complete(false)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                val msg = when (code) {
                    401, 403 -> "Unauthorized Gemini API key (HTTP $code)."
                    404 -> "Selected Gemini Live model is unavailable (HTTP 404)."
                    else -> t.message ?: "Gemini Live connection error"
                }
                Log.e(TAG, "Gemini Live WebSocket failure (HTTP $code): $msg")
                _connectionError.value = msg
                scope.launch { _events.emit(LiveServerEvent.ServerError(msg, code)) }
                if (!setupDeferred.isCompleted) {
                    setupDeferred.complete(false)
                }
            }
        })

        val ready = withTimeoutOrNull(timeoutMs) { setupDeferred.await() } ?: false
        if (!ready && _connectionError.value == null) {
            _connectionError.value = "Timed out waiting for Gemini Live setup."
        }
        return ready
    }

    private fun handleIncomingPayload(rawJson: String) {
        val parsed = GeminiLiveProtocol.parseServerMessage(rawJson)
        if (parsed.isEmpty()) return

        for (event in parsed) {
            if (event is LiveServerEvent.SetupComplete) {
                setupCompleteDeferred?.complete(true)
            } else if (event is LiveServerEvent.ServerError) {
                _connectionError.value = event.message
                setupCompleteDeferred?.complete(false)
            }
            _events.tryEmit(event)
        }
    }

    fun sendPcmAudio(pcm16kMonoBytes: ByteArray): Boolean {
        val ws = webSocket ?: return false
        if (pcm16kMonoBytes.isEmpty()) return true
        val json = GeminiLiveProtocol.buildAudioChunkMessage(pcm16kMonoBytes)
        return ws.send(json)
    }

    fun sendTextTurn(text: String): Boolean {
        val ws = webSocket ?: return false
        if (text.isBlank()) return false
        return ws.send(GeminiLiveProtocol.buildClientTextMessage(text))
    }

    fun close() {
        setupCompleteDeferred?.let {
            if (!it.isCompleted) it.complete(false)
        }
        setupCompleteDeferred = null
        try {
            webSocket?.close(1000, "Session ended")
        } catch (_: Exception) {
        }
        webSocket = null
    }
}
