package com.emerge.app.data

import androidx.room.Room
import androidx.room.withTransaction
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
class LocalStorageRetentionTest {
    private lateinit var db: BrowseDatabase
    private lateinit var repository: BrowseRepository
    private lateinit var audio: AudioRepository

    @Before fun prepare() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            BrowseDatabase::class.java).build()
        repository = BrowseRepository(db)
        audio = AudioRepository(db)
    }
    @After fun close() = db.close()

    private suspend fun visit(at: Long, text: String = "页面") = repository.record(
        BrowseEvent(packageName = "reader", timestamp = at, eventType = 32, visibleText = text,
            endTimestamp = at + 10_000, durationMillis = 10_000, workKey = "work:$at",
            visibilityIntervals = "v1;$at,${at + 10_000},10000,m"))

    @Test fun historyBeyondFiveThousandIsRetainedUntilCapacityRequiresEviction() = runBlocking {
        db.withTransaction {
            repeat(5000) { db.browseEventDao().insert(BrowseEvent(packageName = "reader", timestamp = it.toLong(),
                eventType = 32, visibleText = "历史")) }
        }
        visit(6000)
        assertEquals(5001, repository.count.first())
    }

    @Test fun evictingOldVisitRemovesItsMaterialButProtectsNewestVisitAndStaleWrites() = runBlocking {
        val old = visit(1000)
        val latest = visit(2000)
        val child = db.browseEventDao().insert(BrowseEvent(packageName = "reader", timestamp = 1500,
            eventType = 0, visibleText = "图像文字", sourceType = "visual_model", parentEventId = old))
        db.browseEventDao().insertSample(BrowseSample(visitId = old, capturedAt = 1500, eventType = 32, visibleText = "采样"))
        db.browseEventDao().putSemantics(BrowseSemantic(child, "图像文字", 1600, "[]", "visual_model"))
        val snapshot = requireNotNull(repository.getEvent(old))
        val generation = repository.captureGeneration

        assertTrue(repository.evictOldestStorageUnit())
        assertNull(repository.getEvent(old))
        assertNull(repository.getEvent(child))
        assertNull(db.browseEventDao().lastSampleText(old))
        assertNull(db.browseEventDao().semanticsForEvent(child))
        assertNotNull(repository.getEvent(latest))
        assertNull(repository.saveCapture(snapshot, true, generation) { true })
        assertFalse(repository.evictOldestStorageUnit())
    }

    @Test fun activeAudioAndItsOlderParentRemainWhileWholeCompletedSessionIsEvicted() = runBlocking {
        val parent = visit(1000)
        val active = audio.createSession("reader", AudioSource.PLAYBACK, "进行中", startedAt = 1000)
        audio.updateSessionStatus(active.id, active.generation, AudioSessionStatus.RECORDING)
        val ongoing = requireNotNull(audio.appendTranscript(active.id, active.generation, 0, 1000, "正在转写"))
        assertEquals(parent, repository.getEvent(ongoing.browseEventId)?.parentEventId)
        val done = audio.createSession("import", AudioSource.IMPORT, "已结束", startedAt = 500)
        val first = requireNotNull(audio.appendTranscript(done.id, done.generation, 0, 1000, "第一段"))
        val second = requireNotNull(audio.appendTranscript(done.id, done.generation, 1000, 2000, "第二段"))
        audio.updateSessionStatus(done.id, done.generation, AudioSessionStatus.COMPLETED)
        val latest = visit(20_000)

        assertTrue(repository.evictOldestStorageUnit())
        assertNull(audio.getSession(done.id))
        assertNull(repository.getEvent(first.browseEventId))
        assertNull(repository.getEvent(second.browseEventId))
        assertNull(audio.appendTranscript(done.id, done.generation, 2000, 3000, "迟到结果"))
        assertNotNull(audio.getSession(active.id))
        assertNotNull(repository.getEvent(parent))
        assertNotNull(repository.getEvent(latest))
        assertFalse(repository.evictOldestStorageUnit())
    }

    @Test fun completedAudioAttachedToNewestVisitIsAlsoProtected() = runBlocking {
        val latest = visit(1000)
        val session = audio.createSession("reader", AudioSource.PLAYBACK, "录音", startedAt = 1000)
        val transcript = requireNotNull(audio.appendTranscript(session.id, session.generation, 0, 1000, "这一页的录音"))
        assertEquals(latest, repository.getEvent(transcript.browseEventId)?.parentEventId)
        audio.updateSessionStatus(session.id, session.generation, AudioSessionStatus.COMPLETED)
        assertFalse(repository.evictOldestStorageUnit())
        assertNotNull(audio.getSession(session.id))
    }

    @Test fun trimmingOldHistoryDoesNotRevokeAnUnrelatedNewVisitSampledBeforeCleanup() = runBlocking {
        visit(1000)
        visit(2000)
        val generation = repository.captureGeneration
        val next = BrowseEvent(packageName = "reader", timestamp = 3000, eventType = 32, visibleText = "新作品")
        assertTrue(repository.evictOldestStorageUnit())
        val id = repository.saveCapture(next, false, generation) { true }
        assertNotNull(id)
        assertEquals("新作品", repository.getEvent(requireNotNull(id))?.visibleText)
    }

    @Test fun snapshotHistoryHasNoGlobalFiveThousandCapButEachVisitRemainsBounded() = runBlocking {
        val dao = db.browseEventDao()
        db.withTransaction {
            repeat(50) { index ->
                val id = dao.insert(BrowseEvent(packageName = "reader", timestamp = index.toLong(),
                    eventType = 32, visibleText = "旧作品"))
                repeat(100) { dao.insertSample(BrowseSample(visitId = id, capturedAt = it.toLong(), eventType = 32,
                    visibleText = "采样 $it")) }
            }
        }
        val id = visit(20_000)
        repeat(121) { index ->
            val row = requireNotNull(repository.getEvent(id))
            assertEquals(id, repository.saveCapture(row, true, repository.captureGeneration,
                sampleText = "新采样 $index") { true })
        }
        val total = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM browse_samples").use {
            it.moveToFirst(); it.getInt(0)
        }
        assertEquals(5120, total)
    }
}
