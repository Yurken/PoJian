package com.emerge.app.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.analysis.ArgumentUnit
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.analysis.SemanticsCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
            BrowseDatabase.MIGRATION_3_4, BrowseDatabase.MIGRATION_4_5, BrowseDatabase.MIGRATION_5_6).build()

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

    @Test fun resultSurvivesReopeningWithoutChangingVisitsOrDuration() = runBlocking {
        val name = "semantic-restart-${UUID.randomUUID()}.db"
        context.deleteDatabase(name)
        try {
            val initial = database(name)
            val saved = try {
                val repository = BrowseRepository(initial)
                val event = encounter(repository)
                val result = semantics(event)
                assertTrue(repository.saveSemantics(event, result, repository.captureGeneration) { true })
                event to result
            } finally { initial.close() }
            val reopened = database(name)
            try {
                val repository = BrowseRepository(reopened)
                assertEquals(saved.second, repository.semantics.first().single())
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
                .addMigrations(BrowseDatabase.MIGRATION_4_5, BrowseDatabase.MIGRATION_5_6).build()
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
