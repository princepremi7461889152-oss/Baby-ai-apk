package com.blurr.voice.openhands

import android.content.Context
import com.blurr.voice.api.GeminiApi
import com.blurr.voice.api.TextPart
import com.blurr.voice.utilities.GeminiKeyManager
import kotlinx.serialization.json.Json
import java.util.UUID

data class OpenHandsStepOutcome(
    val command: OpenHandsActionCommand,
    val generatedEvents: List<OpenHandsEvent>,
    val finished: Boolean
)

/**
 * Implements OpenHands' Event-Stream Perception-Action loop on Android:
 * Context + Triggered Microagents + Workspace Files + Event Stream -> Gemini 3.8 Flash Action JSON
 * -> Local Sandbox Execution -> Environment Observation Event.
 */
object OpenHandsAgentRunner {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun buildAgentStepPrompt(
        context: Context,
        conversation: OpenHandsConversation,
        taskGoal: String,
        microagents: List<OpenHandsMicroagent>
    ): String {
        val triggered = OpenHandsRepository.findTriggeredMicroagents(taskGoal, microagents)
        val workspaceFiles = OpenHandsSandbox.listWorkspaceFiles(context, conversation.workspaceId)
        val recentEvents = conversation.events.takeLast(10)

        val allowedActions = when (conversation.agentType) {
            OpenHandsAgentType.CODE_ACT ->
                "FILE_READ, FILE_WRITE, FILE_EDIT, LIST_FILES, GREP, RUN_CMD, BROWSE_URL, THINK, FINISH"
            OpenHandsAgentType.READ_ONLY ->
                "FILE_READ, LIST_FILES, GREP, BROWSE_URL, THINK, FINISH (READ-ONLY: NEVER use FILE_WRITE, FILE_EDIT, touch, mkdir, or rm)"
            OpenHandsAgentType.BROWSING ->
                "BROWSE_URL, FILE_READ, LIST_FILES, GREP, RUN_CMD (api/scan only), THINK, FINISH"
        }

        return buildString {
            appendLine("=== OPENHANDS SOFTWARE AGENT RUNTIME ===")
            appendLine("Active Agent: ${conversation.agentType.displayName} (${conversation.agentType.subtitle})")
            appendLine("Allowed Actions: $allowedActions")
            appendLine("Workspace ID: ${conversation.workspaceId}")
            appendLine()
            if (triggered.isNotEmpty()) {
                appendLine("<openhands_microagents>")
                triggered.forEach { m ->
                    appendLine("- ${m.name} (${m.sourcePath}): ${m.instructions}")
                }
                appendLine("</openhands_microagents>")
                appendLine()
            }
            appendLine("<workspace_files>")
            if (workspaceFiles.isEmpty()) {
                appendLine("(empty workspace)")
            } else {
                workspaceFiles.forEach { f ->
                    appendLine(if (f.isDirectory) "- [DIR] ${f.relativePath}/" else "- ${f.relativePath} (${f.sizeBytes} bytes)")
                }
            }
            appendLine("</workspace_files>")
            appendLine()
            if (recentEvents.isNotEmpty()) {
                appendLine("<event_stream_history>")
                recentEvents.forEach { ev ->
                    appendLine("[Step ${ev.stepIndex} | ${ev.source.label} | ${ev.actionType.label}] ${ev.title}: ${ev.content.take(700)}")
                }
                appendLine("</event_stream_history>")
                appendLine()
            }
            appendLine("User Task: $taskGoal")
            appendLine()
            appendLine("Respond with ONLY a single JSON object (no extra prose outside JSON) matching this schema:")
            appendLine(
                """
                {
                  "thought": "concise reasoning about what to inspect, build, or verify next",
                  "action": "FILE_READ | FILE_WRITE | FILE_EDIT | LIST_FILES | GREP | RUN_CMD | BROWSE_URL | FINISH",
                  "path": "relative/path.kt (when reading/writing/editing)",
                  "content": "full file content (for FILE_WRITE)",
                  "old_str": "exact snippet to replace (for FILE_EDIT)",
                  "new_str": "replacement snippet (for FILE_EDIT)",
                  "command": "sandbox command e.g. ls, grep TODO, scan Main.kt, api weather (for RUN_CMD / GREP)",
                  "url": "https://... (for BROWSE_URL)",
                  "message": "final engineering summary when action is FINISH"
                }
                """.trimIndent()
            )
        }.trim()
    }

