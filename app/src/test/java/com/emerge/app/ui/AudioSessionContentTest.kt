package com.emerge.app.ui

import com.emerge.app.data.AudioSession
import com.emerge.app.data.AudioSessionStatus
import com.emerge.app.data.AudioSource
import com.emerge.app.data.AudioTranscript
import org.junit.Assert.*
import org.junit.Test

class AudioSessionContentTest {
    private val reportJson = """{"summary":"摘要","method":"本地识别","generatedAt":1,"claims":[{"claim":"观点","evidence":"依据","segmentId":21,"relation":"重复","novelty":"重复","historySegmentId":11,"historyQuote":"历史原文"}]}"""

    private fun session(id: String = "current", json: String? = reportJson,
        status: String = AudioSessionStatus.COMPLETED) = AudioSession(
        id, "app.example", AudioSource.PLAYBACK, if (id == "current") "当前" else "历史", 1000,
        status = status, analysisJson = json
    )

    private fun transcript(id: Long, sessionId: String, start: Long, text: String,
        corrected: String? = null) = AudioTranscript(id, sessionId, id, start, start + 1000,
        text, corrected, 1000)

    @Test fun unchangedReportIsReusedWhenSessionMetadataChanges() {
        val cache = AudioSessionReportCache()
        val row = session()
        val first = cache.update(listOf(row)).getValue(row.id)
        assertNotNull(first)

        val updated = cache.update(listOf(row.copy(title = "修改标题", durationMillis = 9000)))

        assertSame(first, updated.getValue(row.id))
        assertEquals("摘要", updated.getValue(row.id)?.summary)
    }

    @Test fun changedJsonIsDecodedEvenWhenSessionIdAndVersionStayTheSame() {
        val cache = AudioSessionReportCache()
        val row = session()
        val first = cache.update(listOf(row)).getValue(row.id)
        val next = cache.update(listOf(row.copy(analysisJson = reportJson.replace("摘要", "更新摘要"))))

        assertNotSame(first, next.getValue(row.id))
        assertEquals("更新摘要", next.getValue(row.id)?.summary)
    }

    @Test fun removedSessionsReleaseTheirReportsBeforeTheIdIsUsedAgain() {
        val cache = AudioSessionReportCache()
        val row = session()
        val first = cache.update(listOf(row)).getValue(row.id)

        assertTrue(cache.update(emptyList()).isEmpty())
        val restored = cache.update(listOf(row)).getValue(row.id)

        assertEquals(first, restored)
        assertNotSame(first, restored)
    }

    @Test fun clearedOrMalformedReportDoesNotKeepThePreviousAnalysis() {
        val cache = AudioSessionReportCache()
        val row = session()
        cache.update(listOf(row))

        assertNull(cache.update(listOf(row.copy(analysisJson = null))).getValue(row.id))
        assertNull(cache.update(listOf(row.copy(analysisJson = "invalid"))).getValue(row.id))
        assertEquals("摘要", cache.update(listOf(row)).getValue(row.id)?.summary)
    }

    @Test fun contentKeepsSessionOrderAndBuildsMediaOrderedCorrectedTranscripts() {
        val rows = listOf(session(status = AudioSessionStatus.FAILED).copy(errorMessage = "解码失败"),
            session("history", json = null))
        val transcripts = listOf(transcript(22, "current", 4000, "后段"),
            transcript(21, "current", 2000, "原文", "修订"), transcript(11, "history", 65000, "历史原文"))
        val result = prepareAudioSessions(rows, transcripts, AudioSessionReportCache().update(rows))

        assertEquals(listOf("current", "history"), result.map { it.ui.id })
        assertEquals(listOf(21L, 22L), result.first().ui.segments.map { it.id })
        assertEquals("修订", result.first().ui.segments.first().correctedText)
        assertEquals("转写中断", result.first().ui.statusLabel)
        assertEquals("解码失败", result.first().ui.errorMessage)
        assertEquals("重复依据原文 · 历史 01:05\n历史原文", result.first().ui.claims.single().historyLabel)
        assertEquals(2000L, result.first().ui.claims.single().startMillis)
    }

    @Test fun cachedReportStillUsesUpdatedHistoryMetadataAndTranscriptOffsets() {
        val cache = AudioSessionReportCache()
        val rows = listOf(session(), session("history", json = null))
        val transcripts = listOf(transcript(21, "current", 2000, "当前原文"),
            transcript(11, "history", 65000, "历史原文"))
        prepareAudioSessions(rows, transcripts, cache.update(rows))
        val updatedRows = listOf(rows.first(), rows.last().copy(title = "更名历史"))
        val updatedTranscripts = listOf(transcripts.first().copy(offsetStartMillis = 3000),
            transcripts.last().copy(offsetStartMillis = 126000))

        val result = prepareAudioSessions(updatedRows, updatedTranscripts, cache.update(updatedRows))

        assertEquals("重复依据原文 · 更名历史 02:06\n历史原文", result.first().ui.claims.single().historyLabel)
        assertEquals(3000L, result.first().ui.claims.single().startMillis)

        val remainingRows = listOf(rows.first())
        val deletedHistory = prepareAudioSessions(remainingRows, listOf(transcripts.first()), cache.update(remainingRows))
        assertEquals("重复依据原文 · 先前片段 \n历史原文", deletedHistory.single().ui.claims.single().historyLabel)
    }

    @Test fun runtimeProjectionReusesContentAndPausesOnlyTheCapturingSession() {
        val rows = listOf(session(status = AudioSessionStatus.RECORDING),
            session("history", json = null, status = AudioSessionStatus.FAILED))
        val transcripts = listOf(transcript(21, "current", 2000, "当前原文"))
        val content = prepareAudioSessions(rows, transcripts, AudioSessionReportCache().update(rows))

        val paused = projectAudioSessions(content, setOf("current"), "current")
        assertEquals("录音已暂停", paused.first().statusLabel)
        assertTrue(paused.first().analyzing)
        assertSame(content.first().ui.segments, paused.first().segments)
        assertSame(content.first().ui.claims, paused.first().claims)
        assertSame(content.last().ui, paused.last())

        val resumed = projectAudioSessions(content, emptySet(), "history")
        assertEquals("正在录音", resumed.first().statusLabel)
        assertEquals("转写失败", resumed.last().statusLabel)
        assertFalse(resumed.first().analyzing)
        assertSame(content.first().ui, resumed.first())
    }
}
