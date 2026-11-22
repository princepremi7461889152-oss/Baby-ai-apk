package com.blurr.voice.openhands

import android.content.Context
import com.blurr.voice.ecc.EccSecurityScanner
import com.blurr.voice.publicapis.PublicApisRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class SandboxExecutionResult(
    val title: String,
    val output: String,
    val isError: Boolean = false,
    val targetPath: String? = null
)

/**
 * Isolated on-device sandbox workspace & action runtime modeled after OpenHands'
 * Workspace + Runtime Action Execution Server. All file operations are strictly
 * confined inside `filesDir/openhands_workspace/<workspaceId>`.
 */
object OpenHandsSandbox {

    private const val ROOT_FOLDER = "openhands_workspace"
    private const val MAX_FILE_READ_CHARS = 5000
    private const val MAX_BROWSE_CHARS = 4000

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getWorkspaceRoot(context: Context, workspaceId: String = "default-workspace"): File {
        val safeWorkspace = workspaceId.replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "default-workspace" }
        val root = File(File(context.applicationContext.filesDir, ROOT_FOLDER), safeWorkspace)
        if (!root.exists()) {
            root.mkdirs()
        }
        seedStarterFilesIfEmpty(root)
        return root
    }

    private fun seedStarterFilesIfEmpty(root: File) {
        val existing = root.listFiles()
        if (existing != null && existing.isNotEmpty()) return

        File(root, "README.md").writeText(
            """
            # OpenHands Local Workspace
            
            This isolated Android sandbox hosts files created, inspected, and edited by
            OpenHands agents (`CodeActAgent`, `ReadOnlyAgent`, `BrowsingAgent`).
            
            Try commands like:
            - `ls`
            - `cat Main.kt`
            - `grep TODO`
            - `scan Main.kt`
            - `api weather`
            """.trimIndent()
        )
        File(root, "Main.kt").writeText(
            """
            package workspace.demo

            // TODO: Add input validation and unit tests
            class GreetingService {
                fun buildGreeting(name: String): String {
                    val clean = name.trim().ifEmpty { "Developer" }
                    return "Hello, ${'$'}clean from OpenHands Sandbox!"
                }
            }
            """.trimIndent()
        )
    }

    private fun resolveSafePath(root: File, relativePath: String): File? {
        val cleaned = relativePath.trim().trimStart('/')
        if (cleaned.isEmpty() || cleaned.contains("\u0000")) return null
        val target = File(root, cleaned)
        val rootCanonical = root.canonicalPath
        val targetCanonical = target.canonicalPath
        if (targetCanonical != rootCanonical && !targetCanonical.startsWith(rootCanonical + File.separator)) {
            return null
        }
        return target
    }

    fun listWorkspaceFiles(context: Context, workspaceId: String = "default-workspace"): List<WorkspaceFileInfo> {
        val root = getWorkspaceRoot(context, workspaceId)
        val result = mutableListOf<WorkspaceFileInfo>()
        fun walk(dir: File) {
            dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))?.forEach { file ->
                val rel = file.relativeTo(root).path
                if (file.isDirectory) {
                    result.add(WorkspaceFileInfo(rel, 0L, file.lastModified(), true))
                    walk(file)
                } else {
                    result.add(WorkspaceFileInfo(rel, file.length(), file.lastModified(), false))
                }
            }
        }
        walk(root)
        return result
    }

    fun readFile(
        context: Context,
        relativePath: String,
        workspaceId: String = "default-workspace"
    ): SandboxExecutionResult {
        val root = getWorkspaceRoot(context, workspaceId)
        val file = resolveSafePath(root, relativePath)
            ?: return SandboxExecutionResult(
                title = "FileRead Denied",
                output = "Invalid or out-of-sandbox path: $relativePath",
                isError = true,
                targetPath = relativePath
            )
        if (!file.exists() || file.isDirectory) {
            return SandboxExecutionResult(
                title = "FileRead Missing",
                output = "File not found in workspace: $relativePath",
                isError = true,
                targetPath = relativePath
            )
        }
        val lines = file.readLines()
        val numbered = lines.mapIndexed { idx, line -> "${idx + 1}: $line" }.joinToString("\n")
        val truncated = if (numbered.length > MAX_FILE_READ_CHARS) {
            numbered.take(MAX_FILE_READ_CHARS) + "\n... [truncated after $MAX_FILE_READ_CHARS chars]"
        } else {
            numbered
        }
        return SandboxExecutionResult(
            title = "Read $relativePath (${lines.size} lines)",
            output = truncated,
            isError = false,
            targetPath = relativePath
        )
    }

    fun writeFile(
        context: Context,
        relativePath: String,
        content: String,
        workspaceId: String = "default-workspace",
        readOnly: Boolean = false
    ): SandboxExecutionResult {
        if (readOnly) {
            return SandboxExecutionResult(
                title = "ReadOnly Guardrail",
                output = "Blocked FileWrite to '$relativePath': active agent is in Read-Only mode.",
                isError = true,
                targetPath = relativePath
            )
        }
        val root = getWorkspaceRoot(context, workspaceId)
        val file = resolveSafePath(root, relativePath)
            ?: return SandboxExecutionResult(
                title = "FileWrite Denied",
                output = "Path escapes sandbox workspace: $relativePath",
                isError = true,
                targetPath = relativePath
            )
        if (file.canonicalPath == root.canonicalPath) {
            return SandboxExecutionResult(
                title = "FileWrite Denied",
                output = "Cannot overwrite workspace root directory.",
                isError = true,
                targetPath = relativePath
            )
        }
        file.parentFile?.mkdirs()
        file.writeText(content)
        val lineCount = content.lines().size
        return SandboxExecutionResult(
            title = "Wrote $relativePath",
            output = "Saved $relativePath (${content.toByteArray().size} bytes, $lineCount lines) in workspace.",
            isError = false,
            targetPath = relativePath
        )
    }

    fun editFile(
        context: Context,
        relativePath: String,
        oldSnippet: String,
        newSnippet: String,
        workspaceId: String = "default-workspace",
        readOnly: Boolean = false
    ): SandboxExecutionResult {
        if (readOnly) {
            return SandboxExecutionResult(
                title = "ReadOnly Guardrail",
                output = "Blocked FileEdit on '$relativePath': active agent is in Read-Only mode.",
                isError = true,
                targetPath = relativePath
            )
        }
        val root = getWorkspaceRoot(context, workspaceId)
        val file = resolveSafePath(root, relativePath)
            ?: return SandboxExecutionResult(
                title = "FileEdit Denied",
                output = "Path escapes sandbox workspace: $relativePath",
                isError = true,
                targetPath = relativePath
            )
        if (!file.exists() || file.isDirectory) {
            return SandboxExecutionResult(
                title = "FileEdit Missing",
                output = "Cannot edit missing file: $relativePath",
                isError = true,
                targetPath = relativePath
            )
        }
        if (oldSnippet.isEmpty()) {
            return SandboxExecutionResult(
                title = "FileEdit Invalid",
                output = "old_str must be non-empty for str_replace edit.",
                isError = true,
                targetPath = relativePath
            )
        }
        val current = file.readText()
        if (!current.contains(oldSnippet)) {
            return SandboxExecutionResult(
                title = "FileEdit No Match",
                output = "Target snippet not found in $relativePath. Read the file first to verify exact text.",
                isError = true,
                targetPath = relativePath
            )
        }
        val updated = current.replaceFirst(oldSnippet, newSnippet)
        file.writeText(updated)
        return SandboxExecutionResult(
            title = "Edited $relativePath",
            output = "Replaced target snippet in $relativePath successfully.",
            isError = false,
            targetPath = relativePath
        )
    }

    fun deleteFile(
        context: Context,
        relativePath: String,
        workspaceId: String = "default-workspace",
        readOnly: Boolean = false
    ): SandboxExecutionResult {
        if (readOnly) {
            return SandboxExecutionResult(
                title = "ReadOnly Guardrail",
                output = "Blocked delete on '$relativePath' in Read-Only mode.",
                isError = true,
                targetPath = relativePath
            )
        }
        val root = getWorkspaceRoot(context, workspaceId)
        val file = resolveSafePath(root, relativePath)
            ?: return SandboxExecutionResult("Delete Denied", "Invalid path: $relativePath", true, relativePath)
        if (!file.exists() || file.canonicalPath == root.canonicalPath) {
            return SandboxExecutionResult("Delete Failed", "File not found: $relativePath", true, relativePath)
        }
        val ok = file.deleteRecursively()
        return SandboxExecutionResult(
            title = if (ok) "Deleted $relativePath" else "Delete Failed",
            output = if (ok) "Removed $relativePath from workspace." else "Could not remove $relativePath.",
            isError = !ok,
            targetPath = relativePath
        )
    }

    fun grepWorkspace(
        context: Context,
        pattern: String,
        workspaceId: String = "default-workspace"
    ): SandboxExecutionResult {
        val needle = pattern.trim()
        if (needle.isEmpty()) {
            return SandboxExecutionResult("Grep Error", "Provide a non-empty search pattern.", true)
        }
        val root = getWorkspaceRoot(context, workspaceId)
        val matches = mutableListOf<String>()
        listWorkspaceFiles(context, workspaceId)
            .filter { !it.isDirectory }
            .forEach { info ->
                val f = File(root, info.relativePath)
                f.readLines().forEachIndexed { idx, line ->
                    if (line.contains(needle, ignoreCase = true) && matches.size < 40) {
                        matches.add("${info.relativePath}:${idx + 1}: ${line.trim()}")
                    }
                }
            }
        return SandboxExecutionResult(
            title = "Grep '$needle' (${matches.size} hits)",
            output = if (matches.isEmpty()) "No matches found for '$needle'." else matches.joinToString("\n")
        )
    }

    suspend fun executeCommand(
        context: Context,
        rawCommand: String,
        workspaceId: String = "default-workspace",
        readOnly: Boolean = false
    ): SandboxExecutionResult = withContext(Dispatchers.IO) {
        val cmd = rawCommand.trim()
        if (cmd.isEmpty()) {
            return@withContext SandboxExecutionResult("CmdRun Error", "Command is empty.", isError = true)
        }

        val lower = cmd.lowercase()
        val forbidden = listOf("rm -rf /", "--no-verify", "chmod 777", "su ", "sudo ", "/system", "/data/data")
        if (forbidden.any { lower.contains(it) }) {
            return@withContext SandboxExecutionResult(
                title = "Sandbox Security Block",
                output = "Blocked unsafe shell pattern in OpenHands Sandbox: $cmd",
                isError = true
            )
        }

        val root = getWorkspaceRoot(context, workspaceId)
        val tokens = cmd.split(Regex("\\s+"))
        val head = tokens.firstOrNull().orEmpty().lowercase()
        val arg1 = tokens.getOrNull(1).orEmpty()
        val rest = cmd.removePrefix(tokens.firstOrNull().orEmpty()).trim()

        when (head) {
            "pwd" -> SandboxExecutionResult("pwd", "/workspace/$workspaceId")
            "ls", "dir", "find" -> {
                val files = listWorkspaceFiles(context, workspaceId)
                val body = if (files.isEmpty()) "(empty workspace)" else files.joinToString("\n") {
                    if (it.isDirectory) "[DIR]  ${it.relativePath}/" else "[FILE] ${it.relativePath} (${it.sizeBytes} B)"
                }
                SandboxExecutionResult("$head (${files.size} entries)", body)
            }
            "cat", "head" -> {
                if (arg1.isBlank()) {
                    SandboxExecutionResult("$head Error", "Usage: $head <relative-path>", true)
                } else {
                    readFile(context, arg1, workspaceId)
                }
            }
            "wc" -> {
                if (arg1.isBlank()) {
                    SandboxExecutionResult("wc Error", "Usage: wc <relative-path>", true)
                } else {
                    val target = resolveSafePath(root, arg1)
                    if (target == null || !target.exists() || target.isDirectory) {
                        SandboxExecutionResult("wc Missing", "File not found: $arg1", true, arg1)
                    } else {
                        val text = target.readText()
                        val lines = target.readLines().size
                        val words = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.size
                        SandboxExecutionResult("wc $arg1", "$lines lines, $words words, ${text.length} chars", false, arg1)
                    }
                }
            }
            "grep" -> grepWorkspace(context, rest, workspaceId)
            "echo" -> SandboxExecutionResult("echo", rest)
            "touch" -> {
                if (arg1.isBlank()) {
                    SandboxExecutionResult("touch Error", "Usage: touch <relative-path>", true)
                } else {
                    writeFile(context, arg1, "", workspaceId, readOnly)
                }
            }
            "mkdir" -> {
                if (readOnly) {
                    SandboxExecutionResult("ReadOnly Guardrail", "Blocked mkdir in Read-Only mode.", true)
                } else if (arg1.isBlank()) {
                    SandboxExecutionResult("mkdir Error", "Usage: mkdir <dir-name>", true)
                } else {
                    val dir = resolveSafePath(root, arg1)
                    if (dir == null) {
                        SandboxExecutionResult("mkdir Denied", "Path escapes sandbox: $arg1", true, arg1)
                    } else {
                        dir.mkdirs()
                        SandboxExecutionResult("mkdir $arg1", "Created directory $arg1", false, arg1)
                    }
                }
            }
            "rm" -> {
                if (arg1.isBlank()) {
                    SandboxExecutionResult("rm Error", "Usage: rm <relative-path>", true)
                } else {
                    deleteFile(context, arg1, workspaceId, readOnly)
                }
            }
            "scan" -> runSecurityScanOnWorkspace(context, arg1, workspaceId)
            "api" -> {
                val query = rest
                val entries = PublicApisRepository.loadCatalog(context)
                val hits = PublicApisRepository.filter(entries, query, "All", noAuthOnly = false, httpsOnly = false).take(6)
                val out = if (hits.isEmpty()) {
                    "No Public APIs matched '$query'."
                } else {
                    hits.joinToString("\n") { "- ${it.api} [${it.category}] Auth=${it.auth} HTTPS=${it.https}: ${it.link}" }
                }
                SandboxExecutionResult("api '$query' (${hits.size} matches)", out)
            }
            "help" -> SandboxExecutionResult(
                "Sandbox Commands",
                "Supported commands: ls, pwd, cat <file>, wc <file>, grep <term>, touch <file>, mkdir <dir>, rm <file>, echo <text>, scan [file], api <topic>, help"
            )
            else -> SandboxExecutionResult(
                title = "Sandbox Command Info",
                output = "On-device Android sandbox does not run arbitrary OS binaries ('$head'). " +
                    "Use supported sandbox commands (`ls`, `cat`, `grep`, `wc`, `touch`, `mkdir`, `rm`, `scan`, `api`, `help`) " +
                    "or configure a remote OpenHands Agent Server for full Linux container execution.",
                isError = true
            )
        }
    }

    private fun runSecurityScanOnWorkspace(
        context: Context,
        targetPath: String,
        workspaceId: String
    ): SandboxExecutionResult {
        val root = getWorkspaceRoot(context, workspaceId)
        val codeBundle = if (targetPath.isNotBlank()) {
            val file = resolveSafePath(root, targetPath)
            if (file == null || !file.exists() || file.isDirectory) {
                return SandboxExecutionResult("Scan Missing", "File not found: $targetPath", true, targetPath)
            }
            file.readText()
        } else {
            val files = listWorkspaceFiles(context, workspaceId).filter { !it.isDirectory }
            if (files.isEmpty()) return SandboxExecutionResult("Scan Empty", "No files in workspace to scan.")
            files.joinToString("\n") { info ->
                "// File: ${info.relativePath}\n" + File(root, info.relativePath).readText()
            }
        }
        val report = EccSecurityScanner.scan(codeBundle)
        val details = buildString {
            appendLine(report.summary)
            report.findings.forEach { f ->
                appendLine("- [${f.severity.label}] L${f.lineNumber} (${f.ruleId}) ${f.title}: ${f.remediation}")
            }
        }.trim()
        return SandboxExecutionResult(
            title = "Security Scan (${report.score}/100)",
            output = details,
            isError = !report.passedGate,
            targetPath = targetPath.ifBlank { null }
        )
    }

    suspend fun browseUrl(rawUrl: String): SandboxExecutionResult = withContext(Dispatchers.IO) {
        val url = rawUrl.trim()
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            return@withContext SandboxExecutionResult(
                title = "BrowseURL Invalid",
                output = "URL must start with https:// or http:// (got: $url)",
                isError = true,
                targetPath = url
            )
        }
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Baby-OpenHands-BrowsingAgent/1.0")
                .build()
            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                val contentType = response.header("Content-Type").orEmpty()
                val readable = if (contentType.contains("html", ignoreCase = true) || rawBody.trimStart().startsWith("<")) {
                    rawBody
                        .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
                        .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
                        .replace(Regex("<[^>]+>"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                } else {
                    rawBody.trim()
                }
                val preview = if (readable.length > MAX_BROWSE_CHARS) {
                    readable.take(MAX_BROWSE_CHARS) + "\n... [truncated]"
                } else {
                    readable
                }
                SandboxExecutionResult(
                    title = "BrowseURL HTTP ${response.code}",
                    output = "URL: $url\nContent-Type: $contentType\n\n$preview",
                    isError = !response.isSuccessful,
                    targetPath = url
                )
            }
        } catch (e: Exception) {
            SandboxExecutionResult(
                title = "BrowseURL Failed",
                output = "Failed to fetch $url: ${e.message ?: "network error"}",
                isError = true,
                targetPath = url
            )
        }
    }
}
