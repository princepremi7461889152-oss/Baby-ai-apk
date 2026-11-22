package com.blurr.voice.live

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.blurr.voice.utilities.AiEnginePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.sqrt

/**
 * Real-time bidirectional audio engine powered by Gemini Live API.
 * - Captures 16kHz PCM16 mono microphone audio with AcousticEchoCanceler / NoiseSuppressor.
 * - Streams 24kHz PCM16 native Gemini Live audio output using one consistent voice per session.
 * - Supports instant barge-in interruption (both server-driven and local mic energy detection).
 * - Never converts Gemini Live responses into Groq TTS or re-synthesizes them via Android TTS.
 */
class GeminiLiveAudioEngine(
    private val context: Context,
    private val liveClient: GeminiLiveClient = GeminiLiveClient()
) {

    companion object {
        private const val TAG = "GeminiLiveAudioEngine"
        private const val CAPTURE_CHUNK_BYTES = 3200 // 100ms of 16kHz 16-bit mono PCM
        private const val BARGE_IN_RMS_THRESHOLD = 2200.0
        private const val BARGE_IN_CONSECUTIVE_FRAMES = 2
    }

    private var sessionScope: CoroutineScope? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    private var eventJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var audioTrack: AudioTrack? = null

    private val playbackQueue = LinkedBlockingQueue<ByteArray>(256)

    private val _sessionState = MutableStateFlow(LiveSessionState.DISCONNECTED)
    val sessionState: StateFlow<LiveSessionState> = _sessionState.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _lastInputTranscript = MutableStateFlow("")
    val lastInputTranscript: StateFlow<String> = _lastInputTranscript.asStateFlow()

    private val _lastOutputTranscript = MutableStateFlow("")
    val lastOutputTranscript: StateFlow<String> = _lastOutputTranscript.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    @Volatile
    private var isModelSpeaking = false

    @Volatile
    private var sessionVoiceLocked: String = AiEnginePreferences.DEFAULT_GEMINI_LIVE_VOICE

    fun getLockedSessionVoice(): String = sessionVoiceLocked

    suspend fun startSession(
        apiKey: String,
        systemInstruction: String,
        onError: ((String) -> Unit)? = null
    ): Boolean {
        stop()

        val appCtx = context.applicationContext
        val voice = AiEnginePreferences.getGeminiLiveVoice(appCtx)
        sessionVoiceLocked = voice
        val primaryModel = AiEnginePreferences.getGeminiLiveModel(appCtx)
        val candidateModels = listOf(
            primaryModel,
            AiEnginePreferences.DEFAULT_GEMINI_LIVE_MODEL,
            "gemini-2.5-flash-native-audio-latest"
        ).distinct()

        _sessionState.value = LiveSessionState.CONNECTING
        _errorMessage.value = null
        _lastInputTranscript.value = ""
        _lastOutputTranscript.value = ""
        _isMuted.value = false

        var connected = false
        for (modelId in candidateModels) {
            Log.d(TAG, "Connecting Gemini Live session with model=$modelId voice=$voice")
            if (liveClient.connect(apiKey, modelId, voice, systemInstruction)) {
                connected = true
                break
            }
        }

        if (!connected) {
            val err = liveClient.connectionError.value ?: "Unable to connect to Gemini Live API."
            _errorMessage.value = err
            _sessionState.value = LiveSessionState.ERROR
            onError?.invoke(err)
            return false
        }

        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        sessionScope = scope

        if (!initAudioHardware()) {
            val err = "Microphone or audio output hardware could not be initialized."
            _errorMessage.value = err
            _sessionState.value = LiveSessionState.ERROR
            stop()
            onError?.invoke(err)
            return false
        }

        _sessionState.value = LiveSessionState.READY_LISTENING
        startEventCollector(scope, onError)
        startAudioPlaybackLoop(scope)
        startAudioCaptureLoop(scope)
        return true
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        if (muted && _sessionState.value == LiveSessionState.READY_LISTENING) {
            _sessionState.value = LiveSessionState.MUTED
        } else if (!muted && _sessionState.value == LiveSessionState.MUTED) {
            _sessionState.value = LiveSessionState.READY_LISTENING
        }
    }

    fun sendTextMessage(text: String): Boolean {
        return liveClient.sendTextTurn(text)
    }

    fun interruptPlayback() {
        isModelSpeaking = false
        playbackQueue.clear()
        try {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                    track.flush()
                    track.play()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error flushing AudioTrack during interrupt: ${e.message}")
        }
        if (!_isMuted.value) {
            _sessionState.value = LiveSessionState.READY_LISTENING
        }
    }

    @SuppressLint("MissingPermission")
    private fun initAudioHardware(): Boolean {
        return try {
            val minRecBuf = AudioRecord.getMinBufferSize(
                GeminiLiveProtocol.INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(CAPTURE_CHUNK_BYTES * 2)

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                GeminiLiveProtocol.INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minRecBuf
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return false
            }

            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = runCatching {
                    AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
                }.getOrNull()
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = runCatching {
                    NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
                }.getOrNull()
            }
            audioRecord = record

            val minPlayBuf = AudioTrack.getMinBufferSize(
                GeminiLiveProtocol.OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4800)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(GeminiLiveProtocol.OUTPUT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minPlayBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                return false
            }
            track.play()
            audioTrack = track
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize live audio hardware", e)
            false
        }
    }

    private fun startEventCollector(scope: CoroutineScope, onError: ((String) -> Unit)?) {
        eventJob = scope.launch {
            liveClient.events.collect { event ->
                when (event) {
                    is LiveServerEvent.SetupComplete -> {
                        if (!_isMuted.value) {
                            _sessionState.value = LiveSessionState.READY_LISTENING
                        }
                    }
                    is LiveServerEvent.AudioChunk -> {
                        isModelSpeaking = true
                        _sessionState.value = LiveSessionState.SPEAKING
                        if (!playbackQueue.offer(event.pcm24kBytes)) {
                            playbackQueue.poll()
                            playbackQueue.offer(event.pcm24kBytes)
                        }
                    }
                    is LiveServerEvent.Interrupted -> {
                        Log.d(TAG, "Gemini Live server signaled barge-in interruption")
                        _sessionState.value = LiveSessionState.INTERRUPTED
                        interruptPlayback()
                    }
                    is LiveServerEvent.TurnComplete -> {
                        isModelSpeaking = false
                        _sessionState.value = if (_isMuted.value) {
                            LiveSessionState.MUTED
                        } else {
                            LiveSessionState.READY_LISTENING
                        }
                    }
                    is LiveServerEvent.InputTranscript -> {
                        _lastInputTranscript.value = event.text
                    }
                    is LiveServerEvent.OutputTranscript -> {
                        _lastOutputTranscript.value = event.text
                    }
                    is LiveServerEvent.TextChunk -> {
                        if (_lastOutputTranscript.value.isBlank()) {
                            _lastOutputTranscript.value = event.text
                        }
                    }
                    is LiveServerEvent.ServerError -> {
                        _errorMessage.value = event.message
                        _sessionState.value = LiveSessionState.ERROR
                        onError?.invoke(event.message)
                    }
                }
            }
        }
    }

    private fun startAudioPlaybackLoop(scope: CoroutineScope) {
        playbackJob = scope.launch {
            while (isActive) {
                try {
                    val chunk = playbackQueue.take()
                    val track = audioTrack ?: continue
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }
                    track.write(chunk, 0, chunk.size)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "AudioTrack write warning: ${e.message}")
                }
            }
        }
    }

    private fun startAudioCaptureLoop(scope: CoroutineScope) {
        captureJob = scope.launch {
            val record = audioRecord ?: return@launch
            val buffer = ByteArray(CAPTURE_CHUNK_BYTES)
            var highEnergyFrames = 0

            try {
                record.startRecording()
                while (isActive) {
                    val readBytes = record.read(buffer, 0, buffer.size)
                    if (readBytes <= 0) continue
                    if (_isMuted.value) {
                        highEnergyFrames = 0
                        continue
                    }

                    val frameBytes = buffer.copyOf(readBytes)

                    // Local barge-in safeguard: if user speaks clearly while Baby is speaking,
                    // flush queued output immediately while streaming user audio to Gemini Live.
                    if (isModelSpeaking) {
                        val rms = computePcm16Rms(frameBytes)
                        if (rms >= BARGE_IN_RMS_THRESHOLD) {
                            highEnergyFrames++
                            if (highEnergyFrames >= BARGE_IN_CONSECUTIVE_FRAMES) {
                                Log.d(TAG, "Local voice barge-in detected (rms=${rms.toInt()})")
                                interruptPlayback()
                                highEnergyFrames = 0
                            }
                        } else {
                            highEnergyFrames = 0
                        }
                    } else {
                        highEnergyFrames = 0
                    }

                    liveClient.sendPcmAudio(frameBytes)
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Audio capture error", e)
                    _errorMessage.value = "Microphone capture interrupted: ${e.message}"
                }
            }
        }
    }

    private fun computePcm16Rms(pcm: ByteArray): Double {
        if (pcm.size < 2) return 0.0
        var sumSquares = 0.0
        val sampleCount = pcm.size / 2
        var i = 0
        while (i + 1 < pcm.size) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
            sumSquares += sample * sample
            i += 2
        }
        return if (sampleCount > 0) sqrt(sumSquares / sampleCount) else 0.0
    }

    fun stop() {
        isModelSpeaking = false
        captureJob?.cancel()
        playbackJob?.cancel()
        eventJob?.cancel()
        captureJob = null
        playbackJob = null
        eventJob = null

        sessionScope?.cancel()
        sessionScope = null

        playbackQueue.clear()
        liveClient.close()

        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null

        runCatching {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                release()
            }
        }
        audioRecord = null

        runCatching {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) stop()
                flush()
                release()
            }
        }
        audioTrack = null

        _sessionState.value = LiveSessionState.DISCONNECTED
    }
}
