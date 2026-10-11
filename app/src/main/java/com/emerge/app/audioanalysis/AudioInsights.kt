package com.emerge.app.audioanalysis

import com.emerge.app.analysis.CognitiveAnalyzer
import com.emerge.app.analysis.Increment
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.time.ZoneId

/** Stable transcript IDs are also the evidence IDs supplied to the optional text service. */
data class TranscriptEvidence(val id: Long, val sessionId: String, val text: String,
    val timestamp: Long, val startMillis: Long, val endMillis: Long)
data class AudioClaimInsight(val claim: String, val evidence: String, val segmentId: Long,
    val relation: String, val novelty: String, val historySegmentId: Long? = null,
    val historyQuote: String? = null, val comparedSegmentId: Long? = null, val comparedQuote: String? = null)
data class AudioInsightReport(val summary: String, val method: String, val claims: List<AudioClaimInsight>,
    val generatedAt: Long = System.currentTimeMillis(), val usage: String? = null)

object LocalAudioInsights {
    fun analyze(current: List<TranscriptEvidence>, history: List<TranscriptEvidence>): AudioInsightReport {
        if (current.isEmpty()) return AudioInsightReport("暂无可分析的转写", "本地识别", emptyList())
        val selected = current.map { it.id }.toSet()
        val all = (current + earlierHistory(current, history)).distinctBy { it.id }
            .sortedWith(compareBy<TranscriptEvidence> { it.timestamp }.thenBy { it.id })
        val lookup = all.associateBy { it.id }
        val now = current.maxOf { it.timestamp }
        val events = all.map { BrowseEvent(id = it.id, packageName = "语音转写", timestamp = it.timestamp,
            eventType = 0, visibleText = it.text, endTimestamp = it.timestamp,
            sourceType = "audio_transcript", audioSessionId = it.sessionId,
            mediaStartMillis = it.startMillis, mediaEndMillis = it.endMillis) }
        val report = CognitiveAnalyzer().analyze(events, StatisticsPeriod.WEEK, now, ZoneId.systemDefault())
        val claims = report.claims.filter { it.eventId in selected }.mapNotNull { claim ->
            val segment = lookup[claim.eventId] ?: return@mapNotNull null
            // Show the actual changed fragment for novelty, rather than an arbitrary reused first line.
            val visibleEvidence = claim.evidenceDetails.filter { segment.text.contains(it.text) }
            val fragment = visibleEvidence.firstOrNull { it.isNew } ?: visibleEvidence.firstOrNull()
            val evidence = fragment?.text
                ?: claim.quote.takeIf { segment.text.contains(it) } ?: return@mapNotNull null
            // A latest comparable claim need not contain the selected fragment. Link only a verified direct match.
            val historicalMatch = fragment?.historyMatches?.firstOrNull { match ->
                lookup[match.eventId]?.let { it.timestamp < segment.timestamp && it.text.contains(match.quote) } == true
            }
            // New evidence has no identical historical match. Show the actual earlier evidence
            // being compared, without claiming that the new fragment was already present.
            val comparison = historicalMatch ?: claim.previousEventId?.let { id ->
                lookup[id]?.takeIf { it.timestamp < segment.timestamp }?.let { prior ->
                    report.claims.firstOrNull { it.eventId == id }?.evidenceDetails
                        ?.firstOrNull { prior.text.contains(it.text) }
                        ?.let { com.emerge.app.analysis.EvidenceHistoryMatch(id, it.text) }
                        ?: com.emerge.app.analysis.EvidenceHistoryMatch(id, prior.text)
                }
            }
            AudioClaimInsight(claim.quote, evidence, claim.eventId,
                if (claim.uncertainAttribution) "可能引用了他人内容，待确认" else "待核实，不代表你的看法",
                claim.increment.label, historicalMatch?.eventId, historicalMatch?.quote,
                comparison?.eventId, comparison?.quote)
        }
        val summary = if (claims.isEmpty()) "未识别到明确观点，请核对转写，或开启云端分析。"
            else "识别出 ${claims.size} 个观点，其中 ${claims.count { it.novelty == Increment.NEW.label }} 个依据有新增，${claims.count { it.novelty == Increment.REPEATED.label }} 个依据重复。"
        val comparisonNote = "比对综合同范围历史片段，所示引文仅为其中一处匹配。新增依据不代表真实或来源独立。"
        return AudioInsightReport(summary + "\n" + comparisonNote, "本地识别 · 历史对照 · 请核对转写", claims)
    }

    /** Similarity retrieves candidates only; the analyzer still compares actual evidence and conditions. */
    fun candidates(current: List<TranscriptEvidence>, history: List<TranscriptEvidence>, limit: Int = 10): List<TranscriptEvidence> {
        if (current.isEmpty() || limit <= 0) return emptyList()
        val currentTerms = terms(current.joinToString(" ") { it.text })
        return earlierHistory(current, history).distinctBy { it.id }.map { it to terms(it.text).intersect(currentTerms).size }
            .filter { it.second > 0 }.sortedWith(compareByDescending<Pair<TranscriptEvidence, Int>> { it.second }
                .thenByDescending { it.first.timestamp }).take(limit).map { it.first }
    }
    private fun earlierHistory(current: List<TranscriptEvidence>, history: List<TranscriptEvidence>): List<TranscriptEvidence> {
        val earliest = current.minOfOrNull { it.timestamp } ?: return emptyList()
        val currentIds = current.map { it.id }.toSet()
        val currentSessions = current.map { it.sessionId }.toSet()
        return history.filter { it.timestamp < earliest && it.id !in currentIds && it.sessionId !in currentSessions }
    }
    private fun terms(text: String): Set<String> = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        .windowed(2).toSet()
}
