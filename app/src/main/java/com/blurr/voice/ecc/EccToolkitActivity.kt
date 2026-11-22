package com.blurr.voice.ecc

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blurr.voice.R
import com.blurr.voice.publicapis.PublicApisActivity
import kotlinx.coroutines.launch
import java.util.UUID

class EccToolkitActivity : AppCompatActivity() {

    private enum class SectionTab {
        WORKFLOW, AGENTS, REVIEW, RULES_MEMORY, RESEARCH
    }

    private lateinit var tvSubtitle: TextView
    private lateinit var btnSyncEcc: Button
    private lateinit var progressEcc: ProgressBar

    private lateinit var tabButtons: Map<SectionTab, Button>
    private lateinit var tabPanels: Map<SectionTab, View>

    // Workflow views
    private lateinit var tvWorkflowStageInfo: TextView
    private lateinit var editWorkflowGoal: EditText
    private lateinit var btnRunWorkflowStage: TextView
    private lateinit var btnNextWorkflowStage: Button
    private lateinit var btnSaveWorkflowLearn: Button
    private lateinit var tvWorkflowOutputHeader: TextView
    private lateinit var tvWorkflowOutput: TextView
    private lateinit var stageButtons: Map<EccWorkflowStage, Button>

    // Agents & Skills views
    private lateinit var editSearchEcc: EditText
    private lateinit var recyclerEcc: RecyclerView
    private lateinit var filterAll: Button
    private lateinit var filterAgents: Button
    private lateinit var filterSkills: Button
    private lateinit var filterCommands: Button
    private lateinit var componentsAdapter: EccComponentsAdapter

    // Review & Security views
    private lateinit var editCodeReviewInput: EditText
    private lateinit var btnLoadSampleCode: Button
    private lateinit var btnRunSecurityScan: TextView
    private lateinit var btnRunAiCodeReview: Button
    private lateinit var btnRunDebugError: Button
    private lateinit var tvScanSummary: TextView
    private lateinit var tvReviewOutput: TextView

    // Rules & Memory views
    private lateinit var btnContextDev: Button
    private lateinit var btnContextReview: Button
    private lateinit var btnContextResearch: Button
    private lateinit var btnAddCustomRule: Button
    private lateinit var btnAddEccMemory: Button
    private lateinit var containerEccRules: LinearLayout
    private lateinit var containerEccMemories: LinearLayout
    private lateinit var tvContextBudgetInfo: TextView

    // Research views
    private lateinit var editResearchTopic: EditText
    private lateinit var btnRunResearchLookup: TextView
    private lateinit var btnOpenPublicApis: Button
    private lateinit var tvResearchOutput: TextView

