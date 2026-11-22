package com.blurr.voice.openhands

import kotlinx.serialization.Serializable

@Serializable
enum class OpenHandsAgentType(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val isReadOnly: Boolean
) {
    CODE_ACT(
        id = "CodeActAgent",
        displayName = "CodeActAgent",
        subtitle = "Full read/write/run perception-action coding agent inside the workspace sandbox",
        isReadOnly = false
    ),
    READ_ONLY(
        id = "ReadOnlyAgent",
        displayName = "ReadOnlyAgent",
        subtitle = "Safe plan & audit mode — reads files, searches, and analyzes without mutating files",
        isReadOnly = true
    ),
    BROWSING(
        id = "BrowsingAgent",
        displayName = "BrowsingAgent",
        subtitle = "Documentation & API explorer using live URL fetch + Public APIs catalog",
        isReadOnly = true
    )
}

@Serializable
enum class OpenHandsAgentState(val label: String) {
    IDLE("Idle"),
    RUNNING("Running"),
    PAUSED("Paused"),
    AWAITING_USER("Awaiting Input"),
    FINISHED("Finished"),
    ERROR("Error")
}

@Serializable
enum class OpenHandsEventSource(val label: String) {
    USER("User"),
    AGENT("Agent"),
    ENVIRONMENT("Runtime")
}

@Serializable
enum class OpenHandsActionType(val label: String) {
    MESSAGE("Message"),
    THINK("Thought"),
    FILE_WRITE("FileWrite"),
    FILE_EDIT("FileEdit"),
    FILE_READ("FileRead"),
    LIST_FILES("ListFiles"),
    GREP("Grep"),
    RUN_CMD("CmdRun"),
    BROWSE_URL("BrowseURL"),
    OBSERVATION("Observation"),
    FINISH("Finish"),
    ERROR("Error")
}

@Serializable
data class OpenHandsEvent(
    val id: String,
    val stepIndex: Int,
    val timestampMs: Long,
    val source: OpenHandsEventSource,
    val actionType: OpenHandsActionType,
    val title: String,
    val content: String,
    val targetPath: String? = null,
    val isError: Boolean = false
)

@Serializable
data class OpenHandsMicroagent(
    val id: String,
    val name: String,
    val triggerKeywords: List<String> = emptyList(),
    val description: String,
    val instructions: String,
    val sourcePath: String = ".openhands/microagents/custom.md",
    val enabled: Boolean = true,
    val isCustom: Boolean = false
)

@Serializable
data class OpenHandsConversation(
    val id: String,
    val title: String,
    val workspaceId: String = "default-workspace",
    val agentType: OpenHandsAgentType = OpenHandsAgentType.CODE_ACT,
    val state: OpenHandsAgentState = OpenHandsAgentState.IDLE,
    val createdAtMs: Long = 0L,
    val updatedAtMs: Long = 0L,
    val events: List<OpenHandsEvent> = emptyList()
)

@Serializable
data class OpenHandsServerConfig(
    val serverUrl: String = "",
    val apiToken: String = "",
    val lastStatus: String = "Not connected (using Local On-Device Sandbox)"
)

@Serializable
data class OpenHandsActionCommand(
    val thought: String = "",
    val action: String = "FINISH",
    val path: String = "",
    val content: String = "",
    val old_str: String = "",
    val new_str: String = "",
    val command: String = "",
    val url: String = "",
    val message: String = ""
)

data class WorkspaceFileInfo(
    val relativePath: String,
    val sizeBytes: Long,
    val updatedAtMs: Long,
    val isDirectory: Boolean
)

data class OpenHandsSyncSummary(
    val totalMicroagents: Int,
    val remoteFilesDiscovered: Int
)
