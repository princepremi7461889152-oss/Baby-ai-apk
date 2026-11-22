package com.blurr.voice.openhands

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class OpenHandsTab(val label: String) {
    AGENT_STREAM("Agent Stream"),
    WORKSPACE("Workspace"),
    MICROAGENTS("Microagents"),
    SERVER_SESSIONS("Server & History")
}

data class OpenHandsUiState(
    val activeTab: OpenHandsTab = OpenHandsTab.AGENT_STREAM,
    val conversation: OpenHandsConversation = OpenHandsConversation(
        id = "conv-default",
        title = "New OpenHands Task",
        workspaceId = "default-workspace",
        agentType = OpenHandsAgentType.CODE_ACT,
        state = OpenHandsAgentState.IDLE
    ),
    val conversationsHistory: List<OpenHandsConversation> = emptyList(),
    val workspaceFiles: List<WorkspaceFileInfo> = emptyList(),
    val selectedFilePath: String? = null,
    val selectedFileContent: String = "",
    val lastCommandOutput: String = "Sandbox ready. Try `ls`, `cat Main.kt`, `scan Main.kt`, or `api weather`.",
    val microagents: List<OpenHandsMicroagent> = emptyList(),
    val microagentQuery: String = "",
    val serverConfig: OpenHandsServerConfig = OpenHandsServerConfig(),
    val remoteConversations: List<RemoteConversationSummary> = emptyList(),
    val isBusy: Boolean = false,
    val isSyncingRepo: Boolean = false,
    val statusBanner: String? = null
)

class OpenHandsViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val _uiState = MutableStateFlow(OpenHandsUiState())
    val uiState: StateFlow<OpenHandsUiState> = _uiState.asStateFlow()

    private var activeRunJob: Job? = null

    init {
        loadInitialState()
    }

    fun loadInitialState() {
        val microagents = OpenHandsRepository.getMicroagents(context)
        val history = OpenHandsRepository.getConversations(context)
        val server = OpenHandsRepository.getServerConfig(context)
        val initialConv = history.firstOrNull() ?: OpenHandsConversation(
            id = UUID.randomUUID().toString().take(8),
            title = "OpenHands Workspace Session",
            workspaceId = "default-workspace",
            agentType = OpenHandsAgentType.CODE_ACT,
            state = OpenHandsAgentState.IDLE,
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis()
        )
        val files = OpenHandsSandbox.listWorkspaceFiles(context, initialConv.workspaceId)
        val firstFile = files.firstOrNull { !it.isDirectory }?.relativePath
        val firstContent = firstFile?.let {
            OpenHandsSandbox.readFile(context, it, initialConv.workspaceId).output
        }.orEmpty()

        _uiState.update {
            it.copy(
                conversation = initialConv,
                conversationsHistory = history,
                workspaceFiles = files,
                selectedFilePath = firstFile,
                selectedFileContent = firstContent,
                microagents = microagents,
                serverConfig = server
            )
        }
    }

    fun selectTab(tab: OpenHandsTab) {
        if (tab == OpenHandsTab.WORKSPACE) {
            refreshWorkspaceFiles()
        }
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun setAgentType(agentType: OpenHandsAgentType) {
        val updated = _uiState.value.conversation.copy(agentType = agentType)
        _uiState.update {
            it.copy(
                conversation = updated,
                statusBanner = "Active agent switched to ${agentType.displayName}"
            )
        }
    }

    fun startNewConversation() {
        activeRunJob?.cancel()
        val fresh = OpenHandsConversation(
            id = UUID.randomUUID().toString().take(8),
            title = "New OpenHands Task",
            workspaceId = _uiState.value.conversation.workspaceId,
            agentType = _uiState.value.conversation.agentType,
            state = OpenHandsAgentState.IDLE,
            createdAtMs = System.currentTimeMillis(),
            updatedAtMs = System.currentTimeMillis()
        )
        _uiState.update {
            it.copy(
                conversation = fresh,
                isBusy = false,
                statusBanner = "Started a new OpenHands Event-Stream conversation."
            )
        }
    }

    fun openConversation(conv: OpenHandsConversation) {
        activeRunJob?.cancel()
        _uiState.update {
            it.copy(
                conversation = conv,
                activeTab = OpenHandsTab.AGENT_STREAM,
                isBusy = false,
                statusBanner = "Resumed '${conv.title}'"
            )
        }
        refreshWorkspaceFiles()
    }

    fun deleteConversation(id: String) {
        OpenHandsRepository.deleteConversation(context, id)
        val history = OpenHandsRepository.getConversations(context)
        _uiState.update { it.copy(conversationsHistory = history) }
    }

    fun runAutonomousTask(goalInput: String, maxSteps: Int = 4) {
        val goal = goalInput.trim()
        if (goal.isEmpty()) {
            _uiState.update { it.copy(statusBanner = "Enter a coding or research goal first.") }
            return
        }

        activeRunJob?.cancel()
        val now = System.currentTimeMillis()
        val current = _uiState.value.conversation
        val nextStepStart = (current.events.maxOfOrNull { it.stepIndex } ?: 0) + 1
        val userEvent = OpenHandsEvent(
            id = UUID.randomUUID().toString().take(8),
            stepIndex = nextStepStart,
            timestampMs = now,
            source = OpenHandsEventSource.USER,
            actionType = OpenHandsActionType.MESSAGE,
            title = "User Instruction",
            content = goal
        )
        var runningConv = current.copy(
            title = if (current.events.isEmpty()) goal.take(48) else current.title,
            state = OpenHandsAgentState.RUNNING,
            updatedAtMs = now,
            events = current.events + userEvent
        )
        persistAndExposeConversation(runningConv, isBusy = true, banner = "Running ${runningConv.agentType.displayName}...")

        activeRunJob = viewModelScope.launch {
            val microagents = OpenHandsRepository.getMicroagents(context)
            var currentStep = nextStepStart
            var reachedFinish = false

            for (iteration in 1..maxSteps) {
                val res = OpenHandsAgentRunner.executeActionStep(
                    context = context,
                    conversation = runningConv,
                    taskGoal = goal,
                    microagents = microagents,
                    stepNumber = currentStep
                )
                if (res.isFailure) {
                    val errMsg = res.exceptionOrNull()?.message ?: "Agent step failed."
                    val errEvent = OpenHandsEvent(
                        id = UUID.randomUUID().toString().take(8),
                        stepIndex = currentStep,
                        timestampMs = System.currentTimeMillis(),
                        source = OpenHandsEventSource.ENVIRONMENT,
                        actionType = OpenHandsActionType.ERROR,
                        title = "Agent Runtime Error",
                        content = errMsg,
                        isError = true
                    )
                    runningConv = runningConv.copy(
                        state = OpenHandsAgentState.ERROR,
                        updatedAtMs = System.currentTimeMillis(),
                        events = runningConv.events + errEvent
                    )
                    persistAndExposeConversation(runningConv, isBusy = false, banner = errMsg)
                    refreshWorkspaceFiles()
                    return@launch
                }

                val outcome = res.getOrThrow()
                runningConv = runningConv.copy(
                    state = if (outcome.finished) OpenHandsAgentState.FINISHED else OpenHandsAgentState.RUNNING,
                    updatedAtMs = System.currentTimeMillis(),
                    events = runningConv.events + outcome.generatedEvents
                )
                persistAndExposeConversation(
                    runningConv,
                    isBusy = !outcome.finished && iteration < maxSteps,
                    banner = if (outcome.finished) "Agent finished task." else "Executed step $iteration/$maxSteps..."
                )
                refreshWorkspaceFiles()

                if (outcome.finished) {
                    reachedFinish = true
                    break
                }
                currentStep++
            }

            if (!reachedFinish) {
                runningConv = runningConv.copy(
                    state = OpenHandsAgentState.AWAITING_USER,
                    updatedAtMs = System.currentTimeMillis()
                )
                persistAndExposeConversation(
                    runningConv,
                    isBusy = false,
                    banner = "Paused after $maxSteps steps. Send a follow-up to continue."
                )
            }
        }
    }

    fun stopAgentRun() {
        activeRunJob?.cancel()
        val paused = _uiState.value.conversation.copy(
            state = OpenHandsAgentState.PAUSED,
            updatedAtMs = System.currentTimeMillis()
        )
        persistAndExposeConversation(paused, isBusy = false, banner = "Agent run paused by user.")
    }

    fun runManualSandboxCommand(rawCommand: String) {
        val cmd = rawCommand.trim()
        if (cmd.isEmpty()) return
        viewModelScope.launch {
            val conv = _uiState.value.conversation
            val step = (conv.events.maxOfOrNull { it.stepIndex } ?: 0) + 1
            val obs = OpenHandsSandbox.executeCommand(
                context = context,
                rawCommand = cmd,
                workspaceId = conv.workspaceId,
                readOnly = conv.agentType.isReadOnly
            )
            val actionEv = OpenHandsEvent(
                id = UUID.randomUUID().toString().take(8),
                stepIndex = step,
                timestampMs = System.currentTimeMillis(),
                source = OpenHandsEventSource.USER,
                actionType = OpenHandsActionType.RUN_CMD,
                title = "Sandbox Cmd: $cmd",
                content = "$ $cmd"
            )
            val obsEv = OpenHandsEvent(
                id = UUID.randomUUID().toString().take(8),
                stepIndex = step,
                timestampMs = System.currentTimeMillis() + 1,
                source = OpenHandsEventSource.ENVIRONMENT,
                actionType = if (obs.isError) OpenHandsActionType.ERROR else OpenHandsActionType.OBSERVATION,
                title = obs.title,
                content = obs.output,
                targetPath = obs.targetPath,
                isError = obs.isError
            )
            val updated = conv.copy(
                updatedAtMs = System.currentTimeMillis(),
                events = conv.events + actionEv + obsEv
            )
            persistAndExposeConversation(updated, isBusy = false, banner = obs.title)
            _uiState.update { it.copy(lastCommandOutput = "${obs.title}\n${obs.output}") }
            refreshWorkspaceFiles()
        }
    }

    fun refreshWorkspaceFiles() {
        val wsId = _uiState.value.conversation.workspaceId
        val files = OpenHandsSandbox.listWorkspaceFiles(context, wsId)
        _uiState.update { it.copy(workspaceFiles = files) }
    }

    fun openWorkspaceFile(relativePath: String) {
        val wsId = _uiState.value.conversation.workspaceId
        val root = OpenHandsSandbox.getWorkspaceRoot(context, wsId)
        val raw = runCatching {
            val file = java.io.File(root, relativePath)
            if (file.exists() && !file.isDirectory) file.readText() else ""
        }.getOrDefault("")
        _uiState.update {
            it.copy(
                selectedFilePath = relativePath,
                selectedFileContent = raw,
                statusBanner = "Opened $relativePath"
            )
        }
    }

    fun saveWorkspaceFile(relativePath: String, content: String) {
        val conv = _uiState.value.conversation
        val res = OpenHandsSandbox.writeFile(
            context = context,
            relativePath = relativePath,
            content = content,
            workspaceId = conv.workspaceId,
            readOnly = false
        )
        refreshWorkspaceFiles()
        _uiState.update {
            it.copy(
                selectedFilePath = if (!res.isError) relativePath else it.selectedFilePath,
                selectedFileContent = if (!res.isError) content else it.selectedFileContent,
                lastCommandOutput = res.output,
                statusBanner = res.title
            )
        }
    }

    fun deleteWorkspaceFile(relativePath: String) {
        val conv = _uiState.value.conversation
        val res = OpenHandsSandbox.deleteFile(context, relativePath, conv.workspaceId, readOnly = false)
        refreshWorkspaceFiles()
        _uiState.update {
            it.copy(
                selectedFilePath = if (it.selectedFilePath == relativePath) null else it.selectedFilePath,
                selectedFileContent = if (it.selectedFilePath == relativePath) "" else it.selectedFileContent,
                lastCommandOutput = res.output,
                statusBanner = res.title
            )
        }
    }

    fun updateMicroagentQuery(query: String) {
        _uiState.update { it.copy(microagentQuery = query) }
    }

    fun toggleMicroagent(id: String) {
        val updated = OpenHandsRepository.toggleMicroagent(context, id)
        _uiState.update { it.copy(microagents = updated) }
    }

    fun addCustomMicroagent(name: String, keywordsCsv: String, instructions: String) {
        if (name.isBlank() || instructions.isBlank()) return
        OpenHandsRepository.addCustomMicroagent(context, name, keywordsCsv, instructions)
        _uiState.update {
            it.copy(
                microagents = OpenHandsRepository.getMicroagents(context),
                statusBanner = "Added microagent '$name'"
            )
        }
    }

    fun deleteCustomMicroagent(id: String) {
        OpenHandsRepository.deleteCustomMicroagent(context, id)
        _uiState.update {
            it.copy(
                microagents = OpenHandsRepository.getMicroagents(context),
                statusBanner = "Removed custom microagent"
            )
        }
    }

    fun syncOpenHandsRepo() {
        _uiState.update { it.copy(isSyncingRepo = true, statusBanner = "Syncing OpenHands/OpenHands...") }
        viewModelScope.launch {
            val res = OpenHandsRepository.syncFromGitHub(context)
            res.onSuccess { summary ->
                _uiState.update {
                    it.copy(
                        isSyncingRepo = false,
                        microagents = OpenHandsRepository.getMicroagents(context),
                        statusBanner = "Synced OpenHands/OpenHands: ${summary.totalMicroagents} microagents (${summary.remoteFilesDiscovered} remote modules)"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isSyncingRepo = false,
                        statusBanner = "Sync failed: ${err.message ?: "network error"}"
                    )
                }
            }
        }
    }

    fun saveAndTestRemoteServer(url: String, token: String) {
        val draft = OpenHandsServerConfig(serverUrl = url.trim(), apiToken = token.trim(), lastStatus = "Testing...")
        OpenHandsRepository.saveServerConfig(context, draft)
        _uiState.update { it.copy(serverConfig = draft, isBusy = true) }

        viewModelScope.launch {
            val health = OpenHandsServerClient.checkHealth(url, token)
            val statusText = health.getOrElse { "Offline / Unreachable: ${it.message}" }
            val saved = draft.copy(lastStatus = statusText)
            OpenHandsRepository.saveServerConfig(context, saved)

            val remoteList = if (health.isSuccess) {
                OpenHandsServerClient.listRemoteConversations(url, token).getOrDefault(emptyList())
            } else {
                emptyList()
            }
            _uiState.update {
                it.copy(
                    serverConfig = saved,
                    remoteConversations = remoteList,
                    isBusy = false,
                    statusBanner = statusText
                )
            }
        }
    }

    fun dispatchRemoteConversation(prompt: String) {
        val cfg = _uiState.value.serverConfig
        if (prompt.isBlank() || cfg.serverUrl.isBlank()) {
            _uiState.update { it.copy(statusBanner = "Configure Server URL and enter a task prompt first.") }
            return
        }
        _uiState.update { it.copy(isBusy = true) }
        viewModelScope.launch {
            val res = OpenHandsServerClient.createRemoteConversation(cfg.serverUrl, cfg.apiToken, prompt)
            res.onSuccess { created ->
                _uiState.update {
                    it.copy(
                        remoteConversations = listOf(created) + it.remoteConversations,
                        isBusy = false,
                        statusBanner = "Started remote OpenHands conversation ${created.id}"
                    )
                }
            }.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isBusy = false,
                        statusBanner = "Remote dispatch failed: ${err.message}"
                    )
                }
            }
        }
    }

    private fun persistAndExposeConversation(
        conversation: OpenHandsConversation,
        isBusy: Boolean,
        banner: String?
    ) {
        OpenHandsRepository.saveConversation(context, conversation)
        val history = OpenHandsRepository.getConversations(context)
        _uiState.update {
            it.copy(
                conversation = conversation,
                conversationsHistory = history,
                isBusy = isBusy,
                statusBanner = banner
            )
        }
    }
}
