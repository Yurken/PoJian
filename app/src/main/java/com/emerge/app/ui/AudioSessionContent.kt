package com.emerge.app.ui

import com.emerge.app.audioanalysis.AudioInsightCodec
import com.emerge.app.audioanalysis.AudioInsightReport
import com.emerge.app.data.AudioSession
import com.emerge.app.data.AudioSessionStatus
import com.emerge.app.data.AudioSource
import com.emerge.app.data.AudioTranscript

internal class AudioSessionReportCache {
    private data class Entry(val json: String?, val report: AudioInsightReport?)
    private val entries = mutableMapOf<String, Entry>()

    fun update(sessions: List<AudioSession>): Map<String, AudioInsightReport?> {
        val activeIds = sessions.mapTo(HashSet()) { it.id }
        entries.keys.retainAll(activeIds)
        return sessions.associate { session ->
            val cached = entries[session.id]
            val entry = if (cached != null && cached.json == session.analysisJson) cached
                else Entry(session.analysisJson, AudioInsightCodec.decode(session.analysisJson))
                    .also { entries[session.id] = it }
            session.id to entry.report
        }
    }
}

internal data class PreparedAudioSession(val ui: AudioSessionUi, val recording: Boolean)

internal fun prepareAudioSessions(
    sessions: List<AudioSession>,
    transcripts: List<AudioTranscript>,
    reports: Map<String, AudioInsightReport?>
): List<PreparedAudioSession> {
    val grouped = transcripts.groupBy { it.sessionId }
    val transcriptById = transcripts.associateBy { it.id }
    val sessionById = sessions.associateBy { it.id }
    return sessions.map { session ->
        val report = reports[session.id]
        val segments = grouped[session.id].orEmpty().sortedWith(compareBy({ it.offsetStartMillis }, { it.id }))
        val savedSegments = segments.count { it.displayText.isNotBlank() }
        PreparedAudioSession(AudioSessionUi(session.id, session.title,
            if (session.source == AudioSource.IMPORT) "导入音频" else "应用录音",
            session.startedAt, session.durationMillis,
            segments.map { AudioSegmentUi(it.id, it.offsetStartMillis, it.offsetEndMillis, it.originalText, it.correctedText) },
            audioTranscriptStatus(session.status, savedSegments), session.errorMessage, report?.method, report?.summary,
            report?.claims.orEmpty().map { claim ->
                val source = transcriptById[claim.segmentId]
                val previous = (claim.historySegmentId ?: claim.comparedSegmentId)?.let(transcriptById::get)
                val previousSession = previous?.sessionId?.let(sessionById::get)
                AudioClaimUi(claim.claim, claim.evidence, claim.relation, claim.novelty,
                    (claim.historyQuote ?: claim.comparedQuote)?.let { quote ->
                        val time = previous?.offsetStartMillis?.div(1000)?.let { "%02d:%02d".format(it / 60, it % 60) }.orEmpty()
                        "${if (claim.historyQuote != null) "重复依据原文" else "对照的历史材料（并非重复匹配）"} · ${previousSession?.title ?: "先前片段"} $time\n$quote"
                    }, source?.offsetStartMillis, source?.offsetEndMillis)
            }, sourcePackage = session.packageName.takeIf { session.source == AudioSource.PLAYBACK }),
            recording = session.status == AudioSessionStatus.RECORDING)
    }
}

internal fun projectAudioSessions(
    content: List<PreparedAudioSession>,
    analyzing: Set<String>,
    pausedSessionId: String?
): List<AudioSessionUi> = content.map { prepared ->
    val row = prepared.ui
    val paused = prepared.recording && row.id == pausedSessionId
    val analyzingNow = row.id in analyzing
    if (paused || analyzingNow) row.copy(
        statusLabel = if (paused) "录音已暂停" else row.statusLabel,
        analyzing = analyzingNow
    ) else row
}
