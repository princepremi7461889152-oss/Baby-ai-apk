package com.blurr.voice.ecc

import android.content.Context
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EccToolkitTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("EccToolkitPrefs", Context.MODE_PRIVATE).edit().clear().commit()
        GeminiKeyManager(context).clearApiKey()
    }

    @Test
    fun repository_loadsBundledCatalogAndMergesGitHubTree() = runBlocking {
        val catalog = EccRepository.loadCatalog(context)
        assertTrue(catalog.agents.size >= 9)
        assertTrue(catalog.skills.size >= 8)
        assertTrue(catalog.commands.size >= 8)
        assertTrue(catalog.rules.size >= 6)

        val onlyAgents = EccRepository.filterComponents(catalog, query = "", typeFilter = EccComponentType.AGENT)
        assertTrue(onlyAgents.all { it.type == EccComponentType.AGENT })

        val tddMatches = EccRepository.filterComponents(catalog, query = "/tdd", typeFilter = null)
        assertTrue(tddMatches.any { it.command == "/tdd" })

        val sampleTreeJson = """
            {
              "tree": [
                {"path": "agents/planner.md", "type": "blob"},
                {"path": "agents/kotlin-reviewer.md", "type": "blob"},
                {"path": "skills/compose-performance/SKILL.md", "type": "blob"},
                {"path": "commands/checkpoint.md", "type": "blob"}
              ]
            }
        """.trimIndent()

        val merged = EccRepository.mergeGitHubTreeWithCatalog(catalog, sampleTreeJson)
        assertTrue(merged.agents.any { it.sourcePath == "agents/kotlin-reviewer.md" && it.title == "Kotlin Reviewer" })
        assertTrue(merged.skills.any { it.sourcePath == "skills/compose-performance/SKILL.md" })
        assertTrue(merged.commands.any { it.command == "/checkpoint" })
        assertEquals(1, merged.agents.count { it.sourcePath == "agents/planner.md" })
    }

    @Test
    fun securityScanner_detectsCriticalVulnerabilitiesAndPassesCleanCode() {
        val cleanCode = """
            class SafeRepo {
                fun sum(a: Int, b: Int): Int = a + b
            }
        """.trimIndent()
        val cleanReport = EccSecurityScanner.scan(cleanCode)
        assertEquals(100, cleanReport.score)
        assertTrue(cleanReport.passedGate)
        assertTrue(cleanReport.findings.isEmpty())

        val vulnerableCode = """
            val key = "AIzaSyTestFakeLeakedKey1234567890"
            fun fetch(db: SQLiteDatabase, id: String) {
                db.rawQuery("SELECT * FROM users WHERE id=" + id, null)
            }
            GlobalScope.launch {
                val url = "http://api.example.com/v1"
                Log.d("Leak", "apiKey=" + key)
            }
        """.trimIndent()
        val badReport = EccSecurityScanner.scan(vulnerableCode)
        assertFalse(badReport.passedGate)
        assertTrue(badReport.score < 75)
        assertTrue(badReport.findings.any { it.ruleId == "SEC-001" && it.severity == EccSeverity.CRITICAL })
        assertTrue(badReport.findings.any { it.ruleId == "SEC-005" && it.severity == EccSeverity.HIGH })
        assertTrue(badReport.findings.any { it.ruleId == "SEC-009" })
        assertTrue(badReport.findings.any { it.ruleId == "QUAL-001" })
    }

    @Test
    fun memoryStore_persistsRulesInstinctsContextAndCompaction() {
        val catalog = EccRepository.loadBundledCatalog(context)

        EccMemoryStore.setContextMode(context, EccContextMode.REVIEW)
        assertEquals(EccContextMode.REVIEW, EccMemoryStore.getProfile(context).contextMode)

        val toggled = EccMemoryStore.toggleRule(context, catalog.rules, "rule-testing")
        assertFalse(toggled.first { it.id == "rule-testing" }.enabled)

        val customRule = EccMemoryStore.addCustomRule(
            context,
            catalog.rules,
            "Verify Offline State",
            "project/offline",
            "All screens must degrade cleanly when offline."
        )
        assertTrue(EccMemoryStore.getRules(context, catalog.rules).any { it.id == customRule.id })

        val instinct = EccMemoryStore.addMemory(
            context,
            "Robolectric Main Looper",
            "Always idle main Looper after Handler.post in state manager tests.",
            EccMemoryKind.PITFALL
        )
        assertTrue(EccMemoryStore.getMemories(context).any { it.id == instinct.id })

        val brief = EccMemoryStore.buildContextBrief(context, catalog.rules, maxChars = 600)
        assertTrue(brief.length <= 600)
        assertTrue(brief.contains("Review Mode"))
        assertTrue(brief.contains("Verify Offline State"))

        EccMemoryStore.deleteCustomRule(context, catalog.rules, customRule.id)
        assertFalse(EccMemoryStore.getRules(context, catalog.rules).any { it.id == customRule.id })

        EccMemoryStore.deleteMemory(context, instinct.id)
        assertFalse(EccMemoryStore.getMemories(context).any { it.id == instinct.id })
    }

    @Test
    fun workflowEngine_handlesStagesSlashCommandsAndResearchOfflineFallback() = runBlocking {
        val catalog = EccRepository.loadBundledCatalog(context)

        val prompt = EccWorkflowEngine.buildWorkflowStagePrompt(
            context = context,
            stage = EccWorkflowStage.IMPLEMENT,
            goal = "Add offline cache for weather API",
            previousOutputs = mapOf(
                EccWorkflowStage.PLAN.name to "Phase 1: WeatherCache class",
                EccWorkflowStage.TEST.name to "Test stale cache expiration"
            ),
            catalog = catalog
        )
        assertTrue(prompt.contains("Stage 1: Plan"))
        assertTrue(prompt.contains("Stage 2: Test"))
        assertTrue(prompt.contains("Add offline cache for weather API"))

        val (reviewOut, scanReport) = EccWorkflowEngine.runWorkflowStage(
            context = context,
            stage = EccWorkflowStage.REVIEW,
            goal = "Review cache",
            previousOutputs = mapOf(
                EccWorkflowStage.IMPLEMENT.name to """val secret = "AIzaSyTestFakeLeakedKey1234567890""""
            ),
            catalog = catalog
        )
        assertNotNull(scanReport)
        assertFalse(scanReport!!.passedGate)
        assertTrue(reviewOut.contains("Settings"))

        val researchOut = EccWorkflowEngine.runResearchFirst(context, "weather", catalog)
        assertTrue(researchOut.contains("Gemini API key not configured"))
        assertTrue(researchOut.contains("Public APIs Catalog"))

        val slashEnriched = EccWorkflowEngine.enrichSlashCommandIfPresent(context, "/plan Build habit tracker")
        assertNotNull(slashEnriched)
        assertTrue(slashEnriched!!.contains("/plan"))
        assertTrue(slashEnriched.contains("Build habit tracker"))

        assertNull(EccWorkflowEngine.enrichSlashCommandIfPresent(context, "Normal chat message"))
    }

    @Test
    fun eccToolkitActivity_andSettingsNavigationWorkEndToEnd() {
        val settingsController = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        val settingsActivity = settingsController.get()
        val btnOpenEcc = settingsActivity.findViewById<TextView>(R.id.btnOpenEccToolkit)
        assertNotNull(btnOpenEcc)
        btnOpenEcc.performClick()

        val startedIntent = shadowOf(settingsActivity).nextStartedActivity
        assertNotNull(startedIntent)
        assertEquals(EccToolkitActivity::class.java.name, startedIntent.component?.className)

        val eccController = Robolectric.buildActivity(EccToolkitActivity::class.java).setup()
        val eccActivity = eccController.get()
        shadowOf(Looper.getMainLooper()).idle()

        val subtitle = eccActivity.findViewById<TextView>(R.id.tvEccSubtitle)
        assertTrue(subtitle.text.toString().contains("Agents"))

        // Advance Workflow stage from Plan -> Test
        val nextStageBtn = eccActivity.findViewById<Button>(R.id.btnNextWorkflowStage)
        nextStageBtn.performClick()
        val stageInfo = eccActivity.findViewById<TextView>(R.id.tvWorkflowStageInfo)
        assertTrue(stageInfo.text.toString().contains("Stage 2/5: Test"))

        // Switch to Agents & Skills tab
        eccActivity.findViewById<Button>(R.id.tabEccAgents).performClick()
        assertEquals(View.VISIBLE, eccActivity.findViewById<View>(R.id.panelEccAgents).visibility)

        // Switch to Review & Scan tab, load sample, run deterministic scan
        eccActivity.findViewById<Button>(R.id.tabEccReview).performClick()
        assertEquals(View.VISIBLE, eccActivity.findViewById<View>(R.id.panelEccReview).visibility)
        eccActivity.findViewById<Button>(R.id.btnLoadSampleCode).performClick()
        eccActivity.findViewById<TextView>(R.id.btnRunSecurityScan).performClick()

        val scanSummary = eccActivity.findViewById<TextView>(R.id.tvScanSummary).text.toString()
        val reviewOutput = eccActivity.findViewById<TextView>(R.id.tvReviewOutput).text.toString()
        assertTrue(scanSummary.contains("BLOCKED"))
        assertTrue(reviewOutput.contains("SEC-001"))

        // Switch to Rules & Context tab
        eccActivity.findViewById<Button>(R.id.tabEccRulesMemory).performClick()
        assertEquals(View.VISIBLE, eccActivity.findViewById<View>(R.id.panelEccRulesMemory).visibility)
        assertTrue(eccActivity.findViewById<LinearLayout>(R.id.containerEccRules).childCount >= 6)

        // Switch to Research tab & verify offline fallback output
        eccActivity.findViewById<Button>(R.id.tabEccResearch).performClick()
        assertEquals(View.VISIBLE, eccActivity.findViewById<View>(R.id.panelEccResearch).visibility)
        eccActivity.findViewById<EditText>(R.id.editResearchTopic).setText("weather")
        eccActivity.findViewById<TextView>(R.id.btnRunResearchLookup).performClick()
        shadowOf(Looper.getMainLooper()).idle()

        val researchOut = eccActivity.findViewById<TextView>(R.id.tvResearchOutput).text.toString()
        assertTrue(researchOut.contains("Public APIs"))
    }
}
