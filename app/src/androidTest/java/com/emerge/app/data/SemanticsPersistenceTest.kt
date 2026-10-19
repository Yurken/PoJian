package com.emerge.app.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.analysis.ArgumentUnit
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.analysis.SemanticsCodec
import com.emerge.app.analysis.ClaimPresentation
import com.emerge.app.analysis.ImpactLevel
import com.emerge.app.analysis.ImpactSignal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SemanticsPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database(name: String? = null): BrowseDatabase =
        if (name == null) Room.inMemoryDatabaseBuilder(context, BrowseDatabase::class.java).build()
        else Room.databaseBuilder(context, BrowseDatabase::class.java, name).addMigrations(
            BrowseDatabase.MIGRATION_1_2, BrowseDatabase.MIGRATION_2_3,
            BrowseDatabase.MIGRATION_3_4, BrowseDatabase.MIGRATION_4_5, BrowseDatabase.MIGRATION_5_6, BrowseDatabase.MIGRATION_6_7, BrowseDatabase.MIGRATION_7_8, BrowseDatabase.MIGRATION_8_9).build()

    private suspend fun encounter(repository: BrowseRepository, text: String = "岗位正在减少，因为招聘数量下降"):
        BrowseEvent {
        val original = BrowseEvent(packageName = "com.reader", timestamp = 1000, eventType = 32,
            visibleText = text, endTimestamp = 6000, durationMillis = 5000, sampleCount = 2)
        return original.copy(id = repository.record(original))
    }

    private fun semantics(event: BrowseEvent, at: Long = 2000) = EventSemantics(
        eventId = event.id, inputText = event.visibleText, analyzedAt = at,
        claims = listOf(ArgumentUnit(quote = "岗位正在减少", topic = "就业", subject = "岗位",
            dimension = "需求", stance = "减少", evidence = "招聘数量下降", uncertain = false)))

    @Test fun pagedSnapshotKeepsEveryValidRowAndVersionOrderAcrossPageBoundaries() = runBlocking {
        val db = database()
        try {
            val dao = db.browseEventDao()
            val expected = mutableListOf<EventSemantics>()
            db.withTransaction {
                repeat(259) { index ->
                    val row = BrowseEvent(packageName = "reader", timestamp = index.toLong(), eventType = 32,
                        visibleText = "岗位正在减少，因为招聘数量下降")
                    val event = row.copy(id = dao.insert(row))
                    val result = semantics(event, (index % 7).toLong())
                    val stored = SemanticsCodec.encode(result)
                    // Include a corrupt row at the end of the first page.
                    dao.putSemantics(if (index == 127) stored.copy(claimsJson = "invalid") else stored)
                    if (index != 127) expected += result
                }
            }
            assertEquals(expected.sortedWith(compareByDescending<EventSemantics> { it.analyzedAt }
                .thenByDescending { it.eventId }), BrowseRepository(db).semantics.first())
        } finally { db.close() }
    }

    @Test fun sameCountReanalysisAndDeletionRefreshAnActiveSnapshot() = runBlocking {
        val db = database()
        val repository = BrowseRepository(db)
        val snapshots = Channel<List<EventSemantics>>(Channel.UNLIMITED)
        val collector = launch { repository.semantics.collect { snapshots.send(it) } }
        try {
            withTimeout(10_000) {
                assertTrue(snapshots.receive().isEmpty())
                val event = encounter(repository)
                val first = semantics(event, 3000)
                assertTrue(repository.saveSemantics(event, first, repository.captureGeneration) { true })
                assertEquals(listOf(first), snapshots.receive())
                val replacement = first.copy(analyzedAt = 4000, claims = emptyList())
                assertTrue(repository.saveSemantics(event, replacement, repository.captureGeneration) { true })
                assertEquals(listOf(replacement), snapshots.receive())
                db.browseEventDao().deleteEvent(event.id)
                assertTrue(snapshots.receive().isEmpty())
            }
        } finally {
            collector.cancelAndJoin()
            snapshots.close()
            db.close()
        }
    }

    @Test fun collectorsReuseDecodedValuesButSameVersionEditsStillReplaceThem() = runBlocking {
        val db = database()
        try {
            val repository = BrowseRepository(db)
            val event = encounter(repository)
            val first = semantics(event)
            db.browseEventDao().putSemantics(SemanticsCodec.encode(first))
            val loaded = repository.semantics.first().single()
            assertSame(loaded, repository.semantics.first().single())
            val changed = first.copy(claims = first.claims.map { it.copy(stance = "质疑") })
            db.browseEventDao().putSemantics(SemanticsCodec.encode(changed))
            assertEquals(changed, repository.semantics.first().single())
            db.browseEventDao().putSemantics(SemanticsCodec.encode(changed).copy(claimsJson = "invalid"))
            assertTrue(repository.semantics.first().isEmpty())
            db.browseEventDao().deleteEvent(event.id)
            assertTrue(repository.semantics.first().isEmpty())
        } finally { db.close() }
    }

    @Test fun resultSurvivesReopeningWithoutChangingVisitsOrDuration() = runBlocking {
        val name = "semantic-restart-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        try {
            val initial = database(name)
            val saved = try {
                val repository = BrowseRepository(initial)
                val event = encounter(repository, "岗位正在减少，因为小林引用《就业报告》描述招聘数量下降")
                val result = semantics(event).copy(claims = listOf(semantics(event).claims.single().copy(
                    evidence = "小林引用《就业报告》描述招聘数量下降", evidenceActors = listOf("小林"),
                    evidenceOrigins = listOf("《就业报告》"),
                    presentation = ClaimPresentation("就业", "招聘岗位正在减少",
                        ImpactSignal(ImpactLevel.MEDIUM, "连续接触可能放大就业担忧。", "岗位正在减少"),
                        ImpactSignal(ImpactLevel.UNKNOWN, "缺少长期接触数据。", ""),
                        ImpactSignal(ImpactLevel.UNKNOWN, "缺少判断适用范围的信息。", ""),
                        listOf("比较不同地区和时间的招聘数据。")))))
                assertTrue(repository.saveSemantics(event, result, repository.captureGeneration) { true })
                event to result
            } finally { initial.close() }
            val reopened = database(name)
            try {
                val repository = BrowseRepository(reopened)
                assertEquals(saved.second, repository.semantics.first().single())
                assertEquals(listOf("小林"), repository.semantics.first().single().claims.single().evidenceActors)
                assertEquals(listOf("《就业报告》"), repository.semantics.first().single().claims.single().evidenceOrigins)
                assertEquals(saved.first, repository.findEvent(saved.first.id))
                assertEquals(1, repository.count.first())
                assertEquals(5000L, repository.allEvents.first().single().durationMillis)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun olderResultCannotReplaceNewerResultAndEmptyClaimsAreDurable() = runBlocking {
        val db = database()
        try {
            val repository = BrowseRepository(db)
            val event = encounter(repository)
            val result = semantics(event, 3000)
            assertTrue(repository.saveSemantics(event, result, repository.captureGeneration) { true })
            assertFalse(repository.saveSemantics(event, semantics(event, 2000), repository.captureGeneration) { true })
            assertEquals(result, repository.semantics.first().single())
            val empty = result.copy(analyzedAt = 4000, claims = emptyList())
            assertTrue(repository.saveSemantics(event, empty, repository.captureGeneration) { true })
            assertEquals(empty, repository.semantics.first().single())
            assertEquals(1, repository.count.first())
        } finally { db.close() }
    }

    @Test fun textGrowthAndCorrectionsRejectOldInputWithoutManufacturingEvents() = runBlocking {
        val db = database()
        try {
            val repository = BrowseRepository(db)
            val event = encounter(repository)
            val result = semantics(event)
            assertTrue(repository.saveSemantics(event, result, repository.captureGeneration) { true })
            val expanded = event.copy(visibleText = event.visibleText + "，但部分地区新增招聘")
            assertEquals(event.id, repository.updateSession(expanded))
            assertFalse(repository.saveSemantics(event, result.copy(analyzedAt = 3000), repository.captureGeneration) { true })
            assertFalse(repository.semantics.first().single().validFor(expanded))
            val replacement = semantics(expanded, 4000)
            assertTrue(repository.saveSemantics(expanded, replacement, repository.captureGeneration) { true })
            val corrected = expanded.copy(visibleText = "岗位需求尚未确认")
            repository.updateSession(corrected)
            assertFalse(repository.semantics.first().single().validFor(corrected))
            assertEquals(1, repository.count.first())
        } finally { db.close() }
    }

    @Test fun deletionCascadesAndLateResultsCannotRecreateTheEvent() = runBlocking {
        val db = database()
        try {
            val repository = BrowseRepository(db)
            val event = encounter(repository)
            val generation = repository.captureGeneration
            assertTrue(repository.saveSemantics(event, semantics(event), generation) { true })
            repository.deleteEvent(event.id)
            assertTrue(repository.semantics.first().isEmpty())
            assertNull(repository.getEvent(event.id))
            assertFalse(repository.saveSemantics(event, semantics(event), generation) { true })
            assertFalse(repository.saveSemantics(event, semantics(event), repository.captureGeneration) { true })
            assertEquals(0, repository.count.first())
        } finally { db.close() }
    }

    @Test fun permissionsGenerationAndUnanchoredQuotesRejectStorage() = runBlocking {
        val db = database()
        try {
            val repository = BrowseRepository(db)
            val event = encounter(repository)
            val generation = repository.captureGeneration
            assertFalse(repository.saveSemantics(event, semantics(event), generation) { false })
            val unanchored = semantics(event).copy(claims = listOf(ArgumentUnit(
                quote = "原文中不存在的句子", topic = "就业", subject = "岗位", dimension = "需求", stance = "减少")))
            assertFalse(repository.saveSemantics(event, unanchored, generation) { true })
            repository.deletePackage("com.other")
            assertFalse(repository.saveSemantics(event, semantics(event), generation) { true })
            assertTrue(repository.semantics.first().isEmpty())
            assertEquals(1, repository.count.first())
        } finally { db.close() }
    }

    @Test fun corruptStoredRowIsIgnoredAndClearKeepsTheOriginalEvidence() = runBlocking {
        val db = database()
        try {
            val repository = BrowseRepository(db)
            val event = encounter(repository)
            db.browseEventDao().putSemantics(BrowseSemantic(event.id, event.visibleText, 2000, "[broken", "text"))
            assertTrue(repository.semantics.first().isEmpty())
            assertTrue(repository.saveSemantics(event, semantics(event), repository.captureGeneration) { true })
            repository.clearSemantics()
            assertTrue(repository.semantics.first().isEmpty())
            assertEquals(event, repository.findEvent(event.id))
            assertEquals(1, repository.count.first())
        } finally { db.close() }
    }

    @Test fun codecPreservesUnknownAttributionAndRejectsAStringBoolean() {
        val event = BrowseEvent(id = 7, packageName = "com.reader", timestamp = 0, eventType = 0,
            visibleText = "有人表示岗位正在减少，但依据尚不清楚")
        val result = EventSemantics(event.id, event.visibleText, 0,
            listOf(ArgumentUnit("岗位正在减少", "就业", "岗位", "需求", "减少")), "audio")
        val encoded = SemanticsCodec.encode(result)
        assertEquals(result, SemanticsCodec.decode(encoded))
        assertNull(SemanticsCodec.decode(encoded.copy(claimsJson = encoded.claimsJson.replace(
            "\"uncertain\":true", "\"uncertain\":\"true\""))))
    }

    @Test fun storedLegacyClaimsWithoutEvidenceIdentityFieldsRemainReadable() = runBlocking {
        val db = database()
        try {
            val repository = BrowseRepository(db)
            val event = encounter(repository)
            val result = semantics(event)
            val encoded = SemanticsCodec.encode(result)
            val legacy = JSONArray(encoded.claimsJson).apply {
                getJSONObject(0).remove("evidence_actors")
                getJSONObject(0).remove("evidence_origins")
            }
            db.browseEventDao().putSemantics(encoded.copy(claimsJson = legacy.toString()))
            val restored = repository.semantics.first().single()
            assertEquals(result, restored)
            assertTrue(restored.claims.single().evidenceActors.isEmpty())
            assertTrue(restored.claims.single().evidenceOrigins.isEmpty())
            assertEquals(event, repository.findEvent(event.id))
        } finally { db.close() }
    }

    @Test fun invalidEvidenceIdentityRejectsTheWholeStoredResultAndCannotBeSaved() = runBlocking {
        val db = database()
        try {
            val repository = BrowseRepository(db)
            val event = encounter(repository, "岗位正在减少，因为小林引用《就业报告》描述招聘数量下降，阿明转述")
            val unit = semantics(event).claims.single().copy(evidence = "小林引用《就业报告》描述招聘数量下降",
                evidenceActors = listOf("小林"), evidenceOrigins = listOf("《就业报告》"))
            val result = semantics(event).copy(claims = listOf(unit))
            assertTrue(repository.saveSemantics(event, result, repository.captureGeneration) { true })
            assertFalse(repository.saveSemantics(event, result.copy(analyzedAt = 3000,
                claims = listOf(unit.copy(evidenceActors = listOf("阿明")))), repository.captureGeneration) { true })
            assertFalse(repository.saveSemantics(event, result.copy(analyzedAt = 3000,
                claims = listOf(unit.copy(evidenceOrigins = listOf("虚构报告")))), repository.captureGeneration) { true })
            assertEquals(result, repository.semantics.first().single())
            val encoded = SemanticsCodec.encode(result)
            val invalidValues: List<Any> = listOf("小林", JSONObject.NULL, 7, JSONObject(),
                JSONArray().put(7), JSONArray().put(JSONObject.NULL), JSONArray().put(""),
                JSONArray().put(" "), JSONArray().put("小林").put("小林"), JSONArray().put("阿明"),
                JSONArray().put("甲".repeat(81)), JSONArray(listOf("小", "林", "引用", "报告", "描述")))
            listOf("evidence_actors", "evidence_origins").forEach { field ->
                invalidValues.forEach { value ->
                    val rows = JSONArray(encoded.claimsJson)
                    val invalidClaim = JSONObject(rows.getJSONObject(0).toString()).put(field, value)
                    rows.put(invalidClaim) // One valid claim must not conceal a malformed second claim.
                    val corrupt = encoded.copy(claimsJson = rows.toString())
                    assertNull("$field=$value", SemanticsCodec.decode(corrupt))
                    db.browseEventDao().putSemantics(corrupt)
                    assertTrue("$field=$value", repository.semantics.first().isEmpty())
                }
            }
            val noEvidence = unit.copy(evidence = "")
            assertNull(SemanticsCodec.decode(SemanticsCodec.encode(result.copy(claims = listOf(noEvidence)))))
            assertEquals(event, repository.findEvent(event.id))
            assertEquals(1, repository.count.first())
        } finally { db.close() }
    }

    @Test fun migrationFromVersionFourPreservesOriginalRowsAndEnablesCascadingSemantics() = runBlocking {
        val name = "semantic-migration-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name).callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE browse_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, packageName TEXT NOT NULL, timestamp INTEGER NOT NULL, eventType INTEGER NOT NULL, visibleText TEXT NOT NULL)")
                        db.execSQL("INSERT INTO browse_events VALUES (7, 'com.reader', 1000, 32, '岗位正在减少，因为招聘数量下降')")
                        BrowseDatabase.MIGRATION_1_2.migrate(db)
                        BrowseDatabase.MIGRATION_2_3.migrate(db)
                        BrowseDatabase.MIGRATION_3_4.migrate(db)
                        db.execSQL("UPDATE browse_events SET durationMillis=5000, endTimestamp=6000, sampleCount=2, workKey='legacy-work', identityMethod='author_title' WHERE id=7")
                        db.execSQL("INSERT INTO observed_works VALUES ('legacy-work', 'com.reader', '旧作品', '旧作者', 'author_title', 1000)")
                        db.execSQL("INSERT INTO browse_samples (visitId,capturedAt,eventType,visibleText) VALUES (7,1000,32,'旧采样')")
                        db.execSQL("INSERT INTO audio_sessions (id,packageName,source,title,startedAt) VALUES ('old-audio','com.reader','import','旧录音',2000)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("Unexpected fixture upgrade")
                }).build())
            try { helper.writableDatabase } finally { helper.close() }
            val migrated = Room.databaseBuilder(context, BrowseDatabase::class.java, name)
                .addMigrations(BrowseDatabase.MIGRATION_4_5, BrowseDatabase.MIGRATION_5_6, BrowseDatabase.MIGRATION_6_7, BrowseDatabase.MIGRATION_7_8, BrowseDatabase.MIGRATION_8_9).build()
            try {
                val repository = BrowseRepository(migrated)
                val event = requireNotNull(repository.findEvent(7))
                assertEquals("岗位正在减少，因为招聘数量下降", event.visibleText)
                assertEquals(5000L, event.durationMillis)
                assertEquals("legacy-work", event.workKey)
                assertEquals(2, event.sampleCount)
                assertEquals("old-audio", migrated.audioDao().observeSessions().first().single().id)
                migrated.openHelper.readableDatabase.query("SELECT visibleText FROM browse_samples WHERE visitId=7").use {
                    assertTrue(it.moveToFirst()); assertEquals("旧采样", it.getString(0))
                }
                assertTrue(repository.semantics.first().isEmpty())
                assertTrue(repository.saveSemantics(event, semantics(event), repository.captureGeneration) { true })
                assertEquals(1, repository.count.first())
                repository.deleteEvent(7)
                assertTrue(repository.semantics.first().isEmpty())
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
