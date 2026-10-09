package com.emerge.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: BrowseDatabase
    private lateinit var audio: AudioRepository
    private lateinit var history: BrowseRepository

    @Before fun prepare() {
        database = Room.inMemoryDatabaseBuilder(context, BrowseDatabase::class.java).build()
        audio = AudioRepository(database)
        history = BrowseRepository(database)
    }
    @After fun close() = database.close()

    private suspend fun session(packageName: String = "音频导入") = audio.createSession(
        packageName = packageName, source = AudioSource.IMPORT, title = "本地语音", startedAt = 1000L)

    @Test fun transcriptKeepsOriginalAndCorrectsDerivedTextWithoutInventingDwell() = runBlocking {
        val session = session()
        val segment = requireNotNull(audio.appendTranscript(session.id, session.generation, 0, 60_000, "岗位减少百分之二十"))
        val event = history.allEvents.first().single()
        assertEquals("audio_import", event.sourceType)
        assertEquals(0L, event.durationMillis)
        assertEquals(0, event.sampleCount)
        assertEquals(event.timestamp, event.endTimestamp)
        assertEquals(60_000L, event.mediaEndMillis)
        val beforeEdit = requireNotNull(audio.getSession(session.id))
        assertTrue(audio.setSessionAnalysis(session.id, session.generation, "old", beforeEdit.contentVersion))
        assertTrue(audio.editTranscript(segment.id, "岗位减少百分之二十五"))
        val corrected = audio.getTranscripts(session.id).single()
        assertEquals("岗位减少百分之二十", corrected.originalText)
        assertEquals("岗位减少百分之二十五", corrected.displayText)
        assertEquals(corrected.displayText, history.allEvents.first().single().visibleText)
        assertNull(audio.getSession(session.id)?.analysisJson)
        assertFalse(audio.setSessionAnalysis(session.id, session.generation, "stale", beforeEdit.contentVersion))
        assertTrue(audio.editTranscript(segment.id, null))
        assertEquals(segment.originalText, history.allEvents.first().single().visibleText)
    }

    @Test fun stableBatchIsIdempotentAndCanArriveAfterCompletion() = runBlocking {
        val session = session()
        assertTrue(audio.updateSessionStatus(session.id, session.generation, AudioSessionStatus.COMPLETED, durationMillis = 4000))
        val first = audio.appendTranscript(session.id, session.generation, 0, 4000, "同一条完整语句")
        val duplicate = audio.appendTranscript(session.id, session.generation, 0, 4000, "同一条完整语句")
        assertEquals(first?.id, duplicate?.id)
        assertEquals(1, history.count.first())
        assertEquals(AudioSessionStatus.COMPLETED, audio.getSession(session.id)?.status)
    }

    @Test fun changingHistoricalTranscriptInvalidatesLaterSavedAndPendingAnalysis() = runBlocking {
        val older = audio.createSession("com.video", AudioSource.IMPORT, "早先材料", startedAt = 1000)
        val first = requireNotNull(audio.appendTranscript(older.id, older.generation, 0, 1000, "依据是百分之二十"))
        val later = audio.createSession("com.video", AudioSource.IMPORT, "后续材料", startedAt = 2000)
        audio.appendTranscript(later.id, later.generation, 0, 1000, "依据是百分之三十")
        val snapshot = requireNotNull(audio.getSession(later.id))
        assertTrue(audio.setSessionAnalysis(later.id, later.generation, "引用早先材料的报告", snapshot.contentVersion))
        audio.editTranscript(first.id, "依据是百分之二十五")
        assertNull(audio.getSession(later.id)?.analysisJson)
        assertFalse(audio.setSessionAnalysis(later.id, later.generation, "修订之前的迟到报告", snapshot.contentVersion))
        val corrected = requireNotNull(audio.getSession(later.id))
        assertTrue(audio.setSessionAnalysis(later.id, later.generation, "新报告", corrected.contentVersion))
        audio.deleteSession(older.id)
        assertNull(audio.getSession(later.id)?.analysisJson)
        assertFalse(audio.setSessionAnalysis(later.id, later.generation, "删除之前的迟到报告", corrected.contentVersion))
    }

    @Test fun deletingAnEventInvalidatesItsSessionAndRejectsLateTranscripts() = runBlocking {
        val session = session()
        val first = requireNotNull(audio.appendTranscript(session.id, session.generation, 0, 2000, "第一条"))
        audio.appendTranscript(session.id, session.generation, 2000, 4000, "保留的第二条")
        history.deleteEvent(first.browseEventId)
        assertEquals(listOf("保留的第二条"), audio.getTranscripts(session.id).map { it.displayText })
        assertNull(audio.appendTranscript(session.id, session.generation, 4000, 6000, "删除前已在处理的第三条"))
        assertFalse(audio.updateSessionStatus(session.id, session.generation, AudioSessionStatus.COMPLETED))
        assertEquals(1, history.count.first())
    }

    @Test fun packageDeletionRemovesPendingSessionsAndLeavesOtherApps() = runBlocking {
        val deleted = session("com.video")
        session("com.video") // No transcript yet; this must also disappear.
        val retained = session("com.other")
        audio.appendTranscript(deleted.id, deleted.generation, 0, 1000, "删除这一条")
        audio.appendTranscript(retained.id, retained.generation, 0, 1000, "保留这一条")
        history.deletePackage("com.video")
        assertEquals(listOf(retained.id), audio.sessions.first().map { it.id })
        assertEquals(listOf("com.other"), history.allEvents.first().map { it.packageName })
        assertNull(audio.appendTranscript(deleted.id, deleted.generation, 1000, 2000, "迟到结果"))
    }

    @Test fun clearAndSessionDeletionCascadeAndDoNotResurrect() = runBlocking {
        val deleted = session()
        audio.appendTranscript(deleted.id, deleted.generation, 0, 1000, "待删除片段")
        audio.deleteSession(deleted.id)
        assertTrue(audio.getTranscripts(deleted.id).isEmpty())
        assertEquals(0, history.count.first())
        assertFalse(audio.setSessionAnalysis(deleted.id, deleted.generation, "late"))
        assertNull(audio.appendTranscript(deleted.id, deleted.generation, 1000, 2000, "迟到结果"))
        val pending = session()
        audio.appendTranscript(pending.id, pending.generation, 0, 1000, "待清空片段")
        history.clear()
        assertTrue(audio.sessions.first().isEmpty())
        assertTrue(audio.allTranscripts.first().isEmpty())
        assertEquals(0, history.count.first())
        assertNull(audio.appendTranscript(pending.id, pending.generation, 1000, 2000, "迟到结果"))
    }

    @Test fun migrationFromVersionTwoPreservesBrowsingAndEnablesAudioForeignKeys() = runBlocking {
        val name = "migration-audio-qa.db"
        context.deleteDatabase(name)
        try {
            context.openOrCreateDatabase(name, 0, null).use { db ->
                db.execSQL("CREATE TABLE browse_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, packageName TEXT NOT NULL, timestamp INTEGER NOT NULL, eventType INTEGER NOT NULL, visibleText TEXT NOT NULL, endTimestamp INTEGER NOT NULL DEFAULT 0, durationMillis INTEGER NOT NULL DEFAULT 0, sampleCount INTEGER NOT NULL DEFAULT 0)")
                db.execSQL("INSERT INTO browse_events VALUES (7, 'com.reader', 1000, 32, '原有浏览文字', 9000, 8000, 4)")
                db.version = 2
            }
            val migrated = Room.databaseBuilder(context, BrowseDatabase::class.java, name)
                .addMigrations(BrowseDatabase.MIGRATION_2_3, BrowseDatabase.MIGRATION_3_4).build()
            try {
                val old = BrowseRepository(migrated).allEvents.first().single()
                assertEquals(7L, old.id)
                assertEquals("原有浏览文字", old.visibleText)
                assertEquals(8000L, old.durationMillis)
                assertEquals(4, old.sampleCount)
                assertEquals("accessibility", old.sourceType)
                assertNull(old.audioSessionId)
                val migratedAudio = AudioRepository(migrated)
                val new = migratedAudio.createSession("音频导入", AudioSource.IMPORT, "升级后录音")
                assertNotNull(migratedAudio.appendTranscript(new.id, new.generation, 0, 1000, "升级后转写"))
                migratedAudio.deleteSession(new.id)
                assertEquals(listOf(7L), BrowseRepository(migrated).allEvents.first().map { it.id })
                assertTrue(migratedAudio.allTranscripts.first().isEmpty())
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }
}
