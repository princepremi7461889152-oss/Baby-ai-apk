package com.blurr.voice.ecc

import android.content.Context
import com.blurr.voice.api.GeminiApi
import com.blurr.voice.api.TextPart
import com.blurr.voice.publicapis.PublicApisRepository
import com.blurr.voice.utilities.GeminiKeyManager

/**
 * Coordinates ECC Agents, Skills, Commands, 5-Stage Workflows (Plan -> Test -> Implement -> Review -> Verify),
 * Automated Review/Debugging, Research-First Lookup, and ChatActivity slash-command enrichment using Gemini 3.8 Flash.
 */
object EccWorkflowEngine {

    fun buildAgentPrompt(
        context: Context,
        component: EccComponent,
        userInput: String,
        catalog: EccCatalog,
        priorContext: String? = null
    ): String {
        val contextBrief = EccMemoryStore.buildContextBrief(context, catalog.rules)
        return buildString {
            appendLine("=== ECC ENGINEERING SYSTEM ===")
            appendLine("Component: ${component.title} (${component.type.name})")
            if (!component.command.isNullOrBlank()) {
                appendLine("Command: ${component.command}")
            }
            appendLine()
            appendLine("Role Instructions:")
            appendLine(component.systemPrompt)
            appendLine()
            appendLine(contextBrief)
            if (!priorContext.isNullOrBlank()) {
                appendLine()
                appendLine("<previous_stage_artifacts>")
                appendLine(priorContext.take(3500))
                appendLine("</previous_stage_artifacts>")
            }
            appendLine()
            appendLine("User Request / Target:")
            appendLine(userInput.trim())
        }.trim()
    }

    fun buildWorkflowStagePrompt(
        context: Context,
        stage: EccWorkflowStage,
        goal: String,
        previousOutputs: Map<String, String>,
        catalog: EccCatalog
    ): String {
        val agent = catalog.agents.find { it.id == stage.agentId }
            ?: EccComponent(
                id = stage.agentId,
                title = stage.label,
                type = EccComponentType.AGENT,
                category = "Workflow",
                description = stage.objective,
                systemPrompt = "Act as the ECC ${stage.label} agent. ${stage.objective}"
            )

        val priorArtifacts = EccWorkflowStage.values()
            .filter { it.order < stage.order }
            .mapNotNull { prev ->
                previousOutputs[prev.name]?.takeIf { it.isNotBlank() }?.let { out ->
                    "### Stage ${prev.order}: ${prev.label}\n${out.take(1200)}"
                }
            }
            .joinToString("\n\n")

        val stageDirective = when (stage) {
            EccWorkflowStage.PLAN ->
                "Produce a concrete phased plan: requirements, affected files/APIs, risks, and acceptance checklist."
            EccWorkflowStage.TEST ->
                "Write failing Red-phase unit/Robolectric test cases covering happy paths, boundaries, and failure cases BEFORE implementation."
            EccWorkflowStage.IMPLEMENT ->
                "Provide clean, modular Kotlin implementation that satisfies the Plan, passes the Tests, and respects all active ECC rules."
            EccWorkflowStage.REVIEW ->
                "Review the proposed implementation for bugs, concurrency/lifecycle leaks, security flaws (CRITICAL/HIGH/MEDIUM/LOW), and exact fixes."
            EccWorkflowStage.VERIFY ->
                "Produce final verification matrix: build/test commands, static security gate checks, edge-case coverage, and release readiness verdict."
        }

        val enrichedRequest = "$goal\n\nStage ${stage.order}/5 (${stage.label}) Directive: $stageDirective"
        return buildAgentPrompt(
            context = context,
            component = agent,
            userInput = enrichedRequest,
            catalog = catalog,
            priorContext = priorArtifacts.ifBlank { null }
        )
    }

    suspend fun runComponent(
        context: Context,
        component: EccComponent,
        userInput: String,
        catalog: EccCatalog,
        priorContext: String? = null
    ): String {
        if (!GeminiKeyManager(context).hasApiKey()) {
            return "Gemini API key not configured. Open Settings -> Gemini 3.8 Flash API Key and add your key first."
        }
        val prompt = buildAgentPrompt(context, component, userInput, catalog, priorContext)
        return GeminiApi.generateContent(
            chat = listOf("user" to listOf(TextPart(prompt))),
            context = context.applicationContext
        ) ?: "Unable to reach Gemini 3.8 Flash right now. Please check your network and API key in Settings."
    }

    suspend fun runWorkflowStage(
        context: Context,
        stage: EccWorkflowStage,
        goal: String,
        previousOutputs: Map<String, String>,
        catalog: EccCatalog
    ): Pair<String, EccScanReport?> {
        val localScan = if (stage == EccWorkflowStage.REVIEW || stage == EccWorkflowStage.VERIFY) {
            val implCode = previousOutputs[EccWorkflowStage.IMPLEMENT.name].orEmpty()
            if (implCode.isNotBlank()) EccSecurityScanner.scan(implCode) else null
        } else {
            null
        }

        if (!GeminiKeyManager(context).hasApiKey()) {
            val scanSummary = localScan?.let { "\n\nLocal AgentShield Scan:\n${it.summary}" }.orEmpty()
            return "Gemini API key not configured. Add your Gemini API key in Settings to run the ${stage.label} AI stage.$scanSummary" to localScan
        }

        val basePrompt = buildWorkflowStagePrompt(context, stage, goal, previousOutputs, catalog)
        val prompt = if (localScan != null) {
            "$basePrompt\n\n<local_security_scan>\n${localScan.summary}\n${
                localScan.findings.joinToString("\n") { "- [${it.severity.label}] L${it.lineNumber} ${it.title}: ${it.remediation}" }
            }\n</local_security_scan>"
        } else {
            basePrompt
        }

