package com.blurr.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.blurr.voice.api.DualAiRouter
import com.blurr.voice.api.GroqApiClient
import com.blurr.voice.api.GroqChatMessage
import com.blurr.voice.api.TextPart
import com.blurr.voice.live.GeminiLiveProtocol
import com.blurr.voice.live.LiveServerEvent
import com.blurr.voice.utilities.AiEnginePreferences
import com.blurr.voice.utilities.AiEnginePreferences.TextRoutingMode
import com.blurr.voice.utilities.GeminiKeyManager
import com.blurr.voice.utilities.GroqKeyManager
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DualAiEngineRobolectricTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        GeminiKeyManager(context).clearApiKey()
        GroqKeyManager(context).clearApiKey()
        AiEnginePreferences.clearAll(context)
    }

    @Test
    fun groqKeyManager_andPreferences_remainSeparateFromGemini() {
        val geminiKeys = GeminiKeyManager(context)
        val groqKeys = GroqKeyManager(context)

        geminiKeys.saveApiKey("AIzaSyGeminiExample1234")
        groqKeys.saveApiKey("  gsk_example_groq_secret_5678  ")

        assertTrue(geminiKeys.hasApiKey())
        assertTrue(groqKeys.hasApiKey())
        assertEquals("AIzaSyGeminiExample1234", geminiKeys.getApiKey())
        assertEquals("gsk_example_groq_secret_5678", groqKeys.getApiKey())
        assertEquals("••••••5678", groqKeys.getMaskedKey())

        // Clearing Groq does not remove Gemini
        groqKeys.clearApiKey()
        assertFalse(groqKeys.hasApiKey())
        assertNull(groqKeys.getApiKey())
        assertTrue(geminiKeys.hasApiKey())

        // Preferences defaults and updates
        assertTrue(AiEnginePreferences.isGeminiLiveVoiceEnabled(context))
        assertEquals(AiEnginePreferences.DEFAULT_GEMINI_LIVE_VOICE, AiEnginePreferences.getGeminiLiveVoice(context))
        assertEquals(AiEnginePreferences.DEFAULT_GROQ_MODEL, AiEnginePreferences.getGroqModel(context))

        AiEnginePreferences.setGeminiLiveVoice(context, "Kore")
        assertEquals("Kore", AiEnginePreferences.getGeminiLiveVoice(context))

        AiEnginePreferences.setGroqModel(context, "llama-3.1-8b-instant")
        assertEquals("llama-3.1-8b-instant", AiEnginePreferences.getGroqModel(context))

        AiEnginePreferences.setCustomGroqModel(context, "openai/gpt-oss-120b")
        assertEquals("openai/gpt-oss-120b", AiEnginePreferences.getGroqModel(context))
    }

    @Test
    fun geminiLiveProtocol_buildsConsistentVoiceSetup_andParsesAudioAndInterruption() {
        val systemPrompt = GeminiLiveProtocol.buildDefaultSystemInstruction(
            userName = "Aarav",
            memoriesSummary = "- Prefers Hinglish replies"
        )
        assertTrue(systemPrompt.contains("Hindi"))
        assertTrue(systemPrompt.contains("Hinglish"))
        assertTrue(systemPrompt.contains("English"))
        assertTrue(systemPrompt.contains("Aarav"))

        val setupJson = JSONObject(
            GeminiLiveProtocol.buildSetupMessage(
                modelId = "gemini-2.5-flash-native-audio-preview-12-2025",
                voiceName = "Aoede",
                systemInstruction = systemPrompt
            )
        ).getJSONObject("setup")

        assertEquals("models/gemini-2.5-flash-native-audio-preview-12-2025", setupJson.getString("model"))
        val genConfig = setupJson.getJSONObject("generationConfig")
        assertEquals("AUDIO", genConfig.getJSONArray("responseModalities").getString(0))
        val voiceName = genConfig
            .getJSONObject("speechConfig")
            .getJSONObject("voiceConfig")
            .getJSONObject("prebuiltVoiceConfig")
            .getString("voiceName")
        assertEquals("Aoede", voiceName)

        // Audio chunk serialization (16kHz PCM)
        val inputPcm = byteArrayOf(1, 2, 3, 4)
        val audioFrame = JSONObject(GeminiLiveProtocol.buildAudioChunkMessage(inputPcm))
        val chunk = audioFrame.getJSONObject("realtimeInput").getJSONArray("mediaChunks").getJSONObject(0)
        assertEquals("audio/pcm;rate=16000", chunk.getString("mimeType"))
        assertEquals(Base64.encodeToString(inputPcm, Base64.NO_WRAP), chunk.getString("data"))

        // Server setupComplete + audio + interruption parsing
        assertEquals(listOf(LiveServerEvent.SetupComplete), GeminiLiveProtocol.parseServerMessage("""{"setupComplete":{}}"""))

        val outputPcmB64 = Base64.encodeToString(byteArrayOf(10, 20, 30, 40), Base64.NO_WRAP)
        val serverAudioMsg = """
            {
              "serverContent": {
                "inputTranscription": {"text": "Namaste Baby"},
                "outputTranscription": {"text": "Namaste! Kaise madad karun?"},
                "modelTurn": {
                  "parts": [
                    {"inlineData": {"mimeType": "audio/pcm;rate=24000", "data": "$outputPcmB64"}}
                  ]
                },
                "interrupted": true,
                "turnComplete": true
              }
            }
        """.trimIndent()

        val events = GeminiLiveProtocol.parseServerMessage(serverAudioMsg)
        assertTrue(events.any { it is LiveServerEvent.Interrupted })
        assertTrue(events.any { it is LiveServerEvent.TurnComplete })
        assertTrue(events.any { it is LiveServerEvent.InputTranscript && it.text == "Namaste Baby" })
        assertTrue(events.any { it is LiveServerEvent.OutputTranscript && it.text == "Namaste! Kaise madad karun?" })
        val audioEvt = events.filterIsInstance<LiveServerEvent.AudioChunk>().first()
        assertEquals(4, audioEvt.pcm24kBytes.size)
        assertEquals("audio/pcm;rate=24000", audioEvt.mimeType)
    }

    @Test
    fun groqApiClient_handlesChatCompletion_whisperWav_andClearErrors() = runBlocking {
        var capturedAuthHeader: String? = null
        var nextCode = 200
        var nextBody = """{"choices":[{"message":{"content":"Fast Groq reply"}}]}"""

        val fakeClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                capturedAuthHeader = req.header("Authorization")
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(nextCode)
                    .message("Mock")
                    .body(nextBody.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val client = GroqApiClient(baseUrl = "https://mock.groq.local/openai/v1", httpClient = fakeClient)

        // Successful chat completion + testConnection
        val testOk = client.testConnection("gsk_test_1234", "llama-3.3-70b-versatile")
        assertTrue(testOk.success)
        assertEquals("Bearer gsk_test_1234", capturedAuthHeader)
        assertTrue(testOk.message.contains("Connected to Groq"))

        // Clear 401 auth error message
        nextCode = 401
        nextBody = """{"error":{"message":"Invalid API Key"}}"""
        val testFail = client.testConnection("gsk_bad_key", "llama-3.3-70b-versatile")
        assertFalse(testFail.success)
        assertTrue(testFail.message.contains("Invalid Groq API key"))
        assertTrue(testFail.message.contains("401"))

        // WAV generation + Whisper transcription
        nextCode = 200
        nextBody = """{"text":"Aaj ka mausam kaisa hai"}"""
        val wav = GroqApiClient.pcm16ToWav(ByteArray(320), sampleRate = 16000, channels = 1)
        assertEquals('R', wav[0].toInt().toChar())
        assertEquals('I', wav[1].toInt().toChar())
        assertEquals('F', wav[2].toInt().toChar())
        assertEquals('F', wav[3].toInt().toChar())
        assertEquals('W', wav[8].toInt().toChar())

        val sttResult = client.transcribeWav("gsk_test_1234", wav, "whisper-large-v3-turbo")
        assertTrue(sttResult.isSuccess)
        assertEquals("Aaj ka mausam kaisa hai", sttResult.getOrNull())
    }

    @Test
    fun dualAiRouter_routesTextToGroqWhenConfiguredFast_withoutTouchingGeminiVoice() = runBlocking {
        GroqKeyManager(context).saveApiKey("gsk_router_key_9999")
        AiEnginePreferences.setGroqModel(context, "llama-3.1-8b-instant")
        AiEnginePreferences.setTextRoutingMode(context, TextRoutingMode.GROQ_FAST)

        val fakeClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"choices":[{"message":{"content":"Groq fast reasoning answer"}}]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()

        val routerResp = DualAiRouter.generateText(
            context = context,
            chat = listOf("user" to listOf(TextPart("Summarize clean code principles"))),
            groqClient = GroqApiClient("https://mock.groq.local/openai/v1", fakeClient)
        )

        assertEquals("Groq", routerResp.provider)
        assertEquals("llama-3.1-8b-instant", routerResp.modelId)
        assertEquals("Groq fast reasoning answer", routerResp.text)
    }

    @Test
    fun settingsActivity_managesGroqCredentialsAndDualAiControls_andTestButtonsValidateEmptyKeys() {
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val activity = controller.get()

        val editGroq = activity.findViewById<EditText>(R.id.editGroqApiKey)
        val btnSaveGroq = activity.findViewById<TextView>(R.id.btnSaveGroqKey)
        val btnClearGroq = activity.findViewById<Button>(R.id.btnClearGroqKey)
        val textGroqStatus = activity.findViewById<TextView>(R.id.textGroqKeyStatus)
        val btnTestGemini = activity.findViewById<Button>(R.id.btnTestGeminiConnection)
        val textGeminiTest = activity.findViewById<TextView>(R.id.textGeminiTestResult)
        val btnTestGroq = activity.findViewById<Button>(R.id.btnTestGroqConnection)
        val textGroqTest = activity.findViewById<TextView>(R.id.textGroqTestResult)
        val switchLive = activity.findViewById<SwitchMaterial>(R.id.switchGeminiLiveVoice)
        val spinnerLiveVoice = activity.findViewById<Spinner>(R.id.spinnerGeminiLiveVoice)
        val editCustomGroq = activity.findViewById<EditText>(R.id.editCustomGroqModel)
        val btnApplyCustomGroq = activity.findViewById<Button>(R.id.btnApplyCustomGroqModel)

        assertTrue(switchLive.isChecked)
        assertEquals(AiEnginePreferences.SUPPORTED_GEMINI_LIVE_VOICES.size, spinnerLiveVoice.count)
        assertTrue(textGroqStatus.text.toString().contains("Not configured"))

        // Save Groq key separately
        editGroq.setText("gsk_settings_test_4321")
        btnSaveGroq.performClick()
        assertEquals("gsk_settings_test_4321", GroqKeyManager(context).getApiKey())
        assertTrue(textGroqStatus.text.toString().contains("Configured"))
        assertTrue(textGroqStatus.text.toString().contains("4321"))

        // Apply custom Groq model
        editCustomGroq.setText("openai/gpt-oss-20b")
        btnApplyCustomGroq.performClick()
        assertEquals("openai/gpt-oss-20b", AiEnginePreferences.getGroqModel(context))
        assertTrue(textGroqStatus.text.toString().contains("openai/gpt-oss-20b"))

        // Test connection with missing Gemini key gives clear actionable error
        btnTestGemini.performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(100)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(textGeminiTest.text.toString().contains("Gemini API key", ignoreCase = true))

        // Clear Groq key and verify empty-key test message
        btnClearGroq.performClick()
        assertFalse(GroqKeyManager(context).hasApiKey())
        assertTrue(textGroqStatus.text.toString().contains("Not configured"))

        btnTestGroq.performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        Thread.sleep(100)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(textGroqTest.text.toString().contains("Groq API key", ignoreCase = true))
    }

    @Test
    fun manifest_declaresMicrophoneForegroundServiceAndWakeLockPermissions() {
        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES
        )
        val perms = pkgInfo.requestedPermissions?.toList().orEmpty()
        assertTrue(perms.contains(Manifest.permission.RECORD_AUDIO))
        assertTrue(perms.contains(Manifest.permission.FOREGROUND_SERVICE))
        assertTrue(perms.contains(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE))
        assertTrue(perms.contains(Manifest.permission.WAKE_LOCK))

        val convService = pkgInfo.services?.firstOrNull {
            it.name == ConversationalAgentService::class.java.name
        }
        assertNotNull(convService)
        assertTrue(
            (convService!!.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0
        )
    }
}
