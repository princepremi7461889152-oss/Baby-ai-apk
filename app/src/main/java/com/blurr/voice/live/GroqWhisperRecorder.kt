package com.blurr.voice.live

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.blurr.voice.api.GroqApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Captures 16kHz PCM16 mono audio and encodes it to WAV for optional Groq Whisper STT.
 */
class GroqWhisperRecorder {

    companion object {
        private const val TAG = "GroqWhisperRecorder"
        private const val SAMPLE_RATE = 16000
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val bufferStream = ByteArrayOutputStream()

    @Volatile
    var isRecording: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    fun startRecording(scope: CoroutineScope): Boolean {
        if (isRecording) return true
        bufferStream.reset()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(3200)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to construct AudioRecord for Groq STT", e)
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        audioRecord = record
        isRecording = true

        recordingJob = scope.launch(Dispatchers.IO) {
            val chunk = ByteArray(3200)
            try {
                record.startRecording()
                while (isActive && isRecording) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read > 0) {
                        synchronized(bufferStream) {
                            bufferStream.write(chunk, 0, read)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error recording audio for Groq STT", e)
            }
        }
        return true
    }

    suspend fun stopAndTranscribe(
        apiKey: String,
        modelId: String,
        languageHint: String? = null,
        groqClient: GroqApiClient = GroqApiClient.shared
    ): Result<String> = withContext(Dispatchers.IO) {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        runCatching {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                release()
            }
        }
        audioRecord = null

        val pcmBytes = synchronized(bufferStream) {
            bufferStream.toByteArray().also { bufferStream.reset() }
        }
        if (pcmBytes.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("No speech captured."))
        }

        val wavBytes = GroqApiClient.pcm16ToWav(pcmBytes, SAMPLE_RATE, 1)
        groqClient.transcribeWav(
            apiKey = apiKey,
            wavBytes = wavBytes,
            modelId = modelId,
            languageHint = languageHint
        )
    }

    fun cancel() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        runCatching {
            audioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                release()
            }
        }
        audioRecord = null
        synchronized(bufferStream) { bufferStream.reset() }
    }
}
