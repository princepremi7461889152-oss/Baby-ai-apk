package com.blurr.voice.openhands

import android.content.Context
import android.os.Looper
import android.widget.TextView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.blurr.voice.R
import com.blurr.voice.SettingsActivity
import com.blurr.voice.utilities.GeminiKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OpenHandsToolkitTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<OpenHandsActivity>()

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("OpenHandsPrefs", Context.MODE_PRIVATE).edit().clear().commit()
        File(context.filesDir, "openhands_workspace/test-ws").deleteRecursively()
        GeminiKeyManager(context).clearApiKey()
    }

    @Test
    fun sandbox_supportsFileOpsCommandsScanApiAndBlocksTraversalAndReadOnlyMutations() = runBlocking {
        val ws = "test-ws"
        val files = OpenHandsSandbox.listWorkspaceFiles(context, ws)
        assertTrue(files.any { it.relativePath == "README.md" })
        assertTrue(files.any { it.relativePath == "Main.kt" })

        val writeRes = OpenHandsSandbox.writeFile(
            context = context,
            relativePath = "src/Calculator.kt",
            content = "class Calculator {\n    fun add(a: Int, b: Int) = a + b\n}",
            workspaceId = ws,
            readOnly = false
        )
        assertFalse(writeRes.isError)

        val readRes = OpenHandsSandbox.readFile(context, "src/Calculator.kt", ws)
        assertFalse(readRes.isError)
        assertTrue(readRes.output.contains("fun add"))

        val editRes = OpenHandsSandbox.editFile(
            context = context,
            relativePath = "src/Calculator.kt",
            oldSnippet = "a + b",
            newSnippet = "a.plus(b)",
            workspaceId = ws,
            readOnly = false
        )
        assertFalse(editRes.isError)
        assertTrue(OpenHandsSandbox.readFile(context, "src/Calculator.kt", ws).output.contains("a.plus(b)"))

        val grepRes = OpenHandsSandbox.grepWorkspace(context, "plus", ws)
        assertTrue(grepRes.output.contains("src/Calculator.kt"))

        // ReadOnly guardrail
        val blockedWrite = OpenHandsSandbox.writeFile(context, "blocked.txt", "nope", ws, readOnly = true)
        assertTrue(blockedWrite.isError)
        assertTrue(blockedWrite.title.contains("ReadOnly"))

        val blockedRm = OpenHandsSandbox.executeCommand(context, "rm Main.kt", ws, readOnly = true)
        assertTrue(blockedRm.isError)

        // Traversal protection
        val traversal = OpenHandsSandbox.readFile(context, "../../etc/passwd", ws)
        assertTrue(traversal.isError)

        // Security scan & Public APIs integration commands
        val scanClean = OpenHandsSandbox.executeCommand(context, "scan src/Calculator.kt", ws)
        assertFalse(scanClean.isError)
        assertTrue(scanClean.output.contains("PASS"))

        val apiSearch = OpenHandsSandbox.executeCommand(context, "api weather", ws)
        assertFalse(apiSearch.isError)
        assertTrue(apiSearch.output.contains("http", ignoreCase = true))
    }

    @Test
    fun repository_loadsMicroagentsTriggersKeywordsParsesGitHubTreeAndPersistsConversations() {
        val bundled = OpenHandsRepository.loadBundledMicroagents(context)
        assertTrue(bundled.size >= 7)

        val custom = OpenHandsRepository.addCustomMicroagent(
            context = context,
            name = "Compose Polish",
            keywordsCsv = "compose, animation",
            instructions = "Use Material 3 and 48dp targets."
        )
        val triggered = OpenHandsRepository.findTriggeredMicroagents(
            "Build a compose screen with smooth animation",
            OpenHandsRepository.getMicroagents(context)
        )
        assertTrue(triggered.any { it.id == custom.id })

        val toggled = OpenHandsRepository.toggleMicroagent(context, custom.id)
        assertFalse(toggled.first { it.id == custom.id }.enabled)

        val sampleTree = """
            {
              "tree": [
                {"path": "microagents/security.md", "type": "blob"},
                {"path": "openhands/agenthub/codeact_agent/README.md", "type": "blob"},
                {"path": "openhands/agenthub/browsing_agent/README.md", "type": "blob"}
              ]
            }
        """.trimIndent()
        val (discovered, count) = OpenHandsRepository.parseGitHubTree(bundled, sampleTree)
        assertEquals(3, count)
        assertTrue(discovered.any { it.sourcePath == "microagents/security.md" })
        assertTrue(discovered.any { it.sourcePath == "openhands/agenthub/codeact_agent/README.md" })

        val conv = OpenHandsConversation(
            id = "c1",
            title = "Fix Calculator",
            workspaceId = "test-ws",
            agentType = OpenHandsAgentType.CODE_ACT,
            state = OpenHandsAgentState.FINISHED,
            createdAtMs = 100L,
            updatedAtMs = 200L
        )
        OpenHandsRepository.saveConversation(context, conv)
        assertTrue(OpenHandsRepository.getConversations(context).any { it.id == "c1" })
        OpenHandsRepository.deleteConversation(context, "c1")
        assertFalse(OpenHandsRepository.getConversations(context).any { it.id == "c1" })
    }

    @Test
    fun agentRunner_parsesActionsExecutesEventStreamAndEnrichesChatCommands() = runBlocking {
        val rawJson = """
            ```json
            {
              "thought": "Creating helper file in sandbox",
              "action": "FILE_WRITE",
              "path": "Helper.kt",
              "content": "object Helper { fun ping() = \"pong\" }"
            }
            ```
        """.trimIndent()

        val cmd = OpenHandsAgentRunner.parseActionCommand(rawJson)
        assertEquals("FILE_WRITE", cmd.action)
        assertEquals("Helper.kt", cmd.path)

        val conv = OpenHandsConversation(
            id = "run-1",
            title = "Write helper",
            workspaceId = "test-ws",
            agentType = OpenHandsAgentType.CODE_ACT
        )
        val outcome = OpenHandsAgentRunner.applyCommandToSandbox(context, conv, cmd, stepNumber = 1)
        assertFalse(outcome.finished)
        assertEquals(3, outcome.generatedEvents.size)
        assertEquals(OpenHandsActionType.THINK, outcome.generatedEvents[0].actionType)
        assertEquals(OpenHandsActionType.FILE_WRITE, outcome.generatedEvents[1].actionType)
        assertEquals(OpenHandsActionType.OBSERVATION, outcome.generatedEvents[2].actionType)
        assertFalse(outcome.generatedEvents[2].isError)

        val enriched = OpenHandsAgentRunner.enrichChatCommandIfPresent(context, "/openhands Fix kotlin bug")
        assertNotNull(enriched)
        assertTrue(enriched!!.contains("OPENHANDS CODEACT ASSISTANT"))
        assertTrue(enriched.contains("Fix kotlin bug"))

        assertNull(OpenHandsAgentRunner.enrichChatCommandIfPresent(context, "Just a regular chat message"))
    }

    @Test
    fun serverClient_normalizesUrlsAndParsesRemoteConversations() {
        assertEquals("https://oh.example.com", OpenHandsServerClient.normalizeBaseUrl("oh.example.com/"))
        assertEquals("http://localhost:3000", OpenHandsServerClient.normalizeBaseUrl("http://localhost:3000/"))

        val payload = """
            {
              "results": [
                {"conversation_id": "abc-1", "title": "Patch bug", "status": "RUNNING"},
                {"id": "abc-2", "title": "Add API client", "status": "STOPPED"}
              ]
            }
        """.trimIndent()
        val parsed = OpenHandsServerClient.parseRemoteConversations(payload)
        assertEquals(2, parsed.size)
        assertEquals("abc-1", parsed[0].id)
        assertEquals("Patch bug", parsed[0].title)
    }

    @Test
    fun settingsNavigation_andComposeOpenHandsScreenTabsWorkEndToEnd() {
        val settingsController = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val settingsActivity = settingsController.get()
        val btnOpenOh = settingsActivity.findViewById<TextView>(R.id.btnOpenOpenHands)
        assertNotNull(btnOpenOh)
        btnOpenOh.performClick()

        val startedIntent = shadowOf(settingsActivity).nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(OpenHandsActivity::class.java.name, startedIntent.component?.className)

        // Verify Compose OpenHandsActivity UI
        composeRule.onNodeWithText("OpenHands Workspace").assertIsDisplayed()
        composeRule.onNodeWithTag("oh_agent_readonlyagent").performClick()
        composeRule.onNodeWithTag("oh_status_banner").assertIsDisplayed()

        // Execute sandbox command in Agent Stream tab (scroll LazyColumn item into view first)
        composeRule.onNodeWithTag("oh_event_stream_list")
            .performScrollToNode(hasTestTag("oh_quick_cmd_input"))
        composeRule.onNodeWithTag("oh_quick_cmd_input").performTextInput("ls")
        composeRule.onNodeWithTag("oh_quick_cmd_button").performClick()
        composeRule.waitUntil(timeoutMillis = 5000) {
            shadowOf(Looper.getMainLooper()).idle()
            OpenHandsRepository.getConversations(context).any { conv ->
                conv.events.any { it.title.contains("Sandbox Cmd: ls") }
            }
        }
        composeRule.onNodeWithTag("oh_event_stream_list")
            .performScrollToNode(hasText("Sandbox Cmd: ls", substring = true))
        composeRule.onNodeWithText("Sandbox Cmd: ls", substring = true).assertIsDisplayed()

        // Switch to Workspace tab
        composeRule.onNodeWithTag("oh_tab_workspace").performClick()
        composeRule.onNodeWithText("Local Sandbox Workspace").assertIsDisplayed()

        // Switch to Microagents tab
        composeRule.onNodeWithTag("oh_tab_microagents").performClick()
        composeRule.onNodeWithTag("oh_microagent_search").assertIsDisplayed()
        composeRule.onNodeWithText("Android & Kotlin Engineer").assertIsDisplayed()

        // Switch to Server & History tab
        composeRule.onNodeWithTag("oh_tab_server_sessions").performScrollTo().performClick()
        composeRule.onNodeWithText("Remote OpenHands Agent Server (Optional)").assertIsDisplayed()
    }
}
