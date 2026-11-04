package com.sednium.kryptonide.agent.pocket

import java.util.Locale

enum class RouteType {
    FAST_PATH,
    DEEP_REASONING
}

data class RoutingDecision(
    val route: RouteType,
    val confidence: Float,
    val reasoning: String
)

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    BLOCKED
}

enum class ExecutionTier {
    SUPERVISED, // Prompts before medium/high
    AUTONOMOUS, // Executes low/medium, prompts high
    UNBOUND     // Executes all except strictly BLOCKED
}

data class RiskAssessment(
    val level: RiskLevel,
    val reason: String,
    val isAllowed: Boolean,
    val requiresConfirmation: Boolean
)

data class AifMetadata(
    val tags: List<String>,
    val importance: Float,
    val summary: String
)

object LayaSystemOneEngine {

    // ── 1. Fast-Path vs Deep-Reasoning Router ──────────────────

    private val FAST_PATH_PATTERNS = listOf(
        Regex("""(?i)^\s*(hi|hello|hey|who are you|what can you do|ping|status|help)\s*[\.!\?]*$"""),
        Regex("""(?i)^\s*(what model|which model|current provider|model name)\s*[\.!\?]*$"""),
        Regex("""(?i)^\s*(where is|find file|locate)\s+([a-zA-Z0-9_\-\.]+)\s*[\.!\?]*$"""),
        Regex("""(?i)^\s*(explain|what does this line mean|syntax of)\s+([a-zA-Z0-9_]+)\s*[\.!\?]*$""")
    )

    private val DEEP_REASONING_PATTERNS = listOf(
        Regex("""(?i)(plan|architect|design|implement|refactor|fix bug|stack trace|crash|exception)"""),
        Regex("""(?i)(across (all|multiple) files|create a (new )?feature|rewrite|migrate|optimize)"""),
        Regex("""(?i)(unit test|build error|gradle failure|unresolved reference|multi-step)""")
    )

    fun route(query: String): RoutingDecision {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return RoutingDecision(RouteType.FAST_PATH, 1.0f, "Empty query")
        }

        // Check explicit deep reasoning keywords first
        for (p in DEEP_REASONING_PATTERNS) {
            if (p.containsMatchIn(trimmed)) {
                return RoutingDecision(
                    route = RouteType.DEEP_REASONING,
                    confidence = 0.95f,
                    reasoning = "Matches deep reasoning intent: '${p.pattern}'"
                )
            }
        }

        // Check fast path patterns
        for (p in FAST_PATH_PATTERNS) {
            if (p.matches(trimmed)) {
                return RoutingDecision(
                    route = RouteType.FAST_PATH,
                    confidence = 0.92f,
                    reasoning = "Matches fast reflexive pattern: '${p.pattern}'"
                )
            }
        }

