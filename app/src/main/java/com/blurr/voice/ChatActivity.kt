package com.blurr.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blurr.voice.api.DualAiRouter
import com.blurr.voice.api.TextPart
import com.blurr.voice.ecc.EccWorkflowEngine
import com.blurr.voice.live.GroqWhisperRecorder
import com.blurr.voice.openhands.OpenHandsAgentRunner
import com.blurr.voice.utilities.AiEnginePreferences
import com.blurr.voice.utilities.AiEnginePreferences.TextRoutingMode
import com.blurr.voice.utilities.GroqKeyManager
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var editText: EditText
    private lateinit var sendButton: Button
    private lateinit var tvChatEngineBadge: TextView
    private lateinit var btnSwitchChatEngine: Button
    private lateinit var btnChatVoiceInput: ImageButton

    private val messages = mutableListOf<Message>()
    private val conversationHistory = mutableListOf<Pair<String, List<Any>>>()
    private lateinit var chatAdapter: ChatAdapter
    private val whisperRecorder = GroqWhisperRecorder()

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                toggleGroqWhisperRecording()
            } else {
                Toast.makeText(this, "Microphone permission is needed for voice input.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        recyclerView = findViewById(R.id.recyclerView)
        editText = findViewById(R.id.editText)
        sendButton = findViewById(R.id.sendButton)
        tvChatEngineBadge = findViewById(R.id.tvChatEngineBadge)
        btnSwitchChatEngine = findViewById(R.id.btnSwitchChatEngine)
        btnChatVoiceInput = findViewById(R.id.btnChatVoiceInput)

        val customMessage = intent.getStringExtra("custom_message") ?: "Hello! How can I help you?"

        recyclerView.layoutManager = LinearLayoutManager(this)
        chatAdapter = ChatAdapter(messages)
        recyclerView.adapter = chatAdapter

        messages.add(Message(customMessage, isUserMessage = false))
        chatAdapter.notifyItemInserted(messages.size - 1)

        updateEngineBadge()

        btnSwitchChatEngine.setOnClickListener {
            val modes = TextRoutingMode.entries
            val current = AiEnginePreferences.getTextRoutingMode(this)
            val next = modes[(modes.indexOf(current) + 1) % modes.size]
            AiEnginePreferences.setTextRoutingMode(this, next)
            updateEngineBadge()
        }

        btnChatVoiceInput.setOnClickListener {
            if (!GroqKeyManager(this).hasApiKey()) {
                Toast.makeText(this, "Add a Groq API key in Settings to use Groq Whisper STT.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return@setOnClickListener
            }
            toggleGroqWhisperRecording()
        }

        sendButton.setOnClickListener {
            val messageContent = editText.text.toString().trim()
            if (messageContent.isEmpty()) return@setOnClickListener

            messages.add(Message(messageContent, isUserMessage = true))
            chatAdapter.notifyItemInserted(messages.size - 1)
            recyclerView.scrollToPosition(messages.size - 1)
            editText.text.clear()

            sendButton.isEnabled = false

            val loadingIndex = messages.size
            messages.add(Message("Thinking...", isUserMessage = false))
            chatAdapter.notifyItemInserted(loadingIndex)
            recyclerView.scrollToPosition(loadingIndex)

            lifecycleScope.launch {
                val reply = try {
                    val enriched = EccWorkflowEngine.enrichSlashCommandIfPresent(applicationContext, messageContent)
                        ?: OpenHandsAgentRunner.enrichChatCommandIfPresent(applicationContext, messageContent)
                    val promptText = enriched ?: messageContent
                    conversationHistory.add("user" to listOf(TextPart(promptText)))
                    val result = DualAiRouter.generateText(
                        context = applicationContext,
                        chat = conversationHistory.toList()
                    )
                    result.text
                } catch (e: Exception) {
                    "Error: ${e.message ?: "Failed to generate response."}"
                }

                if (isFinishing || isDestroyed) return@launch
                messages[loadingIndex] = Message(reply, isUserMessage = false)
                chatAdapter.notifyItemChanged(loadingIndex)
                recyclerView.scrollToPosition(loadingIndex)
                conversationHistory.add("model" to listOf(TextPart(reply)))
                sendButton.isEnabled = true
            }
        }
    }

    private fun toggleGroqWhisperRecording() {
        if (!whisperRecorder.isRecording) {
            val started = whisperRecorder.startRecording(lifecycleScope)
            if (started) {
                btnChatVoiceInput.alpha = 0.55f
                Toast.makeText(this, "Listening (Groq Whisper)... tap mic again to transcribe", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Could not access microphone.", Toast.LENGTH_SHORT).show()
            }
        } else {
            btnChatVoiceInput.alpha = 1.0f
            btnChatVoiceInput.isEnabled = false
            val groqKey = GroqKeyManager(this).getApiKey().orEmpty()
            val sttModel = AiEnginePreferences.getGroqSttModel(this)
            lifecycleScope.launch {
                val transcription = whisperRecorder.stopAndTranscribe(
                    apiKey = groqKey,
                    modelId = sttModel
                )
                btnChatVoiceInput.isEnabled = true
                transcription.onSuccess { text ->
                    if (text.isNotBlank()) {
                        editText.setText(text)
                        editText.setSelection(text.length)
                    } else {
                        Toast.makeText(this@ChatActivity, "No speech detected.", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure { err ->
                    Toast.makeText(this@ChatActivity, err.message ?: "Transcription failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun updateEngineBadge() {
        val mode = AiEnginePreferences.getTextRoutingMode(this)
        val modelHint = when (mode) {
            TextRoutingMode.GROQ_FAST -> AiEnginePreferences.getGroqModel(this)
            TextRoutingMode.GEMINI_PRIMARY -> "gemini-3.8-flash"
            TextRoutingMode.AUTO_DUAL -> "Gemini + ${AiEnginePreferences.getGroqModel(this)}"
        }
        tvChatEngineBadge.text = "Engine: ${mode.displayLabel} ($modelHint)"
    }

    override fun onDestroy() {
        super.onDestroy()
        whisperRecorder.cancel()
    }
}
