package com.blurr.voice.ecc

import kotlinx.serialization.Serializable

@Serializable
enum class EccComponentType {
    AGENT,
    SKILL,
    COMMAND
}

@Serializable
data class EccComponent(
    val id: String,
    val title: String,
    val type: EccComponentType,
    val category: String,
    val sourcePath: String = "",
    val command: String? = null,
    val description: String,
    val systemPrompt: String,
    val tags: List<String> = emptyList()
)

@Serializable
data class EccProjectRule(
    val id: String,
    val title: String,
    val category: String,
    val ruleText: String,
    val enabled: Boolean = true,
    val isCustom: Boolean = false
)

@Serializable
data class EccCatalog(
    val repository: String = "https://github.com/affaan-m/ECC",
    val version: String = "1.0.0",
    val agents: List<EccComponent> = emptyList(),
    val skills: List<EccComponent> = emptyList(),
    val commands: List<EccComponent> = emptyList(),
    val rules: List<EccProjectRule> = emptyList()
)

enum class EccWorkflowStage(
    val order: Int,
    val label: String,
    val agentId: String,
    val objective: String
) {
    PLAN(1, "Plan", "planner", "Scope requirements, architecture, impacted modules, and edge cases."),
    TEST(2, "Test", "tdd-guide", "Design Red-phase failing unit/Robolectric tests & acceptance criteria first."),
    IMPLEMENT(3, "Implement", "architect", "Write clean, modular Kotlin implementation satisfying rules & tests."),
    REVIEW(4, "Review", "code-reviewer", "Audit code quality, bugs, lifecycle safety, and security vulnerabilities."),
    VERIFY(5, "Verify", "docs-lookup", "Run final verification checklist, security gate, & regression sign-off.")
}

@Serializable
data class EccWorkflowSession(
    val id: String,
    val goal: String,
    val createdAtMs: Long,
    val outputsByStage: Map<String, String> = emptyMap(),
    val securityScore: Int? = null
)

@Serializable
enum class EccContextMode(val label: String, val guidance: String) {
    DEV("Dev Mode", "Focus on clean implementation, TDD, idiomatic Kotlin, and minimal working increments."),
    REVIEW("Review Mode", "Prioritize bug detection, security vulnerabilities, lifecycle safety, and edge cases."),
    RESEARCH("Research Mode", "Prioritize official documentation, API contracts, trade-offs, and verification before coding.")
}

@Serializable
enum class EccMemoryKind(val label: String) {
    DECISION("Decision"),
    PATTERN("Pattern"),
    PITFALL("Pitfall"),
    INSTINCT("Instinct")
}

@Serializable
data class EccMemoryItem(
    val id: String,
    val title: String,
    val content: String,
    val kind: EccMemoryKind = EccMemoryKind.INSTINCT,
    val updatedAtMs: Long = System.currentTimeMillis()
)

@Serializable
data class EccProjectProfile(
    val projectName: String = "Baby AI Android",
    val techStack: String = "Kotlin, Jetpack Compose + XML, Coroutines, OkHttp, Gemini 3.8 Flash, Robolectric",
    val architectureNotes: String = "Local-first Android assistant with REST Gemini 3.8 Flash, Room + SharedPreferences stores, Accessibility automation, and Public APIs explorer.",
    val contextMode: EccContextMode = EccContextMode.DEV
)

enum class EccSeverity(val weight: Int, val label: String) {
    CRITICAL(30, "CRITICAL"),
    HIGH(18, "HIGH"),
    MEDIUM(9, "MEDIUM"),
    LOW(4, "LOW")
}

data class EccSecurityFinding(
    val ruleId: String,
    val severity: EccSeverity,
    val title: String,
    val lineNumber: Int,
    val snippet: String,
    val remediation: String
)

data class EccScanReport(
    val score: Int,
    val passedGate: Boolean,
    val lineCount: Int,
    val findings: List<EccSecurityFinding>,
    val summary: String
)
