package com.blurr.voice.ecc

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Persistent storage for ECC Project Rules, Active Context Profile (Dev/Review/Research),
 * Continuous-Learning Instincts/Memories, and saved 5-stage workflow sessions.
 */
object EccMemoryStore {

    private const val PREFS_NAME = "EccToolkitPrefs"
    private const val KEY_PROFILE = "ecc_project_profile_json"
    private const val KEY_MEMORIES = "ecc_memories_json"
    private const val KEY_RULES = "ecc_rules_json"
    private const val KEY_SESSIONS = "ecc_sessions_json"
    private const val MAX_MEMORIES = 60
    private const val MAX_SESSIONS = 25

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getProfile(context: Context): EccProjectProfile {
        val raw = prefs(context).getString(KEY_PROFILE, null) ?: return EccProjectProfile()
        return try {
            json.decodeFromString<EccProjectProfile>(raw)
        } catch (_: Exception) {
            EccProjectProfile()
        }
    }

    @Synchronized
    fun saveProfile(context: Context, profile: EccProjectProfile) {
        prefs(context).edit {
            putString(KEY_PROFILE, json.encodeToString(profile))
        }
    }

    @Synchronized
    fun setContextMode(context: Context, mode: EccContextMode) {
        saveProfile(context, getProfile(context).copy(contextMode = mode))
    }

    @Synchronized
    fun getMemories(context: Context): List<EccMemoryItem> {
        val raw = prefs(context).getString(KEY_MEMORIES, null) ?: return defaultSeedMemories()
        return try {
            json.decodeFromString<List<EccMemoryItem>>(raw)
                .sortedByDescending { it.updatedAtMs }
        } catch (_: Exception) {
            defaultSeedMemories()
        }
    }

    private fun defaultSeedMemories(): List<EccMemoryItem> = listOf(
        EccMemoryItem(
            id = "seed-gemini-38",
            title = "Gemini 3.8 Flash User Key",
            content = "All cloud AI requests route through GeminiApi/AiRepository using gemini-3.8-flash and the user's own GeminiKeyManager API key from Settings.",
            kind = EccMemoryKind.DECISION
        ),
        EccMemoryItem(
            id = "seed-no-firebase-auth",
            title = "Local-First Persistence",
            content = "Do not require Firebase Authentication or hardcoded secrets; store user profile, memories, and ECC project context locally.",
            kind = EccMemoryKind.PATTERN
        )
    )

    @Synchronized
    fun addMemory(
        context: Context,
        title: String,
        content: String,
        kind: EccMemoryKind = EccMemoryKind.INSTINCT
    ): EccMemoryItem {
        val item = EccMemoryItem(
            id = UUID.randomUUID().toString(),
            title = title.trim().ifEmpty { kind.label },
            content = content.trim(),
            kind = kind,
            updatedAtMs = System.currentTimeMillis()
        )
        val updated = (listOf(item) + getMemories(context)).take(MAX_MEMORIES)
        saveMemories(context, updated)
        return item
    }

    @Synchronized
    fun deleteMemory(context: Context, id: String) {
        val updated = getMemories(context).filterNot { it.id == id }
        prefs(context).edit {
            putString(KEY_MEMORIES, json.encodeToString(updated))
        }
    }

    private fun saveMemories(context: Context, items: List<EccMemoryItem>) {
        prefs(context).edit {
            putString(KEY_MEMORIES, json.encodeToString(items))
        }
    }

    @Synchronized
    fun getRules(context: Context, defaults: List<EccProjectRule>): List<EccProjectRule> {
        val raw = prefs(context).getString(KEY_RULES, null)
        if (raw.isNullOrBlank()) {
            return defaults
        }
        return try {
            val saved = json.decodeFromString<List<EccProjectRule>>(raw)
            val savedById = saved.associateBy { it.id }
            val mergedDefaults = defaults.map { def -> savedById[def.id] ?: def }
            val customRules = saved.filter { it.isCustom }
            mergedDefaults + customRules
        } catch (_: Exception) {
            defaults
        }
    }

    @Synchronized
    fun toggleRule(context: Context, defaults: List<EccProjectRule>, ruleId: String): List<EccProjectRule> {
        val updated = getRules(context, defaults).map { rule ->
            if (rule.id == ruleId) rule.copy(enabled = !rule.enabled) else rule
        }
        saveRules(context, updated)
        return updated
    }

    @Synchronized
    fun addCustomRule(
        context: Context,
        defaults: List<EccProjectRule>,
        title: String,
        category: String,
        ruleText: String
    ): EccProjectRule {
        val newRule = EccProjectRule(
            id = "custom-${UUID.randomUUID().toString().take(8)}",
            title = title.trim(),
            category = category.trim().ifEmpty { "project/custom" },
            ruleText = ruleText.trim(),
            enabled = true,
            isCustom = true
        )
        val updated = getRules(context, defaults) + newRule
        saveRules(context, updated)
        return newRule
    }

    @Synchronized
    fun deleteCustomRule(context: Context, defaults: List<EccProjectRule>, ruleId: String): List<EccProjectRule> {
        val updated = getRules(context, defaults).filterNot { it.id == ruleId && it.isCustom }
        saveRules(context, updated)
        return updated
    }

    private fun saveRules(context: Context, rules: List<EccProjectRule>) {
        prefs(context).edit {
            putString(KEY_RULES, json.encodeToString(rules))
        }
    }

    @Synchronized
    fun getSessions(context: Context): List<EccWorkflowSession> {
        val raw = prefs(context).getString(KEY_SESSIONS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<EccWorkflowSession>>(raw)
                .sortedByDescending { it.createdAtMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun saveSession(context: Context, session: EccWorkflowSession) {
        val current = getSessions(context).filterNot { it.id == session.id }
        val updated = (listOf(session) + current).take(MAX_SESSIONS)
        prefs(context).edit {
            putString(KEY_SESSIONS, json.encodeToString(updated))
        }
    }

    /**
     * Builds a bounded "Strategic Compact" context brief injected into ECC agents & workflows.
     */
    fun buildContextBrief(
        context: Context,
        defaultRules: List<EccProjectRule>,
        maxChars: Int = 2400
    ): String {
        val profile = getProfile(context)
        val activeRules = getRules(context, defaultRules)
            .filter { it.enabled }
            .sortedByDescending { it.isCustom }
        val memories = getMemories(context).take(8)

        val raw = buildString {
            appendLine("<ecc_project_context>")
            appendLine("Project: ${profile.projectName}")
            appendLine("Stack: ${profile.techStack}")
            appendLine("Architecture: ${profile.architectureNotes}")
            appendLine("Active Context Mode: ${profile.contextMode.label} — ${profile.contextMode.guidance}")
            appendLine("</ecc_project_context>")
            appendLine()
            if (activeRules.isNotEmpty()) {
                appendLine("<ecc_active_rules>")
                activeRules.forEach { rule ->
                    appendLine("- [${rule.category}] ${rule.title}: ${rule.ruleText}")
                }
                appendLine("</ecc_active_rules>")
                appendLine()
            }
            if (memories.isNotEmpty()) {
                appendLine("<ecc_project_memory>")
                memories.forEach { mem ->
                    appendLine("- [${mem.kind.label}] ${mem.title}: ${mem.content}")
                }
                appendLine("</ecc_project_memory>")
            }
        }.trim()

        return if (raw.length <= maxChars) raw else raw.take(maxChars - 32) + "\n...[compacted]"
    }
}
