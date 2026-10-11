package com.emerge.app.analysis

import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.data.BrowseDatabase
import com.emerge.app.data.BrowseEvent
import com.emerge.app.data.BrowseRepository
import com.emerge.app.ui.StatisticsPeriod
import java.io.File
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual Room readback and exported citations without the app's database or cloud settings. */
@RunWith(AndroidJUnit4::class)
class EvidenceCoveragePersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val firstEvidence = "报告松岚记录岗位数量同比减少24%"
    private val secondEvidence = "报告海峰记录培训预算同比减少36%"
    private val claim = "制造企业的招聘需求正在下降"
    private val now = Instant.parse("2026-10-11T04:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")

    private data class Saved(val first: BrowseEvent, val second: BrowseEvent, val merged: BrowseEvent)

    private fun semantics(event: BrowseEvent, evidence: String, analyzedAt: Long): EventSemantics =
        EventSemantics(event.id, event.visibleText, analyzedAt, listOf(ArgumentUnit(claim, "就业与职场",
            "制造业招聘需求", "岗位数量", "下降", evidence, "当前", uncertain = false,
            evidenceOrigins = listOf("报告松岚", "报告海峰").filter(evidence::contains))))

    private suspend fun seed(repository: BrowseRepository): Saved {
        suspend fun save(evidence: String, timestamp: Long, order: Int): BrowseEvent {
            val raw = BrowseEvent(packageName = "fixture.coverage.reader", timestamp = timestamp, eventType = 32,
                visibleText = "$claim，因为$evidence", endTimestamp = timestamp + 1000,
                durationMillis = 1000, sampleCount = 2)
            val event = raw.copy(id = repository.record(raw))
            assertTrue(repository.saveSemantics(event, semantics(event, evidence, now + order),
                repository.captureGeneration) { true })
            return event
        }
        val saved = Saved(save(firstEvidence, now - 2 * 86_400_000L, 1),
            save(secondEvidence, now - 86_400_000L, 2),
            save("$firstEvidence;$secondEvidence", now - 1_800_000L, 3))
        assertEquals(listOf(1L, 2L, 3L), listOf(saved.first.id, saved.second.id, saved.merged.id))
        assertEquals(3, repository.semantics.first().size)
        return saved
    }

    private suspend fun report(repository: BrowseRepository) = CognitiveAnalyzer().analyze(
        repository.allEvents.first(), StatisticsPeriod.DAY, now, zone,
        purpose = "合成仪器测试：历史引用链", semantics = repository.semantics.first())

    private fun assertUnion(report: CognitiveReport, saved: Saved): ClaimObservation {
        // Prior records are comparison history, so citations cannot pass merely by appearing in their own appendix.
        assertEquals(1, report.rawRecords)
        assertEquals(2, report.comparisonRecords)
        assertEquals(1, report.structuredAnalyzedRecords)
        val observation = report.claims.single()
        assertEquals(saved.merged.id, observation.eventId)
        assertEquals(Increment.REPEATED, observation.increment)
        assertEquals(setOf(saved.first.id, saved.second.id), observation.comparedHistoryEventIds.toSet())
        assertEquals(listOf(firstEvidence, secondEvidence), observation.evidence)
        assertEquals(2, observation.evidenceDetails.size)
        val first = observation.evidenceDetails[0]
        val second = observation.evidenceDetails[1]
        assertEquals(listOf(EvidenceHistoryMatch(saved.first.id, firstEvidence)), first.historyMatches)
        assertEquals(listOf(EvidenceHistoryMatch(saved.second.id, secondEvidence)), second.historyMatches)
        assertEquals(listOf("报告松岚"), first.identity.origins)
        assertEquals(listOf("报告海峰"), second.identity.origins)
        assertFalse(first.isNew)
        assertFalse(second.isNew)
        return observation
    }

    private fun assertCitations(text: String, saved: Saved, fromPdf: Boolean = false) {
        fun compact(value: String) = value.replace(Regex("\\s+"), "")
        val flattened = compact(text)
        val firstBlock = "同一记录原文：$firstEvidence" + "原材料线索：报告松岚" +
            "历史依据记录 #${saved.first.id} · 原句：$firstEvidence"
        val secondBlock = "同一记录原文：$secondEvidence" + "原材料线索：报告海峰" +
            "历史依据记录 #${saved.second.id} · 原句：$secondEvidence"
        fun diagnostic(expected: String): String {
            if (!fromPdf) return ""
            val at = flattened.indexOf("松岚").takeIf { it >= 0 } ?: (flattened.length - 1800).coerceAtLeast(0)
            val excerpt = flattened.substring((at - 120).coerceAtLeast(0), (at + 1800).coerceAtMost(flattened.length))
            return "\nExpected compact citation: ${compact(expected)}\nExtracted compact excerpt: $excerpt"
        }
        assertTrue("First atom must cite its own original record and material${diagnostic(firstBlock)}",
            flattened.contains(compact(firstBlock)))
        assertTrue("Second atom must cite its own original record and material${diagnostic(secondBlock)}",
            flattened.contains(compact(secondBlock)))
        assertFalse(flattened.contains(compact("历史依据记录 #${saved.first.id} · 原句：$secondEvidence")))
        assertFalse(flattened.contains(compact("历史依据记录 #${saved.second.id} · 原句：$firstEvidence")))
    }

    @Test fun storedRowsFeedUnionCoverageAndOriginalHistoricalCitationsIntoText() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, BrowseDatabase::class.java).build()
        try {
            val repository = BrowseRepository(db)
            val saved = seed(repository)
            val restored = repository.semantics.first().associateBy { it.eventId }
            assertEquals(firstEvidence, restored.getValue(saved.first.id).claims.single().evidence)
            assertEquals(secondEvidence, restored.getValue(saved.second.id).claims.single().evidence)
            assertEquals(listOf("报告松岚", "报告海峰"),
                restored.getValue(saved.merged.id).claims.single().evidenceOrigins)
            val result = report(repository)
            assertUnion(result, saved)
            assertCitations(ReportExporter.text(result), saved)
        } finally { db.close() }
    }

    @Test fun pdfContainsActualOriginalQuotesAndPerAtomSourceReferencesFromRoom() = runBlocking {
        // API 35 adds public PDF text contents; reflection keeps the application's compile SDK at 34.
        assumeTrue("PDF text extraction requires Android 35+", Build.VERSION.SDK_INT >= 35)
        val db = Room.inMemoryDatabaseBuilder(context, BrowseDatabase::class.java).build()
        val file = File.createTempFile("evidence-coverage-", ".pdf", context.cacheDir)
        try {
            val repository = BrowseRepository(db)
            val saved = seed(repository)
            val result = report(repository)
            assertUnion(result, saved)
            file.outputStream().use { ReportExporter.writePdf(result, it) }
            assertTrue(file.length() > 500)
            val content = StringBuilder()
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                assertTrue(renderer.pageCount > 0)
                for (index in 0 until renderer.pageCount) renderer.openPage(index).use { page ->
                    val chunks = page.javaClass.getMethod("getTextContents").invoke(page) as List<*>
                    chunks.forEach { chunk ->
                        val item = requireNotNull(chunk)
                        val extracted = item.javaClass.getMethod("getText").invoke(item) as String
                        content.append(extracted)
                        content.append('\n')
                    }
                }
            }
            // Android's PDF font extraction returned 同⼀/原⽂/同⽐ for the visible 同一/原文/同比.
            // Fold only compatibility radical/ideograph glyph aliases; leave TXT, numbers and punctuation literal.
            val glyphNormalized = buildString {
                content.forEach { character ->
                    if (character in '\u2E80'..'\u2EFF' || character in '\u2F00'..'\u2FDF' ||
                        character in '\uF900'..'\uFAFF')
                        append(Normalizer.normalize(character.toString(), Normalizer.Form.NFKC))
                    else append(character)
                }
            }
            assertCitations(glyphNormalized, saved, fromPdf = true)
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test fun deletingAnEarlierSavedRowRemovesOnlyItsCoverageAndCitations() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, BrowseDatabase::class.java).build()
        try {
            val repository = BrowseRepository(db)
            val saved = seed(repository)
            assertUnion(report(repository), saved)
            repository.deleteEvent(saved.first.id)
            assertNull(repository.findEvent(saved.first.id))
            assertFalse(repository.semantics.first().any { it.eventId == saved.first.id })
            val result = report(repository)
            val observation = result.claims.single()
            assertEquals(Increment.NEW, observation.increment)
            assertEquals(listOf(saved.second.id), observation.comparedHistoryEventIds)
            assertTrue(observation.evidenceDetails[0].isNew)
            assertTrue(observation.evidenceDetails[0].historyMatches.isEmpty())
            assertFalse(observation.evidenceDetails[1].isNew)
            assertEquals(listOf(EvidenceHistoryMatch(saved.second.id, secondEvidence)),
                observation.evidenceDetails[1].historyMatches)
            val text = ReportExporter.text(result)
            assertFalse(text.contains("历史依据记录 #${saved.first.id}"))
            assertTrue(text.contains("历史依据记录 #${saved.second.id} · 原句：$secondEvidence"))
        } finally { db.close() }
    }

    @Test fun inputEditRejectsOldSemanticsAndReplacementRecomputesHistoricalCoverage() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, BrowseDatabase::class.java).build()
        try {
            val repository = BrowseRepository(db)
            val saved = seed(repository)
            assertUnion(report(repository), saved)
            val oldSemantics = repository.semantics.first().single { it.eventId == saved.second.id }
            val editedEvidence = "报告海峰记录培训预算同比减少42%"
            val edited = saved.second.copy(visibleText = "$claim，因为$editedEvidence")
            assertEquals(saved.second.id, repository.updateSession(edited))
            assertFalse(oldSemantics.validFor(requireNotNull(repository.findEvent(saved.second.id))))
            assertFalse(repository.saveSemantics(saved.second, oldSemantics.copy(analyzedAt = now + 10),
                repository.captureGeneration) { true })
            val beforeReplacement = report(repository).claims.single()
            assertEquals(Increment.NEW, beforeReplacement.increment)
            assertTrue(beforeReplacement.evidenceDetails[1].historyMatches.isEmpty())
            assertTrue(repository.saveSemantics(edited, semantics(edited, editedEvidence, now + 20),
                repository.captureGeneration) { true })
            assertEquals(editedEvidence, repository.semantics.first()
                .single { it.eventId == edited.id }.claims.single().evidence)
            val result = report(repository)
            val observation = result.claims.single()
            assertEquals(Increment.NEW, observation.increment)
            assertEquals(listOf(EvidenceHistoryMatch(saved.first.id, firstEvidence)),
                observation.evidenceDetails[0].historyMatches)
            assertTrue(observation.evidenceDetails[1].isNew)
            assertTrue(observation.evidenceDetails[1].historyMatches.isEmpty())
            assertFalse(ReportExporter.text(result).contains("历史依据记录 #${saved.second.id}"))
            assertEquals(3, repository.count.first())
            assertEquals(saved.merged, repository.findEvent(saved.merged.id))
        } finally { db.close() }
    }
}