    fun parseActionCommand(rawResponse: String): OpenHandsActionCommand {
        val cleaned = rawResponse
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start >= 0 && end > start) {
            val candidate = cleaned.substring(start, end + 1)
            try {
                return json.decodeFromString<OpenHandsActionCommand>(candidate)
            } catch (_: Exception) {
            }
        }
        return OpenHandsActionCommand(
            thought = "Completed response",
            action = "FINISH",
            message = rawResponse.trim().ifEmpty { "No output produced." }
        )
    }

    suspend fun executeActionStep(
        context: Context,
        conversation: OpenHandsConversation,
        taskGoal: String,
        microagents: List<OpenHandsMicroagent>,
        stepNumber: Int
    ): Result<OpenHandsStepOutcome> {
        if (!GeminiKeyManager(context).hasApiKey()) {
            return Result.failure(
                IllegalStateException("Gemini API key not configured. Add your key in Settings -> Gemini 3.8 Flash API Key.")
            )
        }

        val prompt = buildAgentStepPrompt(context, conversation, taskGoal, microagents)
        val raw = GeminiApi.generateContent(
            chat = listOf("user" to listOf(TextPart(prompt))),
            context = context.applicationContext
        ) ?: return Result.failure(
            IllegalStateException("Unable to reach Gemini 3.8 Flash right now. Check your network and API key.")
        )

        val cmd = parseActionCommand(raw)
        return Result.success(
            applyCommandToSandbox(
                context = context,
                conversation = conversation,
                cmd = cmd,
                stepNumber = stepNumber
            )
        )
    }

    suspend fun applyCommandToSandbox(
        context: Context,
        conversation: OpenHandsConversation,
        cmd: OpenHandsActionCommand,
        stepNumber: Int
    ): OpenHandsStepOutcome {
        val now = System.currentTimeMillis()
        val events = mutableListOf<OpenHandsEvent>()
        val isReadOnly = conversation.agentType.isReadOnly

        if (cmd.thought.isNotBlank()) {
            events.add(
                OpenHandsEvent(
                    id = UUID.randomUUID().toString().take(8),
                    stepIndex = stepNumber,
                    timestampMs = now,
                    source = OpenHandsEventSource.AGENT,
                    actionType = OpenHandsActionType.THINK,
                    title = "${conversation.agentType.displayName} Thought",
                    content = cmd.thought.trim()
                )
            )
        }

        val normalizedAction = cmd.action.trim().uppercase()
        var finished = false

        when (normalizedAction) {
            "FILE_READ" -> {
                events.add(
                    OpenHandsEvent(
                        id = UUID.randomUUID().toString().take(8),
                        stepIndex = stepNumber,
                        timestampMs = now + 1,
                        source = OpenHandsEventSource.AGENT,
                        actionType = OpenHandsActionType.FILE_READ,
                        title = "FileRead: ${cmd.path}",
                        content = "Reading ${cmd.path}",
                        targetPath = cmd.path
                    )
                )
                val obs = OpenHandsSandbox.readFile(context, cmd.path, conversation.workspaceId)
                events.add(observationEvent(stepNumber, now + 2, obs))
            }
            "FILE_WRITE" -> {
                events.add(
                    OpenHandsEvent(
                        id = UUID.randomUUID().toString().take(8),
                        stepIndex = stepNumber,
                        timestampMs = now + 1,
                        source = OpenHandsEventSource.AGENT,
                        actionType = OpenHandsActionType.FILE_WRITE,
                        title = "FileWrite: ${cmd.path}",
                        content = cmd.content.take(500),
                        targetPath = cmd.path
                    )
                )
                val obs = OpenHandsSandbox.writeFile(context, cmd.path, cmd.content, conversation.workspaceId, isReadOnly)
                events.add(observationEvent(stepNumber, now + 2, obs))
            }
            "FILE_EDIT" -> {
                events.add(
                    OpenHandsEvent(
                        id = UUID.randomUUID().toString().take(8),
                        stepIndex = stepNumber,
                        timestampMs = now + 1,
                        source = OpenHandsEventSource.AGENT,
                        actionType = OpenHandsActionType.FILE_EDIT,
                        title = "FileEdit: ${cmd.path}",
                        content = "Replacing snippet in ${cmd.path}",
                        targetPath = cmd.path
                    )
                )
                val obs = OpenHandsSandbox.editFile(
                    context = context,
                    relativePath = cmd.path,
                    oldSnippet = cmd.old_str,
                    newSnippet = cmd.new_str,
                    workspaceId = conversation.workspaceId,
                    readOnly = isReadOnly
                )
                events.add(observationEvent(stepNumber, now + 2, obs))
            }
            "LIST_FILES" -> {
                events.add(
                    OpenHandsEvent(
                        id = UUID.randomUUID().toString().take(8),
                        stepIndex = stepNumber,
                        timestampMs = now + 1,
                        source = OpenHandsEventSource.AGENT,
                        actionType = OpenHandsActionType.LIST_FILES,
                        title = "ListFiles",
                        content = "Listing workspace files"
                    )
                )
                val obs = OpenHandsSandbox.executeCommand(context, "ls", conversation.workspaceId, isReadOnly)
                events.add(observationEvent(stepNumber, now + 2, obs))
            }
            "GREP" -> {
                val pattern = cmd.command.ifBlank { cmd.path }
                events.add(
                    OpenHandsEvent(
                        id = UUID.randomUUID().toString().take(8),
                        stepIndex = stepNumber,
                        timestampMs = now + 1,
                        source = OpenHandsEventSource.AGENT,
                        actionType = OpenHandsActionType.GREP,
                        title = "Grep: $pattern",
                        content = "Searching workspace for '$pattern'"
                    )
                )
                val obs = OpenHandsSandbox.grepWorkspace(context, pattern, conversation.workspaceId)
                events.add(observationEvent(stepNumber, now + 2, obs))
            }
            "RUN_CMD" -> {
                events.add(
                    OpenHandsEvent(
                        id = UUID.randomUUID().toString().take(8),
                        stepIndex = stepNumber,
                        timestampMs = now + 1,
                        source = OpenHandsEventSource.AGENT,
                        actionType = OpenHandsActionType.RUN_CMD,
                        title = "CmdRun: ${cmd.command}",
                        content = "$ ${cmd.command}"
                    )
                )
                val obs = OpenHandsSandbox.executeCommand(context, cmd.command, conversation.workspaceId, isReadOnly)
                events.add(observationEvent(stepNumber, now + 2, obs))
            }
            "BROWSE_URL" -> {
                events.add(
                    OpenHandsEvent(
                        id = UUID.randomUUID().toString().take(8),
                        stepIndex = stepNumber,
                        timestampMs = now + 1,
                        source = OpenHandsEventSource.AGENT,
                        actionType = OpenHandsActionType.BROWSE_URL,
                        title = "BrowseURL: ${cmd.url}",
                        content = "Fetching ${cmd.url}",
                        targetPath = cmd.url
                    )
                )
                val obs = OpenHandsSandbox.browseUrl(cmd.url)
                events.add(observationEvent(stepNumber, now + 2, obs))
            }
            else -> {
                finished = true
                events.add(
                    OpenHandsEvent(
                        id = UUID.randomUUID().toString().take(8),
                        stepIndex = stepNumber,
                        timestampMs = now + 1,
                        source = OpenHandsEventSource.AGENT,
                        actionType = OpenHandsActionType.FINISH,
                        title = "AgentFinish",
                        content = cmd.message.ifBlank { cmd.thought.ifBlank { "Task completed." } }
                    )
                )
            }
        }

        return OpenHandsStepOutcome(
            command = cmd,
            generatedEvents = events,
            finished = finished
        )
    }

    private fun observationEvent(stepNumber: Int, timestamp: Long, res: SandboxExecutionResult): OpenHandsEvent {
        return OpenHandsEvent(
            id = UUID.randomUUID().toString().take(8),
            stepIndex = stepNumber,
            timestampMs = timestamp,
            source = OpenHandsEventSource.ENVIRONMENT,
            actionType = if (res.isError) OpenHandsActionType.ERROR else OpenHandsActionType.OBSERVATION,
            title = res.title,
            content = res.output,
            targetPath = res.targetPath,
            isError = res.isError
        )
    }

    /**
     * Enriches `/openhands <task>` or `/codeact <task>` chat commands in ChatActivity
     * with OpenHands microagent instructions & current sandbox workspace snapshot.
     */
    fun enrichChatCommandIfPresent(context: Context, rawMessage: String): String? {
        val trimmed = rawMessage.trim()
        if (!trimmed.startsWith("/openhands", ignoreCase = true) &&
            !trimmed.startsWith("/codeact", ignoreCase = true)
        ) {
            return null
        }

        val parts = trimmed.split(Regex("\\s+"), limit = 2)
        val task = parts.getOrNull(1).orEmpty().ifBlank { "Summarize the current workspace and propose next engineering steps." }
        val microagents = OpenHandsRepository.getMicroagents(context)
        val triggered = OpenHandsRepository.findTriggeredMicroagents(task, microagents)
        val files = OpenHandsSandbox.listWorkspaceFiles(context)

        return buildString {
            appendLine("=== OPENHANDS CODEACT ASSISTANT ===")
            appendLine("Triggered OpenHands Microagents:")
            triggered.forEach { m ->
                appendLine("- ${m.name}: ${m.instructions}")
            }
            appendLine()
            appendLine("Current OpenHands Sandbox Files:")
            if (files.isEmpty()) {
                appendLine("- (empty workspace)")
            } else {
                files.forEach { f ->
                    appendLine("- ${f.relativePath} (${f.sizeBytes} bytes)")
                }
            }
            appendLine()
            appendLine("Task: $task")
        }.trim()
    }
}
