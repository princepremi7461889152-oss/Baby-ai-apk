package com.blurr.voice

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blurr.voice.api.GeminiApi
import com.blurr.voice.api.TextPart
import com.blurr.voice.ecc.EccWorkflowEngine
import com.blurr.voice.openhands.OpenHandsAgentRunner
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var editText: EditText
    private lateinit var sendButton: Button
    private val messages = mutableListOf<Message>()
    private val conversationHistory = mutableListOf<Pair<String, List<Any>>>()
    private lateinit var chatAdapter: ChatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        recyclerView = findViewById(R.id.recyclerView)
        editText = findViewById(R.id.editText)
        sendButton = findViewById(R.id.sendButton)

        val customMessage = intent.getStringExtra("custom_message") ?: "Hello! How can I help you?"

        recyclerView.layoutManager = LinearLayoutManager(this)
        chatAdapter = ChatAdapter(messages)
        recyclerView.adapter = chatAdapter

        messages.add(Message(customMessage, isUserMessage = false))
        chatAdapter.notifyItemInserted(messages.size - 1)

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
                    GeminiApi.generateContent(
                        chat = conversationHistory.toList(),
                        context = applicationContext
                    ) ?: "Unable to get a response right now. Please check your network and Gemini API key in Settings."
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
}
