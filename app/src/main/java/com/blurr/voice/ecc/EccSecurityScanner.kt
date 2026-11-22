package com.blurr.voice.ecc

/**
 * Deterministic offline Security & Code Quality scanner inspired by ECC AgentShield & rules.
 * Runs locally without network calls so security gates always execute cleanly.
 */
object EccSecurityScanner {

    private data class DetectionRule(
        val id: String,
        val severity: EccSeverity,
        val title: String,
        val regex: Regex,
        val remediation: String
    )

    private val rules: List<DetectionRule> = listOf(
        DetectionRule(
            id = "SEC-001",
            severity = EccSeverity.CRITICAL,
            title = "Hardcoded Google / Gemini API Key",
            regex = Regex("""AIza[0-9A-Za-z\-_]{20,}"""),
            remediation = "Remove hardcoded key and inject via Secrets/BuildConfig or user-entered GeminiKeyManager."
        ),
        DetectionRule(
            id = "SEC-002",
            severity = EccSeverity.CRITICAL,
            title = "Hardcoded Token / Secret Literal",
            regex = Regex("""(?:sk-[A-Za-z0-9]{16,}|ghp_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16})"""),
            remediation = "Revoke exposed token and load credentials from environment secrets or encrypted preferences."
        ),
        DetectionRule(
            id = "SEC-003",
            severity = EccSeverity.HIGH,
            title = "Hardcoded Password or Secret Assignment",
            regex = Regex("""(?i)\b(?:password|passwd|api_?secret|private_?key)\s*=\s*"[^"\s]{4,}""""),
            remediation = "Never embed literal credentials in source code; read from secure storage or user input."
        ),
        DetectionRule(
            id = "SEC-004",
            severity = EccSeverity.CRITICAL,
            title = "Trust-All TLS / Disabled Certificate Validation",
            regex = Regex("""(?i)(?:TrustAll|ALLOW_ALL_HOSTNAME_VERIFIER|X509TrustManager|checkServerTrusted\s*\([^)]*\)\s*\{\s*\})"""),
            remediation = "Remove custom TrustAll handlers and use standard system certificate verification."
        ),
        DetectionRule(
            id = "SEC-005",
            severity = EccSeverity.HIGH,
            title = "SQL Injection via String Concatenation",
            regex = Regex("""(?i)\b(?:rawQuery|execSQL)\s*\(\s*"[^"]*(?:\$|"\s*\+)"""),
            remediation = "Use parameterized selectionArgs (`?` placeholders) or Room `@Query` bindings."
        ),
        DetectionRule(
            id = "SEC-006",
            severity = EccSeverity.HIGH,
            title = "OS Command Execution / Shell Injection Risk",
            regex = Regex("""(?:Runtime\.getRuntime\(\)\.exec|ProcessBuilder\s*\()"""),
            remediation = "Avoid shell execution from untrusted inputs; pass strict argument arrays instead of shell strings."
        ),
        DetectionRule(
            id = "SEC-007",
            severity = EccSeverity.HIGH,
            title = "Dangerous Git / Destructive Shell Flag",
            regex = Regex("""(?:--no-verify|git\s+push\s+--force|rm\s+-rf\s+/|chmod\s+777)"""),
            remediation = "Never bypass verification hooks (--no-verify) or use destructive wildcard filesystem/git commands."
        ),
        DetectionRule(
            id = "SEC-008",
            severity = EccSeverity.HIGH,
            title = "WebView JavaScript Bridge Exposure",
            regex = Regex("""addJavascriptInterface\s*\("""),
            remediation = "Restrict WebView JavaScript interfaces to trusted HTTPS origins and @JavascriptInterface methods only."
        ),
        DetectionRule(
            id = "SEC-009",
            severity = EccSeverity.MEDIUM,
            title = "Cleartext HTTP Endpoint",
            regex = Regex("""http://(?!localhost|127\.0\.0\.1|10\.0\.2\.2)[a-zA-Z0-9._-]+"""),
            remediation = "Use HTTPS instead of cleartext HTTP to protect data in transit."
        ),
        DetectionRule(
            id = "SEC-010",
            severity = EccSeverity.MEDIUM,
            title = "Weak Cryptographic Algorithm (MD5 / SHA-1 / ECB)",
            regex = Regex("""(?i)(?:MessageDigest\.getInstance\s*\(\s*"(?:MD5|SHA-?1)"|AES/ECB)"""),
            remediation = "Use SHA-256+ for hashing and AES/GCM/NoPadding for symmetric encryption."
        ),
        DetectionRule(
            id = "QUAL-001",
            severity = EccSeverity.MEDIUM,
            title = "Unstructured GlobalScope Coroutine",
            regex = Regex("""\bGlobalScope\.launch\b"""),
            remediation = "Use lifecycleScope, viewModelScope, or a supervised CoroutineScope to prevent memory leaks."
        ),
        DetectionRule(
            id = "QUAL-002",
            severity = EccSeverity.MEDIUM,
            title = "Blocking Sleep or runBlocking Call",
            regex = Regex("""(?:\bThread\.sleep\s*\(|\brunBlocking\s*\{)"""),
            remediation = "Use non-blocking suspend `delay()` on coroutines and keep main/UI threads responsive."
        ),
        DetectionRule(
            id = "QUAL-003",
            severity = EccSeverity.LOW,
            title = "Sensitive Data Possibly Logged",
            regex = Regex("""(?i)\bLog\.(?:d|i|v|w|e)\s*\([^)]*(?:apiKey|token|password|secret)"""),
            remediation = "Mask or redact sensitive keys/tokens before writing diagnostic logs."
        ),
        DetectionRule(
            id = "QUAL-004",
            severity = EccSeverity.LOW,
            title = "Swallowed Exception / Empty Catch Block",
            regex = Regex("""catch\s*\([^)]+\)\s*\{\s*\}"""),
            remediation = "Log, propagate, or handle caught exceptions explicitly instead of silently swallowing errors."
        )
    )

    fun scan(sourceCode: String): EccScanReport {
        val normalized = sourceCode.trim()
        if (normalized.isEmpty()) {
            return EccScanReport(
                score = 100,
                passedGate = true,
                lineCount = 0,
                findings = emptyList(),
                summary = "No code provided to scan."
            )
        }

        val lines = sourceCode.lines()
        val findings = mutableListOf<EccSecurityFinding>()

        lines.forEachIndexed { index, rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("*")) return@forEachIndexed

            rules.forEach { rule ->
                if (rule.regex.containsMatchIn(rawLine)) {
                    findings.add(
                        EccSecurityFinding(
                            ruleId = rule.id,
                            severity = rule.severity,
                            title = rule.title,
                            lineNumber = index + 1,
                            snippet = trimmed.take(110),
                            remediation = rule.remediation
                        )
                    )
                }
            }
        }

        val penalty = findings.sumOf { it.severity.weight }
        val score = (100 - penalty).coerceIn(0, 100)
        val hasCriticalOrHigh = findings.any {
            it.severity == EccSeverity.CRITICAL || it.severity == EccSeverity.HIGH
        }
        val passedGate = !hasCriticalOrHigh && score >= 75

        val summary = if (findings.isEmpty()) {
            "PASS (100/100) — Zero security or code quality anti-patterns detected across ${lines.size} lines."
        } else {
            val crit = findings.count { it.severity == EccSeverity.CRITICAL }
            val high = findings.count { it.severity == EccSeverity.HIGH }
            val med = findings.count { it.severity == EccSeverity.MEDIUM }
            val low = findings.count { it.severity == EccSeverity.LOW }
            val verdict = if (passedGate) "PASS WITH WARNINGS" else "BLOCKED (Fix CRITICAL/HIGH issues)"
            "$verdict • Score: $score/100 • Critical: $crit, High: $high, Medium: $med, Low: $low"
        }

        return EccScanReport(
            score = score,
            passedGate = passedGate,
            lineCount = lines.size,
            findings = findings.sortedByDescending { it.severity.weight },
            summary = summary
        )
    }
}
