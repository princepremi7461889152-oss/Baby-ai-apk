package com.blurr.voice.openhands

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val CardDark = Color(0xFF1C1C1E)
private val CardElevated = Color(0xFF252529)
private val TealAccent = Color(0xFF03DAC5)
private val PeachAccent = Color(0xFFFFBC87)
private val MutedGray = Color(0xFF9A9AA0)
private val ErrorRed = Color(0xFFCF6679)

@Composable
fun AgentStreamTab(
    state: OpenHandsUiState,
    onSelectAgent: (OpenHandsAgentType) -> Unit,
    onRunTask: (String) -> Unit,
    onStopTask: () -> Unit,
    onNewConversation: () -> Unit,
    onQuickSandboxCmd: (String) -> Unit
) {
    var taskInput by remember { mutableStateOf("") }
    var quickCmd by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("oh_event_stream_list"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            // Agent Selector Pills
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OpenHandsAgentType.values().forEach { agent ->
                    val selected = state.conversation.agentType == agent
                    FilterChip(
                        selected = selected,
                        onClick = { onSelectAgent(agent) },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("oh_agent_${agent.id.lowercase()}"),
                        label = {
                            Text(
                                text = agent.displayName,
                                fontSize = 13.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TealAccent.copy(alpha = 0.20f),
                            selectedLabelColor = TealAccent,
                            containerColor = CardDark,
                            labelColor = Color.White
                        )
                    )
                }
                OutlinedButton(
                    onClick = onNewConversation,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("oh_new_conversation_button"),
                    border = BorderStroke(1.dp, MutedGray.copy(alpha = 0.4f))
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "New session", modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New", color = Color.White, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = state.conversation.agentType.subtitle,
                color = MutedGray,
                fontSize = 12.sp
            )
        }

        item {
            // Task Input Card
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    OutlinedTextField(
                        value = taskInput,
                        onValueChange = { taskInput = it },
                        placeholder = {
                            Text("Assign engineering task (e.g. Inspect Main.kt, add tests, & scan)...", color = MutedGray, fontSize = 13.sp)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("oh_task_input"),
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = TealAccent,
                            unfocusedBorderColor = MutedGray.copy(alpha = 0.4f)
                        )
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onRunTask(taskInput) },
                            enabled = !state.isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("oh_run_agent_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = PeachAccent,
                                contentColor = Color.Black
                            )
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Run OpenHands agent", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Run ${state.conversation.agentType.displayName}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                        if (state.isBusy) {
                            OutlinedButton(
                                onClick = onStopTask,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("oh_stop_agent_button"),
                                border = BorderStroke(1.dp, ErrorRed)
                            ) {
                                Icon(Icons.Filled.Stop, contentDescription = "Stop agent", tint = ErrorRed)
                            }
                        }
                    }
                }
            }
        }

        item {
            // Quick Sandbox Command Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = quickCmd,
                    onValueChange = { quickCmd = it },
                    placeholder = { Text("Quick sandbox cmd: ls, cat Main.kt, scan, api weather", color = MutedGray, fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("oh_quick_cmd_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = MutedGray.copy(alpha = 0.35f)
                    )
                )
                Button(
                    onClick = {
                        if (quickCmd.isNotBlank()) {
                            onQuickSandboxCmd(quickCmd)
                            quickCmd = ""
                        }
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("oh_quick_cmd_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = CardElevated, contentColor = TealAccent)
                ) {
                    Icon(Icons.Filled.Terminal, contentDescription = "Execute sandbox command", modifier = Modifier.size(18.dp))
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Event Stream (${state.conversation.events.size} events)",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier.testTag("oh_event_stream_header")
                )
                Text(
                    text = "State: ${state.conversation.state.label}",
                    color = if (state.conversation.state == OpenHandsAgentState.ERROR) ErrorRed else TealAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        if (state.conversation.events.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("No Event-Stream activity yet", color = Color.White, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Assign a task to CodeActAgent (uses your Gemini 3.8 Flash key from Settings) or run sandbox commands like `ls`, `cat Main.kt`, `scan`, or `api weather` right now.",
                            color = MutedGray,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else {
            items(state.conversation.events, key = { it.id }) { event ->
                EventStreamCard(event)
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun EventStreamCard(event: OpenHandsEvent) {
    val badgeColor = when {
        event.isError || event.actionType == OpenHandsActionType.ERROR -> ErrorRed
        event.source == OpenHandsEventSource.USER -> PeachAccent
        event.actionType == OpenHandsActionType.FINISH -> Color(0xFF4CAF50)
        event.source == OpenHandsEventSource.ENVIRONMENT -> Color(0xFF64B5F6)
        else -> TealAccent
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#${event.stepIndex} • ${event.source.label} • ${event.actionType.label}",
                    color = badgeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = when {
                        event.isError -> Icons.Filled.ErrorOutline
                        event.actionType == OpenHandsActionType.BROWSE_URL -> Icons.Filled.Language
                        event.actionType == OpenHandsActionType.RUN_CMD -> Icons.Filled.Terminal
                        event.actionType == OpenHandsActionType.FINISH -> Icons.Filled.CheckCircle
                        else -> Icons.Filled.Code
                    },
                    contentDescription = event.actionType.label,
                    tint = badgeColor,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(event.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                text = event.content,
                color = Color(0xFFD8D8DD),
                fontSize = 12.sp,
                fontFamily = if (event.source == OpenHandsEventSource.ENVIRONMENT || event.actionType == OpenHandsActionType.RUN_CMD) {
                    FontFamily.Monospace
                } else {
                    FontFamily.Default
                }
            )
        }
    }
}

@Composable
fun WorkspaceTab(
    state: OpenHandsUiState,
    onRefreshFiles: () -> Unit,
    onOpenFile: (String) -> Unit,
    onSaveFile: (String, String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onRunCommand: (String) -> Unit
) {
    var filePathInput by remember(state.selectedFilePath) {
        mutableStateOf(state.selectedFilePath ?: "notes.kt")
    }
    var fileContentInput by remember(state.selectedFilePath, state.selectedFileContent) {
        mutableStateOf(state.selectedFileContent)
    }
    var cmdInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Local Sandbox Workspace", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Confined to app sandbox: /${state.conversation.workspaceId}", color = MutedGray, fontSize = 12.sp)
            }
            IconButton(
                onClick = onRefreshFiles,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("oh_refresh_workspace_button")
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh workspace", tint = TealAccent)
            }
        }

        // Workspace files list
        Card(
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Files (${state.workspaceFiles.size})", color = PeachAccent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (state.workspaceFiles.isEmpty()) {
                    Text("No files yet. Create one below.", color = MutedGray, fontSize = 12.sp)
                } else {
                    state.workspaceFiles.forEach { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (file.relativePath == state.selectedFilePath) TealAccent.copy(alpha = 0.14f) else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable(enabled = !file.isDirectory) { onOpenFile(file.relativePath) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = if (file.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                                    contentDescription = file.relativePath,
                                    tint = if (file.isDirectory) PeachAccent else TealAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${file.relativePath} (${file.sizeBytes} B)",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!file.isDirectory) {
                                IconButton(
                                    onClick = { onDeleteFile(file.relativePath) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete ${file.relativePath}", tint = MutedGray, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // File Editor
        Card(
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sandbox File Editor", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                OutlinedTextField(
                    value = filePathInput,
                    onValueChange = { filePathInput = it },
                    label = { Text("Relative path (e.g. Demo.kt)", color = MutedGray) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("oh_workspace_file_path"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = MutedGray.copy(alpha = 0.4f)
                    )
                )
                OutlinedTextField(
                    value = fileContentInput,
                    onValueChange = { fileContentInput = it },
                    label = { Text("File contents", color = MutedGray) },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("oh_workspace_file_content"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = TealAccent,
                        unfocusedBorderColor = MutedGray.copy(alpha = 0.4f)
                    )
                )
                Button(
                    onClick = { onSaveFile(filePathInput, fileContentInput) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("oh_save_file_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent, contentColor = Color.Black)
                ) {
                    Icon(Icons.Filled.Save, contentDescription = "Save file to workspace", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save File to Sandbox", fontWeight = FontWeight.SemiBold)
                }
            }
        }

        // Sandbox Terminal
        Card(
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sandbox Terminal", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = cmdInput,
                        onValueChange = { cmdInput = it },
                        placeholder = { Text("ls | cat Main.kt | grep TODO | scan | api news", color = MutedGray, fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("oh_sandbox_cmd_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = PeachAccent,
                            unfocusedBorderColor = MutedGray.copy(alpha = 0.4f)
                        )
                    )
                    Button(
                        onClick = {
                            if (cmdInput.isNotBlank()) {
                                onRunCommand(cmdInput)
                                cmdInput = ""
                            }
                        },
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("oh_run_cmd_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = PeachAccent, contentColor = Color.Black)
                    ) {
                        Text("Run", fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    text = state.lastCommandOutput,
                    color = Color(0xFFD0F0EA),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF121215), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                        .testTag("oh_terminal_output")
                )
            }
        }
    }
}

@Composable
fun MicroagentsTab(
    state: OpenHandsUiState,
    onQueryChange: (String) -> Unit,
    onToggleMicroagent: (String) -> Unit,
    onAddMicroagent: (String, String, String) -> Unit,
    onDeleteCustomMicroagent: (String) -> Unit
) {
    var showAddForm by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newKeywords by remember { mutableStateOf("") }
    var newInstructions by remember { mutableStateOf("") }

    val filtered = remember(state.microagents, state.microagentQuery) {
        OpenHandsRepository.filterMicroagents(state.microagents, state.microagentQuery)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.microagentQuery,
                onValueChange = onQueryChange,
                placeholder = { Text("Search OpenHands microagents & triggers...", color = MutedGray, fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("oh_microagent_search"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = TealAccent,
                    unfocusedBorderColor = MutedGray.copy(alpha = 0.4f)
                )
            )
            Button(
                onClick = { showAddForm = !showAddForm },
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("oh_toggle_add_microagent_button"),
                colors = ButtonDefaults.buttonColors(containerColor = PeachAccent, contentColor = Color.Black)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add microagent", modifier = Modifier.size(18.dp))
            }
        }

        if (showAddForm) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CardElevated),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("New Keyword-Triggered Microagent", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Name", color = MutedGray) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("oh_new_micro_name"),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    OutlinedTextField(
                        value = newKeywords,
                        onValueChange = { newKeywords = it },
                        label = { Text("Trigger keywords (comma-separated)", color = MutedGray) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("oh_new_micro_keywords"),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    OutlinedTextField(
                        value = newInstructions,
                        onValueChange = { newInstructions = it },
                        label = { Text("Microagent instructions", color = MutedGray) },
                        minLines = 2,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("oh_new_micro_instructions"),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Button(
                        onClick = {
                            if (newName.isNotBlank() && newInstructions.isNotBlank()) {
                                onAddMicroagent(newName, newKeywords, newInstructions)
                                newName = ""
                                newKeywords = ""
                                newInstructions = ""
                                showAddForm = false
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("oh_save_microagent_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = TealAccent, contentColor = Color.Black)
                    ) {
                        Text("Save Microagent", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .testTag("oh_microagent_list"),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filtered, key = { it.id }) { micro ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(micro.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(micro.sourcePath, color = TealAccent, fontSize = 11.sp)
                            }
                            if (micro.isCustom) {
                                IconButton(
                                    onClick = { onDeleteCustomMicroagent(micro.id) },
                                    modifier = Modifier.minimumInteractiveComponentSize()
                                ) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete ${micro.name}", tint = MutedGray)
                                }
                            }
                            Switch(
                                checked = micro.enabled,
                                onCheckedChange = { onToggleMicroagent(micro.id) },
                                colors = SwitchDefaults.colors(checkedThumbColor = TealAccent)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(micro.description, color = Color(0xFFD0D0D5), fontSize = 12.sp)
                        if (micro.triggerKeywords.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Triggers: ${micro.triggerKeywords.joinToString(", ")}",
                                color = PeachAccent,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun ServerAndHistoryTab(
    state: OpenHandsUiState,
    onTestRemoteServer: (String, String) -> Unit,
    onDispatchRemote: (String) -> Unit,
    onResumeConversation: (OpenHandsConversation) -> Unit,
    onDeleteConversation: (String) -> Unit
) {
    var serverUrl by remember(state.serverConfig.serverUrl) { mutableStateOf(state.serverConfig.serverUrl) }
    var serverToken by remember(state.serverConfig.apiToken) { mutableStateOf(state.serverConfig.apiToken) }
    var remotePrompt by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Optional Remote OpenHands Agent Server Card
        Card(
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Remote OpenHands Agent Server (Optional)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    "Connect to a self-hosted `openhands-agent-server` or OpenHands Cloud API. Leave blank to keep running locally on-device.",
                    color = MutedGray,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL (e.g. https://your-openhands-host:3000)", color = MutedGray) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("oh_server_url_input"),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                OutlinedTextField(
                    value = serverToken,
                    onValueChange = { serverToken = it },
                    label = { Text("API Token (optional, stored locally only)", color = MutedGray) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("oh_server_token_input"),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                )
                Text(
                    text = "Status: ${state.serverConfig.lastStatus}",
                    color = TealAccent,
                    fontSize = 12.sp
                )
                Button(
                    onClick = { onTestRemoteServer(serverUrl, serverToken) },
                    enabled = !state.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("oh_test_server_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = TealAccent, contentColor = Color.Black)
                ) {
                    Text("Save & Connect Server", fontWeight = FontWeight.SemiBold)
                }

                if (serverUrl.isNotBlank()) {
                    OutlinedTextField(
                        value = remotePrompt,
                        onValueChange = { remotePrompt = it },
                        label = { Text("Dispatch prompt to remote OpenHands server", color = MutedGray) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White)
                    )
                    Button(
                        onClick = { onDispatchRemote(remotePrompt) },
                        enabled = !state.isBusy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize(),
                        colors = ButtonDefaults.buttonColors(containerColor = PeachAccent, contentColor = Color.Black)
                    ) {
                        Text("Create Remote Conversation", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        if (state.remoteConversations.isNotEmpty()) {
            Text("Remote Server Conversations", color = PeachAccent, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            state.remoteConversations.forEach { rc ->
                Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(rc.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("ID: ${rc.id} • Status: ${rc.status}", color = MutedGray, fontSize = 11.sp)
                    }
                }
            }
        }

        Text("Local Event-Stream Sessions (${state.conversationsHistory.size})", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        if (state.conversationsHistory.isEmpty()) {
            Text("No saved local sessions yet.", color = MutedGray, fontSize = 12.sp)
        } else {
            state.conversationsHistory.forEach { conv ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onResumeConversation(conv) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(conv.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(
                                "${conv.agentType.displayName} • ${conv.events.size} events • ${conv.state.label}",
                                color = TealAccent,
                                fontSize = 11.sp
                            )
                        }
                        IconButton(
                            onClick = { onDeleteConversation(conv.id) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete conversation", tint = MutedGray)
                        }
                    }
                }
            }
        }
    }
}
