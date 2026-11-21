package com.blurr.voice.data

import android.content.Context
import android.util.Log
import com.blurr.voice.api.GeminiApi
import com.blurr.voice.api.TextPart
import com.blurr.voice.utilities.FirebaseProviders
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Extracts and stores memories from conversations using Firebase AI Logic (`GeminiApi`)
 * and saves them directly to the signed-in user's Firestore document.
 */
object MemoryExtractor {

    private const val TAG = "MemoryExtractor"

    private val memoryExtractionPrompt = """
        You are a memory extraction agent. Analyze the following conversation and extract key, lasting facts about the user which are helpful for an assistant to remember.

        Focus on:
        - Personal details (family, relationships, preferences, life events)
        - Significant experiences, hobbies, or important dates/locations
        - Long-term preferences, goals, or habits
        Ignore:
        - Fleeting emotions or temporary states
        - Generic statements or hypothetical scenarios
        - Technical details or app-specific instructions

        IMPORTANT: Do NOT extract memories that are semantically equivalent to the following already known memories:
        {used_memories}

        Format each memory as a single short, clear sentence (without bullet prefixes).
        If no significant new memories are found, return ONLY "NO_MEMORIES".

        Conversation:
        {conversation}

        Extracted Memories (one per line):
    """.trimIndent()

    suspend fun extractAndSaveMemoriesToFirestore(
        context: Context,
        conversationHistory: List<Pair<String, List<Any>>>,
        existingMemories: List<String> = emptyList()
    ) {
        val currentUser = try {
            Firebase.auth.currentUser
        } catch (_: Exception) {
            null
        } ?: return

        // Skip index 0 if it's the large system prompt; keep actual dialogue turns
        val dialogueTurns = if (conversationHistory.size > 1) {
            conversationHistory.drop(1)
        } else {
            emptyList()
        }
        if (dialogueTurns.isEmpty()) return

        withContext(Dispatchers.IO) {
            try {
                val conversationText = formatConversationForExtraction(dialogueTurns)
                if (conversationText.isBlank()) return@withContext

                val usedMemoriesText = if (existingMemories.isNotEmpty()) {
                    existingMemories.take(50).joinToString("\n") { "- $it" }
                } else {
                    "None"
                }

                val extractionPrompt = memoryExtractionPrompt
                    .replace("{conversation}", conversationText)
                    .replace("{used_memories}", usedMemoriesText)

                val response = GeminiApi.generateContent(
                    chat = listOf("user" to listOf(TextPart(extractionPrompt))),
                    context = context
                ) ?: return@withContext

                val memories = parseExtractedMemories(response)
                if (memories.isEmpty()) return@withContext

                val docRef = FirebaseProviders.firestore(context)
                    .collection("users")
                    .document(currentUser.uid)

                memories.take(5).forEach { memoryText ->
                    val memoryMap = hashMapOf(
                        "id" to UUID.randomUUID().toString(),
                        "text" to memoryText.take(500),
                        "source" to "Assistant",
                        "createdAt" to Timestamp.now()
                    )
                    docRef.update("memories", FieldValue.arrayUnion(memoryMap)).await()
                }
                Log.d(TAG, "Saved ${memories.size} extracted memories to Firestore.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FirebaseProviders.handleFirestoreError(
                    e,
                    FirebaseProviders.OperationType.UPDATE,
                    "users/${currentUser.uid}"
                )
                Log.e(TAG, "Error during memory extraction", e)
            }
        }
    }

    private fun formatConversationForExtraction(conversationHistory: List<Pair<String, List<Any>>>): String {
        return conversationHistory.joinToString("\n") { (role, parts) ->
            val textParts = parts.filterIsInstance<TextPart>()
            val text = textParts.joinToString(" ") { it.text }
            "$role: $text"
        }
    }

    private fun parseExtractedMemories(response: String): List<String> {
        return try {
            response.lines()
                .map { it.trim().removePrefix("- ").removePrefix("* ").trim() }
                .filter { it.isNotBlank() }
                .filter { !it.equals("NO_MEMORIES", ignoreCase = true) }
                .filter { !it.startsWith("Extracted Memories", ignoreCase = true) }
                .filter { !it.startsWith("Memories:", ignoreCase = true) }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing extracted memories", e)
            emptyList()
        }
    }
}