package com.blurr.voice.ecc

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class EccSyncSummary(
    val totalComponents: Int,
    val agentsCount: Int,
    val skillsCount: Int,
    val commandsCount: Int,
    val remoteFilesDiscovered: Int
)

/**
 * Loads the bundled ECC (affaan-m/ECC) catalog of Agents, Skills, Commands, and Rules,
 * and supports live synchronization from https://github.com/affaan-m/ECC.
 */
object EccRepository {

    private const val ASSET_FILE = "ecc_catalog.json"
    private const val CACHE_FILE = "ecc_catalog_cache.json"
    private const val GITHUB_TREE_API_URL =
        "https://api.github.com/repos/affaan-m/ECC/git/trees/main?recursive=1"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun loadCatalog(context: Context): EccCatalog = withContext(Dispatchers.IO) {
        val cacheFile = File(context.filesDir, CACHE_FILE)
        if (cacheFile.exists()) {
            try {
                val cached = json.decodeFromString<EccCatalog>(cacheFile.readText())
                if (cached.agents.isNotEmpty() || cached.skills.isNotEmpty()) {
                    return@withContext cached
                }
            } catch (_: Exception) {
            }
        }
        loadBundledCatalog(context)
    }

    fun loadBundledCatalog(context: Context): EccCatalog {
        return try {
            val raw = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            json.decodeFromString<EccCatalog>(raw)
        } catch (_: Exception) {
            EccCatalog()
        }
    }

    fun getAllComponents(catalog: EccCatalog): List<EccComponent> {
        return catalog.agents + catalog.skills + catalog.commands
    }

    fun filterComponents(
        catalog: EccCatalog,
        query: String,
        typeFilter: EccComponentType?
    ): List<EccComponent> {
        val q = query.trim().lowercase()
        return getAllComponents(catalog).filter { item ->
            val matchesType = typeFilter == null || item.type == typeFilter
            val matchesQuery = q.isEmpty() ||
                item.title.lowercase().contains(q) ||
                item.description.lowercase().contains(q) ||
                item.category.lowercase().contains(q) ||
                item.tags.any { it.lowercase().contains(q) } ||
                (item.command?.lowercase()?.contains(q) == true)
            matchesType && matchesQuery
        }
    }

    suspend fun syncFromGitHub(context: Context): Result<EccSyncSummary> = withContext(Dispatchers.IO) {
        try {
            val baseCatalog = loadBundledCatalog(context)
            val request = Request.Builder()
                .url(GITHUB_TREE_API_URL)
                .header("User-Agent", "Baby-Android-App")
                .header("Accept", "application/vnd.github+json")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful || body.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("GitHub ECC sync failed (HTTP ${response.code})")
                    )
                }

                val merged = mergeGitHubTreeWithCatalog(baseCatalog, body)
                val cacheFile = File(context.filesDir, CACHE_FILE)
                cacheFile.writeText(json.encodeToString(merged))

                Result.success(
                    EccSyncSummary(
                        totalComponents = merged.agents.size + merged.skills.size + merged.commands.size,
                        agentsCount = merged.agents.size,
                        skillsCount = merged.skills.size,
                        commandsCount = merged.commands.size,
                        remoteFilesDiscovered = countMarkdownDefinitions(body)
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun countMarkdownDefinitions(treeJson: String): Int {
        return try {
            val tree = JSONObject(treeJson).optJSONArray("tree") ?: return 0
            var count = 0
            for (i in 0 until tree.length()) {
                val path = tree.optJSONObject(i)?.optString("path").orEmpty()
                if (path.endsWith(".md") && (
                        path.startsWith("agents/") ||
                            path.startsWith("skills/") ||
                            path.startsWith("commands/") ||
                            path.startsWith("rules/")
                        )
                ) {
                    count++
                }
            }
            count
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Merges newly discovered agents, skills, and commands from affaan-m/ECC git tree
     * with Baby's tuned bundled prompts so core workflows never lose instructions.
     */
    fun mergeGitHubTreeWithCatalog(base: EccCatalog, treeJson: String): EccCatalog {
        val root = JSONObject(treeJson)
        val tree = root.optJSONArray("tree") ?: return base

        val agentsByPath = base.agents.associateBy { it.sourcePath }.toMutableMap()
        val skillsByPath = base.skills.associateBy { it.sourcePath }.toMutableMap()
        val commandsByPath = base.commands.associateBy { it.sourcePath }.toMutableMap()

        for (i in 0 until tree.length()) {
            val node = tree.optJSONObject(i) ?: continue
            val path = node.optString("path")
            val type = node.optString("type")
            if (type != "blob" || !path.endsWith(".md")) continue

            when {
                path.startsWith("agents/") && path != "agents/README.md" && !agentsByPath.containsKey(path) -> {
                    val slug = path.removePrefix("agents/").removeSuffix(".md")
                    val title = slugToTitle(slug)
                    agentsByPath[path] = EccComponent(
                        id = "agent-$slug",
                        title = title,
                        type = EccComponentType.AGENT,
                        category = "Synced ECC Agent",
                        sourcePath = path,
                        description = "Specialized ECC agent synced from $path in affaan-m/ECC.",
                        systemPrompt = "You are the ECC $title agent ($path). Apply rigorous software engineering standards, verify assumptions, and deliver actionable structured output.",
                        tags = listOf(slug, "synced", "agent")
                    )
                }
                path.startsWith("skills/") && path.endsWith("/SKILL.md") && !skillsByPath.containsKey(path) -> {
                    val slug = path.removePrefix("skills/").removeSuffix("/SKILL.md")
                    val title = slugToTitle(slug)
                    skillsByPath[path] = EccComponent(
                        id = "skill-$slug",
                        title = "$title Skill",
                        type = EccComponentType.SKILL,
                        category = "Synced ECC Skill",
                        sourcePath = path,
                        description = "Reusable ECC workflow skill synced from $path.",
                        systemPrompt = "Apply the ECC $title engineering skill ($path): provide structured step-by-step guidance, constraints, and verification checks.",
                        tags = listOf(slug, "synced", "skill")
                    )
                }
                path.startsWith("commands/") && path != "commands/README.md" && !commandsByPath.containsKey(path) -> {
                    val slug = path.removePrefix("commands/").removeSuffix(".md")
                    val cmd = "/$slug"
                    commandsByPath[path] = EccComponent(
                        id = "cmd-$slug",
                        title = "$cmd — ${slugToTitle(slug)}",
                        type = EccComponentType.COMMAND,
                        category = "Synced ECC Command",
                        sourcePath = path,
                        command = cmd,
                        description = "ECC slash command synced from $path.",
                        systemPrompt = "Execute the ECC $cmd workflow ($path) cleanly with structured output and verification steps.",
                        tags = listOf(cmd, slug, "synced")
                    )
                }
            }
        }

        return base.copy(
            agents = agentsByPath.values.toList(),
            skills = skillsByPath.values.toList(),
            commands = commandsByPath.values.toList()
        )
    }

    private fun slugToTitle(slug: String): String {
        return slug.split('-', '_', '/')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { ch -> ch.uppercaseChar() }
            }
    }
}
