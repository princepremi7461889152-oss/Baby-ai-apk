package com.blurr.voice.openhands

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class OpenHandsActivity : ComponentActivity() {

    private val viewModel: OpenHandsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            OpenHandsTheme {
                OpenHandsRootScreen(
                    state = state,
                    onBack = { finish() },
                    onSyncRepo = { viewModel.syncOpenHandsRepo() },
                    onSelectTab = { viewModel.selectTab(it) },
                    onSelectAgent = { viewModel.setAgentType(it) },
                    onRunTask = { viewModel.runAutonomousTask(it) },
                    onStopTask = { viewModel.stopAgentRun() },
                    onNewConversation = { viewModel.startNewConversation() },
                    onRunSandboxCommand = { viewModel.runManualSandboxCommand(it) },
                    onRefreshFiles = { viewModel.refreshWorkspaceFiles() },
                    onOpenFile = { viewModel.openWorkspaceFile(it) },
                    onSaveFile = { path, body -> viewModel.saveWorkspaceFile(path, body) },
                    onDeleteFile = { viewModel.deleteWorkspaceFile(it) },
                    onMicroagentQuery = { viewModel.updateMicroagentQuery(it) },
                    onToggleMicroagent = { viewModel.toggleMicroagent(it) },
                    onAddMicroagent = { n, k, i -> viewModel.addCustomMicroagent(n, k, i) },
                    onDeleteMicroagent = { viewModel.deleteCustomMicroagent(it) },
                    onTestServer = { url, token -> viewModel.saveAndTestRemoteServer(url, token) },
                    onDispatchRemote = { viewModel.dispatchRemoteConversation(it) },
                    onResumeConversation = { viewModel.openConversation(it) },
                    onDeleteConversation = { viewModel.deleteConversation(it) }
                )
            }
        }
    }
}

@Composable
private fun OpenHandsTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Color(0xFF03DAC5),
        secondary = Color(0xFFFFBC87),
        background = Color(0xFF101010),
        surface = Color(0xFF1C1C1E),
        onBackground = Color.White,
        onSurface = Color.White
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun OpenHandsRootScreen(
    state: OpenHandsUiState,
    onBack: () -> Unit,
    onSyncRepo: () -> Unit,
    onSelectTab: (OpenHandsTab) -> Unit,
    onSelectAgent: (OpenHandsAgentType) -> Unit,
    onRunTask: (String) -> Unit,
    onStopTask: () -> Unit,
    onNewConversation: () -> Unit,
    onRunSandboxCommand: (String) -> Unit,
    onRefreshFiles: () -> Unit,
    onOpenFile: (String) -> Unit,
    onSaveFile: (String, String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onMicroagentQuery: (String) -> Unit,
    onToggleMicroagent: (String) -> Unit,
    onAddMicroagent: (String, String, String) -> Unit,
    onDeleteMicroagent: (String) -> Unit,
    onTestServer: (String, String) -> Unit,
    onDispatchRemote: (String) -> Unit,
    onResumeConversation: (OpenHandsConversation) -> Unit,
    onDeleteConversation: (String) -> Unit
) {
    Scaffold(
        containerColor = Color(0xFF101010),
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Top atmospheric header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF141B1E),
                                Color(0xFF191522),
                                Color(0xFF101010)
                            )
                        )
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("oh_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        Column {
                            Text(
                                text = "OpenHands Workspace",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "${state.microagents.count { it.enabled }} Microagents • ${state.workspaceFiles.size} Files • Gemini 3.8 Flash",
                                color = Color(0xFF03DAC5),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Button(
                        onClick = onSyncRepo,
                        enabled = !state.isSyncingRepo,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("oh_sync_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF252529),
                            contentColor = Color(0xFFFFBC87)
                        )
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = "Sync OpenHands repository", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (state.isSyncingRepo) "Syncing" else "Sync", fontSize = 12.sp)
                    }
                }
            }

            if (state.isBusy || state.isSyncingRepo) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF03DAC5),
                    trackColor = Color(0xFF1C1C1E)
                )
            }

            state.statusBanner?.let { banner ->
                Text(
                    text = banner,
                    color = Color(0xFFFFBC87),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF19191D))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("oh_status_banner")
                )
            }

            // Tab Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OpenHandsTab.values().forEach { tab ->
                    val selected = state.activeTab == tab
                    Button(
                        onClick = { onSelectTab(tab) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("oh_tab_${tab.name.lowercase()}"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) Color(0xFF03DAC5) else Color(0xFF1C1C1E),
                            contentColor = if (selected) Color.Black else Color.White
                        )
                    ) {
                        Text(tab.label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (state.activeTab) {
                    OpenHandsTab.AGENT_STREAM -> AgentStreamTab(
                        state = state,
                        onSelectAgent = onSelectAgent,
                        onRunTask = onRunTask,
                        onStopTask = onStopTask,
                        onNewConversation = onNewConversation,
                        onQuickSandboxCmd = onRunSandboxCommand
                    )
                    OpenHandsTab.WORKSPACE -> WorkspaceTab(
                        state = state,
                        onRefreshFiles = onRefreshFiles,
                        onOpenFile = onOpenFile,
                        onSaveFile = onSaveFile,
                        onDeleteFile = onDeleteFile,
                        onRunCommand = onRunSandboxCommand
                    )
                    OpenHandsTab.MICROAGENTS -> MicroagentsTab(
                        state = state,
                        onQueryChange = onMicroagentQuery,
                        onToggleMicroagent = onToggleMicroagent,
                        onAddMicroagent = onAddMicroagent,
                        onDeleteCustomMicroagent = onDeleteMicroagent
                    )
                    OpenHandsTab.SERVER_SESSIONS -> ServerAndHistoryTab(
                        state = state,
                        onTestRemoteServer = onTestServer,
                        onDispatchRemote = onDispatchRemote,
                        onResumeConversation = onResumeConversation,
                        onDeleteConversation = onDeleteConversation
                    )
                }
            }
        }
    }
}