        // Query length heuristic: Short queries without code snippets are usually fast path
        return if (trimmed.length < 80 && !trimmed.contains("\n") && !trimmed.contains("{")) {
            RoutingDecision(RouteType.FAST_PATH, 0.75f, "Short conversational query")
        } else {
            RoutingDecision(RouteType.DEEP_REASONING, 0.85f, "Complex multi-clause query")
        }
    }

    // ── 2. Deterministic Risk Classifier ───────────────────────

    private val FORK_BOMB_REGEX = Regex("""(?i)(:\s*\(\s*\)\s*\{\s*:\|:&\s*\}\s*;\s*:|fork\s*while\s*1)""")
    private val DESTRUCTIVE_ROOT_REGEX = Regex("""(?i)(rm\s+-(rf|fr|r)\s+(/\s*$|/\*|~\s*$|~/\*|\${'$'}HOME\s*$))""")
    private val RAW_DEVICE_WRITE_REGEX = Regex("""(?i)(mkfs|dd\s+if=.*of=/dev/(sd|vd|nvme|mmcblk|block))""")

    private val HIGH_RISK_PATTERNS = listOf(
        Regex("""(?i)\b(rm\s+-(rf|fr|r))\b"""),
        Regex("""(?i)\b(git\s+reset\s+--hard|git\s+clean\s+-[fF]d?)\b"""),
        Regex("""(?i)\b(reboot|poweroff|shutdown|killall|pkill)\b"""),
        Regex("""(?i)\b(chmod\s+-R\s+777|chown\s+-R)\b""")
    )

    private val MEDIUM_RISK_PATTERNS = listOf(
        Regex("""(?i)\b(npm\s+(install|i|update)|pip3?\s+install|yarn\s+add)\b"""),
        Regex("""(?i)\b(git\s+(checkout|branch\s+-D|merge|rebase|push))\b"""),
        Regex("""(?i)\b(mv|cp|rm|mkdir|touch)\b""")
    )

    fun risk(command: String, tier: ExecutionTier = ExecutionTier.SUPERVISED): RiskAssessment {
        val trimmed = command.trim()

        // 1. Check unconditionally blocked patterns
        if (FORK_BOMB_REGEX.containsMatchIn(trimmed)) {
            return RiskAssessment(RiskLevel.BLOCKED, "Detected dangerous fork bomb pattern", isAllowed = false, requiresConfirmation = false)
        }
        if (DESTRUCTIVE_ROOT_REGEX.containsMatchIn(trimmed)) {
            return RiskAssessment(RiskLevel.BLOCKED, "Destructive deletion targeting root/home filesystem", isAllowed = false, requiresConfirmation = false)
        }
        if (RAW_DEVICE_WRITE_REGEX.containsMatchIn(trimmed)) {
            return RiskAssessment(RiskLevel.BLOCKED, "Raw block device write / filesystem creation attempt", isAllowed = false, requiresConfirmation = false)
        }

        // 2. Check High Risk
        for (pattern in HIGH_RISK_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                val allowed = tier == ExecutionTier.UNBOUND
                val confirm = tier != ExecutionTier.UNBOUND
                return RiskAssessment(
                    level = RiskLevel.HIGH,
                    reason = "Command modifies repository state or deletes files recursively: ${pattern.pattern}",
                    isAllowed = true,
                    requiresConfirmation = confirm
                )
            }
        }

        // 3. Check Medium Risk
        for (pattern in MEDIUM_RISK_PATTERNS) {
            if (pattern.containsMatchIn(trimmed)) {
                val confirm = tier == ExecutionTier.SUPERVISED
                return RiskAssessment(
                    level = RiskLevel.MEDIUM,
                    reason = "Command installs dependencies or mutates files: ${pattern.pattern}",
                    isAllowed = true,
                    requiresConfirmation = confirm
                )
            }
        }

        // 4. Low Risk (Read-only inspection)
        return RiskAssessment(
            level = RiskLevel.LOW,
            reason = "Standard read-only or build command",
            isAllowed = true,
            requiresConfirmation = false
        )
    }

    // ── 3. Context Token Pruner ────────────────────────────────

    private val ERROR_LINE_REGEX = Regex("""(?i)(error|fatal|exception|failed|failure|line\s+\d+|warning|caused by)""")

    fun prune(output: String, maxLines: Int = 120): String {
        val lines = output.lines()
        if (lines.size <= maxLines) return output

        val headCount = (maxLines * 0.2).toInt().coerceAtLeast(5)
        val tailCount = (maxLines * 0.3).toInt().coerceAtLeast(10)
        val middleCapacity = (maxLines - headCount - tailCount).coerceAtLeast(10)

        val head = lines.take(headCount)
        val tail = lines.takeLast(tailCount)

        val middlePool = lines.subList(headCount, lines.size - tailCount)
        val criticalMiddle = middlePool.filter { ERROR_LINE_REGEX.containsMatchIn(it) }.take(middleCapacity)

        val omitted = lines.size - head.size - tail.size - criticalMiddle.size

        return buildString {
            head.forEach { appendLine(it) }
            if (criticalMiddle.isNotEmpty()) {
                appendLine("\n[... omitted non-critical logs; retained ${criticalMiddle.size} critical diagnostic lines ...]")
                criticalMiddle.forEach { appendLine(it) }
            }
            appendLine("\n[... truncated $omitted lines of build output ...]\n")
            tail.forEach { appendLine(it) }
        }
    }

    // ── 4. AIF Semantic Tagger ─────────────────────────────────

    fun extractAifMetadata(prompt: String, diffOrContent: String? = null): AifMetadata {
        val combined = "$prompt\n${diffOrContent ?: ""}".lowercase(Locale.US)
        val tags = mutableSetOf<String>()

        if (combined.contains("compose") || combined.contains("ui") || combined.contains("layout") || combined.contains("screen")) {
            tags.add("ui")
        }
        if (combined.contains("android") || combined.contains("activity") || combined.contains("manifest")) {
            tags.add("android")
        }
        if (combined.contains("network") || combined.contains("http") || combined.contains("api") || combined.contains("retrofit") || combined.contains("ktor")) {
            tags.add("network")
        }
        if (combined.contains("database") || combined.contains("room") || combined.contains("sqlite") || combined.contains("storage")) {
            tags.add("storage")
        }
        if (combined.contains("architecture") || combined.contains("refactor") || combined.contains("clean code") || combined.contains("pattern")) {
            tags.add("architecture")
        }
        if (combined.contains("bug") || combined.contains("crash") || combined.contains("fix") || combined.contains("error")) {
            tags.add("bugfix")
        }
        if (combined.contains("native") || combined.contains("c++") || combined.contains("jni") || combined.contains("ndk")) {
            tags.add("native")
        }

        // Calculate cognitive importance score
        var score = 0.3f
        if (tags.contains("architecture")) score += 0.35f
        if (tags.contains("bugfix")) score += 0.25f
        if (tags.contains("native")) score += 0.15f
        if ((diffOrContent?.length ?: 0) > 500) score += 0.1f

        val importance = score.coerceIn(0.1f, 1.0f)
        val summary = prompt.lines().firstOrNull()?.take(80)?.trim() ?: "Task turn completed"

        return AifMetadata(
            tags = tags.toList().ifEmpty { listOf("general") },
            importance = importance,
            summary = summary
        )
    }
}
