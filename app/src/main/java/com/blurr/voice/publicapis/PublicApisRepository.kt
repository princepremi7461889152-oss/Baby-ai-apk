package com.blurr.voice.publicapis

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class EndpointProbeResult(
    val isSuccess: Boolean,
    val statusCode: Int,
    val contentType: String,
    val bodyPreview: String
)

object PublicApisRepository {

    private const val GITHUB_README_URL =
        "https://raw.githubusercontent.com/public-apis/public-apis/master/README.md"
    private const val ASSET_FILE = "public_apis_catalog.json"
    private const val CACHE_FILE = "public_apis_cache.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val linkRegex = Regex("""\[([^\]]+)]\(([^)]+)\)""")

    suspend fun loadCatalog(context: Context): List<PublicApiEntry> = withContext(Dispatchers.IO) {
        val cacheFile = File(context.filesDir, CACHE_FILE)
        if (cacheFile.exists()) {
            try {
                val cached = json.decodeFromString<List<PublicApiEntry>>(cacheFile.readText())
                if (cached.isNotEmpty()) {
                    return@withContext cached
                }
            } catch (_: Exception) {
            }
        }

        return@withContext try {
            val assetText = context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
            json.decodeFromString<List<PublicApiEntry>>(assetText)
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun syncFromGitHub(context: Context): Result<List<PublicApiEntry>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(GITHUB_README_URL)
                .header("User-Agent", "Baby-Android-App")
                .build()

            client.newCall(request).execute().use { response ->
                val markdown = response.body?.string().orEmpty()
                if (!response.isSuccessful || markdown.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("GitHub sync failed (HTTP ${response.code})")
                    )
                }

                val parsed = parseReadmeMarkdown(markdown)
                if (parsed.isEmpty()) {
                    return@withContext Result.failure(
                        IllegalStateException("No API entries parsed from public-apis README.md")
                    )
                }

                val cacheFile = File(context.filesDir, CACHE_FILE)
                cacheFile.writeText(json.encodeToString(parsed))
                Result.success(parsed)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun parseReadmeMarkdown(markdown: String): List<PublicApiEntry> {
        val entries = mutableListOf<PublicApiEntry>()
        var currentCategory = ""

        markdown.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.startsWith("### ")) {
                val cat = line.removePrefix("### ").trim()
                currentCategory = if (cat.equals("Index", ignoreCase = true)) "" else cat
                return@forEach
            }

            if (currentCategory.isNotEmpty() && line.startsWith("|")) {
                val cells = line.split("|").map { it.trim() }.drop(1).dropLastWhile { it.isEmpty() }
                if (cells.size >= 5) {
                    val firstCell = cells[0]
                    if (firstCell.equals("API", ignoreCase = true) || firstCell.startsWith(":---") || firstCell.startsWith("---")) {
                        return@forEach
                    }

                    val match = linkRegex.find(firstCell) ?: return@forEach
                    val name = match.groupValues[1].trim()
                    val link = match.groupValues[2].trim()
                    val description = cells[1].trim()
                    val authRaw = cells[2].replace("`", "").trim()
                    val auth = if (authRaw.isEmpty()) "No" else authRaw
                    val httpsRaw = cells[3].replace("`", "").trim()
                    val https = httpsRaw.equals("Yes", ignoreCase = true)
                    val cors = cells[4].replace("`", "").trim().ifEmpty { "Unknown" }

                    if (name.isNotEmpty() && link.startsWith("http")) {
                        entries.add(
                            PublicApiEntry(
                                api = name,
                                description = description,
                                auth = auth,
                                https = https,
                                cors = cors,
                                link = link,
                                category = currentCategory
                            )
                        )
                    }
                }
            }
        }
        return entries
    }

    fun filter(
        entries: List<PublicApiEntry>,
        query: String,
        category: String?,
        noAuthOnly: Boolean,
        httpsOnly: Boolean
    ): List<PublicApiEntry> {
        val q = query.trim().lowercase()
        return entries.filter { entry ->
            val matchesCategory = category.isNullOrBlank() ||
                category == "All" ||
                entry.category.equals(category, ignoreCase = true)
            val matchesAuth = !noAuthOnly || entry.auth.equals("No", ignoreCase = true)
            val matchesHttps = !httpsOnly || entry.https
            val matchesQuery = q.isEmpty() ||
                entry.api.lowercase().contains(q) ||
                entry.description.lowercase().contains(q) ||
                entry.category.lowercase().contains(q)
            matchesCategory && matchesAuth && matchesHttps && matchesQuery
        }
    }

    suspend fun probeEndpoint(url: String): EndpointProbeResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Baby-Android-App")
                .build()

            client.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type") ?: "unknown"
                val rawBody = response.body?.string().orEmpty()
                val preview = rawBody
                    .replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(360)
                    .ifEmpty { "(empty response body)" }

                EndpointProbeResult(
                    isSuccess = response.isSuccessful,
                    statusCode = response.code,
                    contentType = contentType,
                    bodyPreview = preview
                )
            }
        } catch (e: Exception) {
            EndpointProbeResult(
                isSuccess = false,
                statusCode = -1,
                contentType = "error",
                bodyPreview = e.message ?: "Network request failed"
            )
        }
    }
}