    private var catalog: EccCatalog = EccCatalog()
    private var activeTab: SectionTab = SectionTab.WORKFLOW
    private var currentStage: EccWorkflowStage = EccWorkflowStage.PLAN
    private val stageOutputs = mutableMapOf<String, String>()
    private var currentFilterType: EccComponentType? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ecc_toolkit)

        bindViews()
        setupListeners()
        loadCatalogAndRefreshUi()
    }

    private fun bindViews() {
        findViewById<ImageButton>(R.id.btnBackEcc).setOnClickListener { finish() }
        tvSubtitle = findViewById(R.id.tvEccSubtitle)
        btnSyncEcc = findViewById(R.id.btnSyncEcc)
        progressEcc = findViewById(R.id.progressEcc)

        tabButtons = mapOf(
            SectionTab.WORKFLOW to findViewById(R.id.tabEccWorkflow),
            SectionTab.AGENTS to findViewById(R.id.tabEccAgents),
            SectionTab.REVIEW to findViewById(R.id.tabEccReview),
            SectionTab.RULES_MEMORY to findViewById(R.id.tabEccRulesMemory),
            SectionTab.RESEARCH to findViewById(R.id.tabEccResearch)
        )
        tabPanels = mapOf(
            SectionTab.WORKFLOW to findViewById<ScrollView>(R.id.panelEccWorkflow),
            SectionTab.AGENTS to findViewById<LinearLayout>(R.id.panelEccAgents),
            SectionTab.REVIEW to findViewById<ScrollView>(R.id.panelEccReview),
            SectionTab.RULES_MEMORY to findViewById<ScrollView>(R.id.panelEccRulesMemory),
            SectionTab.RESEARCH to findViewById<ScrollView>(R.id.panelEccResearch)
        )

        tvWorkflowStageInfo = findViewById(R.id.tvWorkflowStageInfo)
        editWorkflowGoal = findViewById(R.id.editWorkflowGoal)
        btnRunWorkflowStage = findViewById(R.id.btnRunWorkflowStage)
        btnNextWorkflowStage = findViewById(R.id.btnNextWorkflowStage)
        btnSaveWorkflowLearn = findViewById(R.id.btnSaveWorkflowLearn)
        tvWorkflowOutputHeader = findViewById(R.id.tvWorkflowOutputHeader)
        tvWorkflowOutput = findViewById(R.id.tvWorkflowOutput)
        stageButtons = mapOf(
            EccWorkflowStage.PLAN to findViewById(R.id.btnStagePlan),
            EccWorkflowStage.TEST to findViewById(R.id.btnStageTest),
            EccWorkflowStage.IMPLEMENT to findViewById(R.id.btnStageImplement),
            EccWorkflowStage.REVIEW to findViewById(R.id.btnStageReview),
            EccWorkflowStage.VERIFY to findViewById(R.id.btnStageVerify)
        )

        editSearchEcc = findViewById(R.id.editSearchEccComponents)
        recyclerEcc = findViewById(R.id.recyclerEccComponents)
        filterAll = findViewById(R.id.btnFilterEccAll)
        filterAgents = findViewById(R.id.btnFilterEccAgents)
        filterSkills = findViewById(R.id.btnFilterEccSkills)
        filterCommands = findViewById(R.id.btnFilterEccCommands)
        componentsAdapter = EccComponentsAdapter(emptyList()) { item ->
            showRunComponentDialog(item)
        }
        recyclerEcc.layoutManager = LinearLayoutManager(this)
        recyclerEcc.adapter = componentsAdapter

        editCodeReviewInput = findViewById(R.id.editCodeReviewInput)
        btnLoadSampleCode = findViewById(R.id.btnLoadSampleCode)
        btnRunSecurityScan = findViewById(R.id.btnRunSecurityScan)
        btnRunAiCodeReview = findViewById(R.id.btnRunAiCodeReview)
        btnRunDebugError = findViewById(R.id.btnRunDebugError)
        tvScanSummary = findViewById(R.id.tvScanSummary)
        tvReviewOutput = findViewById(R.id.tvReviewOutput)

        btnContextDev = findViewById(R.id.btnContextDev)
        btnContextReview = findViewById(R.id.btnContextReview)
        btnContextResearch = findViewById(R.id.btnContextResearch)
        btnAddCustomRule = findViewById(R.id.btnAddCustomRule)
        btnAddEccMemory = findViewById(R.id.btnAddEccMemory)
        containerEccRules = findViewById(R.id.containerEccRules)
        containerEccMemories = findViewById(R.id.containerEccMemories)
        tvContextBudgetInfo = findViewById(R.id.tvContextBudgetInfo)

        editResearchTopic = findViewById(R.id.editResearchTopic)
        btnRunResearchLookup = findViewById(R.id.btnRunResearchLookup)
        btnOpenPublicApis = findViewById(R.id.btnOpenPublicApisFromEcc)
        tvResearchOutput = findViewById(R.id.tvResearchOutput)
    }

    private fun setupListeners() {
        tabButtons.forEach { (tab, button) ->
            button.setOnClickListener { switchTab(tab) }
        }
        btnSyncEcc.setOnClickListener { syncFromGitHub() }

        stageButtons.forEach { (stage, button) ->
            button.setOnClickListener { selectWorkflowStage(stage) }
        }
        btnRunWorkflowStage.setOnClickListener { executeCurrentWorkflowStage() }
        btnNextWorkflowStage.setOnClickListener {
            val next = EccWorkflowStage.values().find { it.order == currentStage.order + 1 }
                ?: EccWorkflowStage.PLAN
            selectWorkflowStage(next)
        }
        btnSaveWorkflowLearn.setOnClickListener {
            val currentText = tvWorkflowOutput.text?.toString().orEmpty().trim()
            if (currentText.length < 20) {
                Toast.makeText(this, "Run a workflow stage first to save an instinct.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val title = "${currentStage.label}: ${editWorkflowGoal.text?.toString().orEmpty().take(36).ifBlank { "Workflow Insight" }}"
            EccMemoryStore.addMemory(this, title, currentText.take(280), EccMemoryKind.INSTINCT)
            renderRulesAndMemory()
            Toast.makeText(this, "Saved insight to ECC Persistent Memory (/learn).", Toast.LENGTH_SHORT).show()
        }

        editSearchEcc.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyComponentFilter()
            }
        })
        filterAll.setOnClickListener { setComponentTypeFilter(null) }
        filterAgents.setOnClickListener { setComponentTypeFilter(EccComponentType.AGENT) }
        filterSkills.setOnClickListener { setComponentTypeFilter(EccComponentType.SKILL) }
        filterCommands.setOnClickListener { setComponentTypeFilter(EccComponentType.COMMAND) }

        btnLoadSampleCode.setOnClickListener {
            editCodeReviewInput.setText(
                """
                val apiKey = "AIzaSyFakeHardcodedDemoKey1234567890"
                fun loadUser(db: SQLiteDatabase, userId: String) {
                    db.rawQuery("SELECT * FROM users WHERE id = " + userId, null)
                }
                GlobalScope.launch {
                    val url = "http://insecure.example.com/profile"
                    Log.d("Auth", "Loaded apiKey=" + apiKey)
                }
                """.trimIndent()
            )
        }
        btnRunSecurityScan.setOnClickListener { runOfflineSecurityScanOnly() }
        btnRunAiCodeReview.setOnClickListener { runAiReview(EccWorkflowEngine.ReviewMode.CODE_REVIEW) }
        btnRunDebugError.setOnClickListener { runAiReview(EccWorkflowEngine.ReviewMode.DEBUG_BUILD_ERROR) }

        btnContextDev.setOnClickListener { updateContextMode(EccContextMode.DEV) }
        btnContextReview.setOnClickListener { updateContextMode(EccContextMode.REVIEW) }
        btnContextResearch.setOnClickListener { updateContextMode(EccContextMode.RESEARCH) }
        btnAddCustomRule.setOnClickListener { showAddRuleDialog() }
        btnAddEccMemory.setOnClickListener { showAddMemoryDialog() }

        btnRunResearchLookup.setOnClickListener { executeResearchLookup() }
        btnOpenPublicApis.setOnClickListener {
            startActivity(Intent(this, PublicApisActivity::class.java))
        }
    }

    private fun loadCatalogAndRefreshUi() {
        lifecycleScope.launch {
            catalog = EccRepository.loadCatalog(this@EccToolkitActivity)
            if (isFinishing || isDestroyed) return@launch
            tvSubtitle.text = "${catalog.agents.size} Agents • ${catalog.skills.size} Skills • ${catalog.commands.size} Cmds • Gemini 3.8 Flash"
            switchTab(SectionTab.WORKFLOW)
            selectWorkflowStage(EccWorkflowStage.PLAN)
            setComponentTypeFilter(null)
            renderRulesAndMemory()
        }
    }

    private fun switchTab(tab: SectionTab) {
        activeTab = tab
        val activeColor = ContextCompat.getColor(this, R.color.accent)
        val inactiveColor = ContextCompat.getColor(this, R.color.white)
        tabButtons.forEach { (t, btn) ->
            btn.setTextColor(if (t == tab) activeColor else inactiveColor)
        }
        tabPanels.forEach { (t, panel) ->
            panel.visibility = if (t == tab) View.VISIBLE else View.GONE
        }
    }

    private fun selectWorkflowStage(stage: EccWorkflowStage) {
        currentStage = stage
        val activeColor = ContextCompat.getColor(this, R.color.accent)
        val inactiveColor = ContextCompat.getColor(this, R.color.white)
        stageButtons.forEach { (s, btn) ->
            btn.setTextColor(if (s == stage) activeColor else inactiveColor)
        }
        tvWorkflowStageInfo.text = "Stage ${stage.order}/5: ${stage.label} — ${stage.objective}"
        tvWorkflowOutputHeader.text = "Stage ${stage.order}: ${stage.label} Output"
        tvWorkflowOutput.text = stageOutputs[stage.name]
            ?: "Ready for ${stage.label} stage. Tap Run Current Stage to execute with ECC ${stage.agentId}."
    }

    private fun executeCurrentWorkflowStage() {
        val goal = editWorkflowGoal.text?.toString().orEmpty().trim()
        if (goal.isEmpty()) {
            Toast.makeText(this, "Enter a coding task or feature goal first.", Toast.LENGTH_SHORT).show()
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            val (output, scanReport) = EccWorkflowEngine.runWorkflowStage(
                context = this@EccToolkitActivity,
                stage = currentStage,
                goal = goal,
                previousOutputs = stageOutputs,
                catalog = catalog
            )
            if (isFinishing || isDestroyed) return@launch
            setBusy(false)

            val fullOutput = if (scanReport != null) {
                "${scanReport.summary}\n\n$output"
            } else {
                output
            }
            stageOutputs[currentStage.name] = fullOutput
            tvWorkflowOutput.text = fullOutput

            EccMemoryStore.saveSession(
                this@EccToolkitActivity,
                EccWorkflowSession(
                    id = UUID.randomUUID().toString().take(8),
                    goal = goal,
                    createdAtMs = System.currentTimeMillis(),
                    outputsByStage = stageOutputs.toMap(),
                    securityScore = scanReport?.score
                )
            )
        }
    }

    private fun setComponentTypeFilter(type: EccComponentType?) {
        currentFilterType = type
        val activeColor = ContextCompat.getColor(this, R.color.accent)
        val inactiveColor = ContextCompat.getColor(this, R.color.white)
        filterAll.setTextColor(if (type == null) activeColor else inactiveColor)
        filterAgents.setTextColor(if (type == EccComponentType.AGENT) activeColor else inactiveColor)
        filterSkills.setTextColor(if (type == EccComponentType.SKILL) activeColor else inactiveColor)
        filterCommands.setTextColor(if (type == EccComponentType.COMMAND) activeColor else inactiveColor)
        applyComponentFilter()
    }

    private fun applyComponentFilter() {
        val filtered = EccRepository.filterComponents(
            catalog = catalog,
            query = editSearchEcc.text?.toString().orEmpty(),
            typeFilter = currentFilterType
        )
        componentsAdapter.updateItems(filtered)
    }

    private fun showRunComponentDialog(component: EccComponent) {
        val input = EditText(this).apply {
            hint = "Describe task or paste code for ${component.title}..."
            setTextColor(ContextCompat.getColor(this@EccToolkitActivity, R.color.white))
            setHintTextColor(ContextCompat.getColor(this@EccToolkitActivity, R.color.text_gray))
            minLines = 3
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(component.title)
            .setMessage("${component.description}\n\nSource: ${component.sourcePath}")
            .setView(input)
            .setPositiveButton("Run with Gemini 3.8 Flash") { _, _ ->
                val userPrompt = input.text?.toString().orEmpty().trim()
                    .ifEmpty { "Explain how to apply ${component.title} to this project step-by-step." }
                executeComponent(component, userPrompt)
            }
            .setNegativeButton("Close", null)
            .show()
        styleDialogButtons(dialog)
    }

    private fun executeComponent(component: EccComponent, userInput: String) {
        setBusy(true)
        lifecycleScope.launch {
            val result = EccWorkflowEngine.runComponent(
                context = this@EccToolkitActivity,
                component = component,
                userInput = userInput,
                catalog = catalog
            )
            if (isFinishing || isDestroyed) return@launch
            setBusy(false)

            val resultDialog = AlertDialog.Builder(this@EccToolkitActivity)
                .setTitle(component.title)
                .setMessage(result)
                .setPositiveButton("Save /learn") { _, _ ->
                    EccMemoryStore.addMemory(this@EccToolkitActivity, component.title, result.take(280), EccMemoryKind.PATTERN)
                    renderRulesAndMemory()
                    Toast.makeText(this@EccToolkitActivity, "Saved to ECC Memory.", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close", null)
                .show()
            styleDialogButtons(resultDialog)
        }
    }

    private fun runOfflineSecurityScanOnly() {
        val code = editCodeReviewInput.text?.toString().orEmpty()
        val report = EccSecurityScanner.scan(code)
        tvScanSummary.text = report.summary
        tvReviewOutput.text = if (report.findings.isEmpty()) {
            "AgentShield Gate: PASS (${report.score}/100). Checked ${report.lineCount} lines."
        } else {
            buildString {
                report.findings.forEach { finding ->
                    appendLine("[${finding.severity.label}] Line ${finding.lineNumber} (${finding.ruleId}): ${finding.title}")
                    appendLine("  Code: ${finding.snippet}")
                    appendLine("  Fix: ${finding.remediation}")
                    appendLine()
                }
            }.trim()
        }
    }

    private fun runAiReview(mode: EccWorkflowEngine.ReviewMode) {
        val codeOrError = editCodeReviewInput.text?.toString().orEmpty().trim()
        if (codeOrError.isEmpty()) {
            Toast.makeText(this, "Paste code or an error stack trace first.", Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            val (report, reply) = EccWorkflowEngine.runReviewOrDebug(
                context = this@EccToolkitActivity,
                codeOrError = codeOrError,
                mode = mode,
                catalog = catalog
            )
            if (isFinishing || isDestroyed) return@launch
            setBusy(false)
            tvScanSummary.text = report.summary
            tvReviewOutput.text = reply
        }
    }

    private fun updateContextMode(mode: EccContextMode) {
        EccMemoryStore.setContextMode(this, mode)
        renderRulesAndMemory()
        Toast.makeText(this, "Switched ECC Context to ${mode.label}.", Toast.LENGTH_SHORT).show()
    }

    private fun renderRulesAndMemory() {
        val profile = EccMemoryStore.getProfile(this)
        val activeColor = ContextCompat.getColor(this, R.color.accent)
        val inactiveColor = ContextCompat.getColor(this, R.color.white)
        btnContextDev.setTextColor(if (profile.contextMode == EccContextMode.DEV) activeColor else inactiveColor)
        btnContextReview.setTextColor(if (profile.contextMode == EccContextMode.REVIEW) activeColor else inactiveColor)
        btnContextResearch.setTextColor(if (profile.contextMode == EccContextMode.RESEARCH) activeColor else inactiveColor)

        containerEccRules.removeAllViews()
        val rules = EccMemoryStore.getRules(this, catalog.rules)
        rules.forEach { rule ->
            val check = CheckBox(this).apply {
                text = "[${rule.category}] ${rule.title}\n${rule.ruleText}"
                isChecked = rule.enabled
                setTextColor(ContextCompat.getColor(this@EccToolkitActivity, R.color.white))
                textSize = 12f
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setPadding(8, 12, 8, 12)
                setOnCheckedChangeListener { _, _ ->
                    EccMemoryStore.toggleRule(this@EccToolkitActivity, catalog.rules, rule.id)
                    updateContextBudgetLabel()
                }
                setOnLongClickListener {
                    if (rule.isCustom) {
                        EccMemoryStore.deleteCustomRule(this@EccToolkitActivity, catalog.rules, rule.id)
                        renderRulesAndMemory()
                        Toast.makeText(this@EccToolkitActivity, "Custom rule removed.", Toast.LENGTH_SHORT).show()
                    }
                    rule.isCustom
                }
            }
            containerEccRules.addView(check)
        }

        containerEccMemories.removeAllViews()
        val memories = EccMemoryStore.getMemories(this)
        memories.forEach { mem ->
            val tv = TextView(this).apply {
                text = "[${mem.kind.label}] ${mem.title}: ${mem.content}\n(Tap to delete)"
                setTextColor(ContextCompat.getColor(this@EccToolkitActivity, R.color.white))
                textSize = 12f
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setPadding(16, 14, 16, 14)
                setBackgroundResource(R.drawable.rounded_background)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 12 }
                layoutParams = lp
                setOnClickListener {
                    EccMemoryStore.deleteMemory(this@EccToolkitActivity, mem.id)
                    renderRulesAndMemory()
                    Toast.makeText(this@EccToolkitActivity, "Removed instinct.", Toast.LENGTH_SHORT).show()
                }
            }
            containerEccMemories.addView(tv)
        }
        updateContextBudgetLabel()
    }

    private fun updateContextBudgetLabel() {
        val brief = EccMemoryStore.buildContextBrief(this, catalog.rules)
        tvContextBudgetInfo.text = "Strategic Context Brief: ${brief.length} / 2400 chars injected into ECC prompts"
    }

    private fun showAddRuleDialog() {
        val input = EditText(this).apply {
            hint = "Rule (e.g. Always verify edge cases with Robolectric before release)"
            setTextColor(ContextCompat.getColor(this@EccToolkitActivity, R.color.white))
            setHintTextColor(ContextCompat.getColor(this@EccToolkitActivity, R.color.text_gray))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Custom ECC Rule")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text?.toString().orEmpty().trim()
                if (text.isNotEmpty()) {
                    EccMemoryStore.addCustomRule(this, catalog.rules, text.take(40), "project/custom", text)
                    renderRulesAndMemory()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        styleDialogButtons(dialog)
    }

    private fun showAddMemoryDialog() {
        val input = EditText(this).apply {
            hint = "Project instinct, architecture decision, or pitfall..."
            setTextColor(ContextCompat.getColor(this@EccToolkitActivity, R.color.white))
            setHintTextColor(ContextCompat.getColor(this@EccToolkitActivity, R.color.text_gray))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Persistent Memory (/learn)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text?.toString().orEmpty().trim()
                if (text.isNotEmpty()) {
                    EccMemoryStore.addMemory(this, text.take(36), text, EccMemoryKind.INSTINCT)
                    renderRulesAndMemory()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
        styleDialogButtons(dialog)
    }

    private fun executeResearchLookup() {
        val topic = editResearchTopic.text?.toString().orEmpty().trim()
        if (topic.isEmpty()) {
            Toast.makeText(this, "Enter a library, API, or technical topic first.", Toast.LENGTH_SHORT).show()
            return
        }
        setBusy(true)
        lifecycleScope.launch {
            val result = EccWorkflowEngine.runResearchFirst(this@EccToolkitActivity, topic, catalog)
            if (isFinishing || isDestroyed) return@launch
            setBusy(false)
            tvResearchOutput.text = result
        }
    }

    private fun syncFromGitHub() {
        btnSyncEcc.isEnabled = false
        btnSyncEcc.text = "Syncing..."
        setBusy(true)
        lifecycleScope.launch {
            val res = EccRepository.syncFromGitHub(this@EccToolkitActivity)
            if (isFinishing || isDestroyed) return@launch
            btnSyncEcc.isEnabled = true
            btnSyncEcc.text = "Sync ECC"
            setBusy(false)

            res.onSuccess { summary ->
                catalog = EccRepository.loadCatalog(this@EccToolkitActivity)
                tvSubtitle.text = "${summary.agentsCount} Agents • ${summary.skillsCount} Skills • ${summary.commandsCount} Cmds • Synced"
                applyComponentFilter()
                Toast.makeText(
                    this@EccToolkitActivity,
                    "Synced affaan-m/ECC (${summary.remoteFilesDiscovered} definitions found).",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                Toast.makeText(this@EccToolkitActivity, "Sync failed: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        progressEcc.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun styleDialogButtons(dialog: AlertDialog) {
        val white = ContextCompat.getColor(this, R.color.white)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(white)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(white)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(white)
    }
}