        val reply = GeminiApi.generateContent(
            chat = listOf("user" to listOf(TextPart(prompt))),
            context = context.applicationContext
        ) ?: "Unable to reach Gemini 3.8 Flash for ${stage.label} stage."

        return reply to localScan
    }

    suspend fun runReviewOrDebug(
        context: Context,
        codeOrError: String,
        mode: ReviewMode,
        catalog: EccCatalog
    ): Pair<EccScanReport, String> {
        val scanReport = EccSecurityScanner.scan(codeOrError)
        if (!GeminiKeyManager(context).hasApiKey()) {
            val offlineMsg = buildString {
                appendLine("Offline AgentShield Scan Completed (Gemini API key not configured for AI deep pass):")
                appendLine(scanReport.summary)
                scanReport.findings.forEach { f ->
                    appendLine("• [${f.severity.label}] Line ${f.lineNumber}: ${f.title}")
                    appendLine("  Fix: ${f.remediation}")
                }
            }.trim()
            return scanReport to offlineMsg
        }

        val agentId = when (mode) {
            ReviewMode.CODE_REVIEW -> "code-reviewer"
            ReviewMode.SECURITY_AUDIT -> "security-reviewer"
            ReviewMode.DEBUG_BUILD_ERROR -> "build-error-resolver"
        }
        val agent = catalog.agents.find { it.id == agentId } ?: catalog.agents.first()

        val promptInput = buildString {
            appendLine("Mode: ${mode.label}")
            appendLine("Offline AgentShield Findings (${scanReport.summary}):")
            if (scanReport.findings.isEmpty()) {
                appendLine("- No static pattern violations found.")
            } else {
                scanReport.findings.forEach { f ->
                    appendLine("- [${f.severity.label}] L${f.lineNumber} (${f.ruleId}) ${f.title}: ${f.snippet}")
                }
            }
            appendLine()
            appendLine("Target Code / Error Trace:")
            appendLine("```")
            appendLine(codeOrError.take(6000))
            appendLine("```")
        }

        val aiReply = runComponent(context, agent, promptInput, catalog)
        return scanReport to aiReply
    }

    suspend fun runResearchFirst(
        context: Context,
        topic: String,
        catalog: EccCatalog
    ): String {
        val matchingApis = try {
            val entries = PublicApisRepository.loadCatalog(context)
            PublicApisRepository.filter(
                entries = entries,
                query = topic,
                category = "All",
                noAuthOnly = false,
                httpsOnly = false
            ).take(5)
        } catch (_: Exception) {
            emptyList()
        }

        val apiContext = if (matchingApis.isNotEmpty()) {
            buildString {
                appendLine("Related APIs in Baby Public APIs Catalog:")
                matchingApis.forEach { api ->
                    appendLine("- ${api.api} (${api.category}, Auth=${api.auth}, HTTPS=${api.https}): ${api.description} [${api.link}]")
                }
            }
        } else {
            ""
        }

        if (!GeminiKeyManager(context).hasApiKey()) {
            return buildString {
                appendLine("Gemini API key not configured. Add your key in Settings for full Research-First AI synthesis.")
                if (apiContext.isNotBlank()) {
                    appendLine()
                    append(apiContext)
                }
            }.trim()
        }

        val researchAgent = catalog.agents.find { it.id == "docs-lookup" } ?: catalog.agents.first()
        val request = buildString {
            appendLine("Research-First Brief Request: $topic")
            appendLine("Provide: (1) Official library/API recommendations, (2) Auth/Permission/Platform requirements, (3) Known pitfalls & edge cases, (4) Minimal verified code pattern, (5) Pre-coding verification checklist.")
            if (apiContext.isNotBlank()) {
                appendLine()
                append(apiContext)
            }
        }
        return runComponent(context, researchAgent, request, catalog)
    }

    /**
     * Checks if a ChatActivity user message begins with an ECC slash command
     * (e.g., /plan, /tdd, /code-review, /security-scan, /verify, /build-fix, /research, /learn).
     * Returns an enriched prompt ready for Gemini 3.8 Flash, or null for normal chat messages.
     */
    suspend fun enrichSlashCommandIfPresent(context: Context, rawMessage: String): String? {
        val trimmed = rawMessage.trim()
        if (!trimmed.startsWith("/")) return null

        val parts = trimmed.split(Regex("\\s+"), limit = 2)
        val cmdToken = parts[0].lowercase()
        val argText = parts.getOrNull(1).orEmpty().ifBlank { "Assist based on active project context." }

        val catalog = EccRepository.loadCatalog(context)
        val matched = catalog.commands.find {
            it.command.equals(cmdToken, ignoreCase = true) ||
                (cmdToken == "/security" && it.command == "/security-scan") ||
                (cmdToken == "/review" && it.command == "/code-review")
        } ?: return null

        val extra = if (matched.command == "/security-scan" || matched.command == "/code-review") {
            val scan = EccSecurityScanner.scan(argText)
            "Local AgentShield Scan Summary: ${scan.summary}"
        } else {
            null
        }

        return buildAgentPrompt(context, matched, argText, catalog, extra)
    }

    enum class ReviewMode(val label: String) {
        CODE_REVIEW("Code Review"),
        SECURITY_AUDIT("Security Audit (AgentShield)"),
        DEBUG_BUILD_ERROR("Debug Build / Stack Trace")
    }
}
