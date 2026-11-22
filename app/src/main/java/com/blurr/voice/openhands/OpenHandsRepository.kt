package com.blurr.voice.openhands

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Repository managing OpenHands Microagents (bundled + synced from OpenHands/OpenHands),
 * Event-Stream Conversations, and Remote Agent Server configuration.
 */
object OpenHandsRepository {

    private const val PREFS_NAME = "OpenHandsPrefs"
    private const val ASSET_MICROAGENTS = "openhands_microagents.json"
    private const val KEY_CUSTOM_MICROAGENTS = "oh_custom_microagents_json"
    private const val KEY_DISABLED_IDS = "oh_disabled_microagent_ids"
    private const val KEY_SYNCED_MICROAGENTS = "oh_synced_microagents_json"
    private const val KEY_CONVERSATIONS = "oh_conversations_json"
    private const val KEY_SERVER_CONFIG = "oh_server_config_json"
    private const val MAX_CONVERSATIONS = 20

    private const val GITHUB_TREE_URL =
        "https://api.github.com/repos/OpenHands/OpenHands/git/trees/main?recursive=1"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadBundledMicroagents(context: Context): List<OpenHandsMicroagent> {
        return try {
            val raw = context.assets.open(ASSET_MICROAGENTS).bufferedReader().use { it.readText() }
            json.decodeFromString<List<OpenHandsMicroagent>>(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun getMicroagents(context: Context): List<OpenHandsMicroagent> {
        val base = loadBundledMicroagents(context)
        val syncedRaw = prefs(context).getString(KEY_SYNCED_MICROAGENTS, null)
        val synced = if (!syncedRaw.isNullOrBlank()) {
            try {
                json.decodeFromString<List<OpenHandsMicroagent>>(syncedRaw)
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val customRaw = prefs(context).getString(KEY_CUSTOM_MICROAGENTS, null)
        val custom = if (!customRaw.isNullOrBlank()) {
            try {
                json.decodeFromString<List<OpenHandsMicroagent>>(customRaw)
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val disabledIds = prefs(context).getStringSet(KEY_DISABLED_IDS, emptySet()) ?: emptySet()
        val mergedById = linkedMapOf<String, OpenHandsMicroagent>()
        (base + synced + custom).forEach { agent ->
            mergedById[agent.id] = agent.copy(enabled = !disabledIds.contains(agent.id))
        }
        return mergedById.values.toList()
    }

    @Synchronized
    fun toggleMicroagent(context: Context, id: String): List<OpenHandsMicroagent> {
        val disabled = (prefs(context).getStringSet(KEY_DISABLED_IDS, emptySet()) ?: emptySet()).toMutableSet()
        if (disabled.contains(id)) disabled.remove(id) else disabled.add(id)
        prefs(context).edit { putStringSet(KEY_DISABLED_IDS, disabled) }
        return getMicroagents(context)
    }

    @Synchronized
    fun addCustomMicroagent(
        context: Context,
        name: String,
        keywordsCsv: String,
        instructions: String
    ): OpenHandsMicroagent {
        val keywords = keywordsCsv.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
        val newAgent = OpenHandsMicroagent(
            id = "custom-micro-${UUID.randomUUID().toString().take(8)}",
            name = name.trim().ifEmpty { "Custom Microagent" },
            triggerKeywords = keywords,
            description = instructions.trim().take(90),
            instructions = instructions.trim(),
            sourcePath = ".openhands/microagents/custom.md",
            enabled = true,
            isCustom = true
        )
        val existingRaw = prefs(context).getString(KEY_CUSTOM_MICROAGENTS, null)
        val existing = if (!existingRaw.isNullOrBlank()) {
            try {
                json.decodeFromString<List<OpenHandsMicroagent>>(existingRaw)
            } catch (_: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
        prefs(context).edit {
            putString(KEY_CUSTOM_MICROAGENTS, json.encodeToString(listOf(newAgent) + existing))
        }
        return newAgent
    }

    @Synchronized
    fun deleteCustomMicroagent(context: Context, id: String) {
        val existingRaw = prefs(context).getString(KEY_CUSTOM_MICROAGENTS, null) ?: return
        val updated = try {
            json.decodeFromString<List<OpenHandsMicroagent>>(existingRaw).filterNot { it.id == id }
        } catch (_: Exception) {
            emptyList()
        }
        prefs(context).edit {
            putString(KEY_CUSTOM_MICROAGENTS, json.encodeToString(updated))
        }
    }

    /**
     * Matches enabled microagents whose trigger keywords appear in the user prompt
     * (or falls back to always-on microagents if none match explicitly).
     */
    fun findTriggeredMicroagents(taskText: String, microagents: List<OpenHandsMicroagent>): List<OpenHandsMicroagent> {
        val lower = taskText.lowercase()
        val enabled = microagents.filter { it.enabled }
        val keywordMatches = enabled.filter { agent ->
            agent.triggerKeywords.any { kw -> kw.isNotBlank() && lower.contains(kw.lowercase()) }
        }
        return if (keywordMatches.isNotEmpty()) keywordMatches else enabled.take(2)
    }

    fun filterMicroagents(microagents: List<OpenHandsMicroagent>, query: String): List<OpenHandsMicroagent> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return microagents
        return microagents.filter { agent ->
            agent.name.lowercase().contains(q) ||
                agent.description.lowercase().contains(q) ||
                agent.sourcePath.lowercase().contains(q) ||
                agent.triggerKeywords.any { it.lowercase().contains(q) }
        }
    }

    suspend fun syncFromGitHub(context: Context): Result<OpenHandsSyncSummary> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_TREE_URL)
                .header("User-Agent", "Baby-Android-App")
                .header("Accept", "application/vnd.github+json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("OpenHands GitHub sync failed (HTTP ${response.code})")
                    )
                }
                val (discovered, count) = parseGitHubTree(loadBundledMicroagents(context), body)
                prefs(context).edit {
                    putString(KEY_SYNCED_MICROAGENTS, json.encodeToString(discovered))
                }
                val total = getMicroagents(context).size
                Result.success(OpenHandsSyncSummary(totalMicroagents = total, remoteFilesDiscovered = count))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Extracts microagents and agenthub modules from OpenHands/OpenHands git tree JSON.
     */
    fun parseGitHubTree(
        baseMicroagents: List<OpenHandsMicroagent>,
        treeJson: String
    ): Pair<List<OpenHandsMicroagent>, Int> {
        val root = JSONObject(treeJson)
        val tree = root.optJSONArray("tree") ?: return emptyList<OpenHandsMicroagent>() to 0
        val existingPaths = baseMicroagents.map { it.sourcePath }.toSet()
        val discovered = mutableListOf<OpenHandsMicroagent>()
        var remoteCount = 0

        for (i in 0 until tree.length()) {
            val node = tree.optJSONObject(i) ?: continue
            val path = node.optString("path")
            val type = node.optString("type")
            if (type != "blob") continue

            val isMicroagentFile = (path.startsWith("microagents/") || path.startsWith(".openhands/microagents/")) &&
                path.endsWith(".md") && !path.endsWith("README.md")
            val isAgentReadme = path.startsWith("openhands/agenthub/") && path.endsWith("/README.md")

            if (isMicroagentFile || isAgentReadme) {
                remoteCount++
                if (!existingPaths.contains(path) && discovered.size < 40) {
                    val slug = if (isAgentReadme) {
                        path.removePrefix("openhands/agenthub/").removeSuffix("/README.md")
                    } else {
                        path.substringAfterLast('/').removeSuffix(".md")
                    }
                    val title = slug.split('_', '-', '/')
                        .filter { it.isNotBlank() }
                        .joinToString(" ") { part ->
                            part.replaceFirstChar { it.uppercaseChar() }
                        }
                    val kindLabel = if (isAgentReadme) "Synced OpenHands AgentHub" else "Synced OpenHands Microagent"
                    discovered.add(
                        OpenHandsMicroagent(
                            id = "synced-${slug.lowercase()}",
                            name = title,
                            triggerKeywords = listOf(slug.lowercase(), if (isAgentReadme) "agent" else "microagent"),
                            description = "$kindLabel from $path in OpenHands/OpenHands.",
                            instructions = "Apply official OpenHands guidance from $path ($title) during planning, tool selection, and workspace verification.",
                            sourcePath = path,
                            enabled = true,
                            isCustom = false
                        )
                    )
                }
            }
        }
        return discovered to remoteCount
    }

    @Synchronized
    fun getConversations(context: Context): List<OpenHandsConversation> {
        val raw = prefs(context).getString(KEY_CONVERSATIONS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<OpenHandsConversation>>(raw)
                .sortedByDescending { it.updatedAtMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveConversation(context: Context, conversation: OpenHandsConversation) {
        val others = getConversations(context).filterNot { it.id == conversation.id }
        val updated = (listOf(conversation) + others).take(MAX_CONVERSATIONS)
        prefs(context).edit {
            putString(KEY_CONVERSATIONS, json.encodeToString(updated))
        }
    }

    @Synchronized
    fun deleteConversation(context: Context, conversationId: String) {
        val updated = getConversations(context).filterNot { it.id == conversationId }
        prefs(context).edit {
            putString(KEY_CONVERSATIONS, json.encodeToString(updated))
        }
    }

    @Synchronized
    fun getServerConfig(context: Context): OpenHandsServerConfig {
        val raw = prefs(context).getString(KEY_SERVER_CONFIG, null) ?: return OpenHandsServerConfig()
        return try {
            json.decodeFromString<OpenHandsServerConfig>(raw)
        } catch (_: Exception) {
            OpenHandsServerConfig()
        }
    }

    @Synchronized
    fun saveServerConfig(context: Context, config: OpenHandsServerConfig) {
        prefs(context).edit {
            putString(KEY_SERVER_CONFIG, json.encodeToString(config))
        }
    }
}
