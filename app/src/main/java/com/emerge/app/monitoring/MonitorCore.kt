package com.emerge.app.monitoring

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import com.emerge.app.analysis.EvidenceIdentityComparator
import com.emerge.app.analysis.EvidenceRelation

/** A recreated accessibility bridge cannot reactivate a result bound to an earlier screen session. */
class MonitorScreenScope {
    private val nonce = UUID.randomUUID().toString()
    fun token(packageName: String, windowId: Int, revision: Long, text: String): String =
        MonitorPolicy.hash("$nonce|$packageName|$windowId|$revision|$text")
}

/** Product policy, deliberately independent of Android and model providers. */
data class MonitorConfig(
    val bubble: Boolean = false,
    val cocoon: Boolean = true,
    val realtime: Boolean = false,
    val visual: Boolean = false,
    val cooldownMinutes: Int = 30,
    val dailyAlerts: Int = 6,
    val displaySeconds: Int = 3,
    val frameIntervalSeconds: Int = 30,
    val dailyFrames: Int = 20,
    val quiet: Boolean = false,
    val quietStart: Int = 22,
    val quietEnd: Int = 8,
    val researchMode: Boolean = false
) {
    fun bounded() = copy(cooldownMinutes = cooldownMinutes.coerceIn(5, 180),
        dailyAlerts = dailyAlerts.coerceIn(0, 30), displaySeconds = displaySeconds.coerceIn(2, 8),
        frameIntervalSeconds = frameIntervalSeconds.coerceIn(15, 120), dailyFrames = dailyFrames.coerceIn(1, 100),
        quietStart = quietStart.coerceIn(0, 23), quietEnd = quietEnd.coerceIn(0, 23))
}

data class WebEvidence(val url: String, val title: String, val quote: String, val excerpt: String) {
    fun valid(): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
            quote.isNotBlank() && quote.length <= 1000 && excerpt.contains(quote)
    }.getOrDefault(false)
}

data class MonitorFinding(
    val id: String, val contentKey: String, val packageName: String, val capturedAt: Long,
    val kind: String, val verdict: String, val claim: String, val explanation: String,
    val sources: List<WebEvidence> = emptyList(), val topic: String = "",
    val contextToken: String = "", val inputKind: String = "text"
) {
    val label: String get() = when (verdict) {
        "supported" -> "资料支持（仍需核对）"
        "contradicted" -> "发现证据矛盾"
        "conflicting" -> "证据存在分歧"
        "opinion" -> "观点表达，不判真假"
        "insufficient" -> "证据不足"
        "not_checked" -> "未完成核查"
        "repetition" -> "观点与依据重复线索"
        else -> "待核查"
    }
}

data class ObservedArgument(val contentKey: String, val packageName: String, val capturedAt: Long,
    val topic: String, val subject: String, val dimension: String, val stance: String,
    val evidence: String, val conditions: String = "", val uncertain: Boolean = false,
    val encounterKey: String = contentKey, val evidenceActors: List<String> = emptyList(),
    val evidenceOrigins: List<String> = emptyList())

object MonitorPolicy {
    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun normalized(value: String) = value.lowercase().replace(Regex("\\s+"), " ").trim()
    fun quiet(config: MonitorConfig, hour: Int): Boolean = config.quiet && when {
        config.quietStart == config.quietEnd -> true
        config.quietStart < config.quietEnd -> hour in config.quietStart until config.quietEnd
        else -> hour >= config.quietStart || hour < config.quietEnd
    }
    fun alertKey(finding: MonitorFinding) = hash(finding.kind + "|" +
        if (finding.kind == "cocoon") finding.topic else normalized(finding.claim))

    /** A completed result for an earlier screen must never appear as a verdict on the next screen. */
    fun mayAlert(finding: MonitorFinding, config: MonitorConfig, currentToken: String,
        now: Long, hour: Int, todayCount: Int, lastAlertAt: Long?): Boolean {
        if (!config.bubble || quiet(config, hour) || todayCount >= config.dailyAlerts) return false
        if (finding.contextToken.isBlank() || finding.contextToken != currentToken) return false
        if (now < finding.capturedAt || now - finding.capturedAt > 120_000) return false
        if (lastAlertAt != null && (now < lastAlertAt || now - lastAlertAt < config.cooldownMinutes * 60_000L)) return false
        return when (finding.kind) {
            "cocoon" -> config.cocoon && !config.researchMode && finding.verdict == "repetition"
            "fact" -> config.realtime && finding.verdict == "contradicted" &&
                finding.sources.isNotEmpty() && finding.sources.all { it.valid() }
            else -> false
        }
    }

    /** Same topic is not same stance; several fragments from one audio session are one encounter. */
    fun repetition(history: List<ObservedArgument>, now: Long, zone: ZoneId = ZoneId.systemDefault()): ObservedArgument? {
        val eligible = history.filter { !it.uncertain && it.topic.isNotBlank() && it.subject.isNotBlank() &&
            it.dimension.isNotBlank() && it.stance.isNotBlank() &&
            it.evidence.isNotBlank() && it.capturedAt <= now && now - it.capturedAt <= 7 * 86_400_000L }
        val latest = eligible.maxByOrNull { it.capturedAt } ?: return null
        val scope = eligible.filter { it.topic == latest.topic && it.subject == latest.subject &&
            it.dimension == latest.dimension && it.conditions == latest.conditions }
            .distinctBy { it.encounterKey }
        // Conservative guard for screen fragments with uncertain post boundaries: never diagnose from one sitting.
        if (scope.size < 8 || scope.map { Instant.ofEpochMilli(it.capturedAt).atZone(zone).toLocalDate() }.distinct().size < 3) return null
        val dominant = scope.groupBy { normalized(it.stance) }.maxByOrNull { it.value.size }?.value ?: return null
        if (dominant.none { it.contentKey == latest.contentKey } || dominant.size.toDouble() / scope.size < 0.75) return null
        val ordered = dominant.sortedBy { it.capturedAt }
        val identities = ordered.map { EvidenceIdentityComparator.identity(it.evidence, it.evidenceActors, it.evidenceOrigins) }
        val repeated = ordered.indices.count { index ->
            val current = ordered[index]
            (maxOf(0, index - 120) until index).reversed().any { previous ->
                val prior = ordered[previous]
                prior.capturedAt < current.capturedAt &&
                EvidenceIdentityComparator.compare(current.evidence, prior.evidence,
                    identities[index], identities[previous]) == EvidenceRelation.REUSED }
        }
        return latest.takeIf { repeated.toDouble() / scope.size >= 0.6 }
    }
}
