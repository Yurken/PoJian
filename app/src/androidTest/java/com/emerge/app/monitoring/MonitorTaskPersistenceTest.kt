package com.emerge.app.monitoring

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.data.AudioSession
import com.emerge.app.data.BrowseDatabase
import com.emerge.app.data.BrowseEvent
import com.emerge.app.data.BrowseRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises real Room transactions and Android Keystore; no remote model or screenshot is involved. */
@RunWith(AndroidJUnit4::class)
class MonitorTaskPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val now = 1_000_000L
    private fun database(name: String? = null) = if (name == null)
        Room.inMemoryDatabaseBuilder(context, BrowseDatabase::class.java).build()
    else Room.databaseBuilder(context, BrowseDatabase::class.java, name).addMigrations(
        BrowseDatabase.MIGRATION_1_2, BrowseDatabase.MIGRATION_2_3, BrowseDatabase.MIGRATION_3_4,
        BrowseDatabase.MIGRATION_4_5, BrowseDatabase.MIGRATION_5_6, BrowseDatabase.MIGRATION_6_7, BrowseDatabase.MIGRATION_7_8).build()

    private suspend fun event(db: BrowseDatabase, text: String = "招聘岗位正在减少，因为岗位同比减少20%",
        visibility: String? = null): BrowseEvent {
        val row = BrowseEvent(packageName = "synthetic.reader", timestamp = now, eventType = 32,
            visibleText = text, endTimestamp = now + 5000, durationMillis = 5000, sampleCount = 2,
            workKey = "synthetic-work", visibilityIntervals = visibility)
        return row.copy(id = db.browseEventDao().insert(row))
    }
    private fun request(rows: List<BrowseEvent>, id: String = "synthetic-request") = MonitorTaskRequest(
        requestId = id, packageName = rows.first().packageName, kind = if (rows.size > 1) "audio" else "text",
        text = rows.joinToString("\n") { it.visibleText }.takeLast(6000), capturedAt = now,
        contextToken = "synthetic-context", encounter = "synthetic-encounter", snapshots = rows,
        serviceFingerprint = MonitorPolicy.hash("synthetic-service"),
        authorizationKey = MonitorPolicy.hash("synthetic-permission-v1"), checkFacts = true)

    @Test fun encryptedTaskSurvivesDatabaseReopenAndCompletionIsIdempotent() = runBlocking {
        val name = "monitor-reopen-${UUID.randomUUID()}.db"
        try {
            val first = database(name)
            val saved = try {
                val row = event(first, visibility = "interval-fixture".repeat(13000))
                val input = request(listOf(row))
                val store = MonitorTaskStore(first)
                assertEquals(EnqueueResult.ENQUEUED, store.enqueue(input, now))
                val task = first.monitorTaskDao().all().single()
                assertFalse(task.payload.contains(input.text))
                assertFalse(task.payload.contains(input.contextToken))
                val plaintext = MonitorPayloadCipher().open(task.payload, task.id)
                assertFalse(plaintext.contains("visibilityIntervals"))
                assertFalse(plaintext.contains("interval-fixture"))
                assertEquals(5000L, first.browseEventDao().event(row.id)!!.durationMillis)
                input
            } finally { first.close() }
            val reopened = database(name)
            try {
                val store = MonitorTaskStore(reopened)
                val lease = requireNotNull(store.claim(now))
                assertEquals(saved.text, lease.request.text)
                assertEquals(saved.checkFacts, lease.request.checkFacts)
                assertNull(lease.request.snapshots.single().visibilityIntervals)
                assertEquals(saved.snapshots.single().visibilityIntervals,
                    reopened.browseEventDao().event(saved.snapshots.single().id)!!.visibilityIntervals)
                assertTrue(store.finish(lease, now + 1))
                assertTrue(store.finish(lease, now + 2))
                assertEquals("", reopened.monitorTaskDao().all().single().payload)
                assertEquals(EnqueueResult.DUPLICATE, store.enqueue(saved, now + 3))
                assertNull(store.claim(now + 3))
                assertEquals(1, store.counts.first().completed)
                assertEquals(1, reopened.browseEventDao().chronologicalEvents().size)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun migrationFromVersionFivePreservesEvidenceAndAddsEncryptedQueue() = runBlocking {
        val name = "monitor-v5-${UUID.randomUUID()}.db"
        try {
            val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name).callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE browse_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, packageName TEXT NOT NULL, timestamp INTEGER NOT NULL, eventType INTEGER NOT NULL, visibleText TEXT NOT NULL)")
                        db.execSQL("INSERT INTO browse_events VALUES (7,'synthetic.reader',1000000,32,'招聘岗位正在减少，因为岗位同比减少20%')")
                        BrowseDatabase.MIGRATION_1_2.migrate(db)
                        BrowseDatabase.MIGRATION_2_3.migrate(db)
                        BrowseDatabase.MIGRATION_3_4.migrate(db)
                        BrowseDatabase.MIGRATION_4_5.migrate(db)
                        db.execSQL("UPDATE browse_events SET durationMillis=5000,endTimestamp=1005000,sampleCount=2,workKey='legacy-work' WHERE id=7")
                        db.execSQL("INSERT INTO browse_samples (visitId,capturedAt,eventType,visibleText) VALUES (7,1000000,32,'合成旧采样')")
                        db.execSQL("INSERT INTO browse_semantics VALUES (7,'招聘岗位正在减少，因为岗位同比减少20%',1001000,'[]','text')")
                        db.execSQL("INSERT INTO audio_sessions (id,packageName,source,title,startedAt) VALUES ('legacy-audio','synthetic.reader','import','合成旧音频',1000000)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        error("Unexpected fixture upgrade")
                }).build())
            try { helper.writableDatabase } finally { helper.close() }
            val migrated = database(name)
            try {
                val row = requireNotNull(migrated.browseEventDao().event(7))
                assertEquals(5000L, row.durationMillis)
                assertEquals("legacy-work", row.workKey)
                assertNull(row.visibilityIntervals)
                assertEquals("[]", migrated.browseEventDao().semanticsForEvent(7)!!.claimsJson)
                assertEquals("legacy-audio", migrated.audioDao().observeSessions().first().single().id)
                migrated.openHelper.readableDatabase.query("SELECT visibleText FROM browse_samples WHERE visitId=7").use {
                    assertTrue(it.moveToFirst()); assertEquals("合成旧采样", it.getString(0))
                }
                val store = MonitorTaskStore(migrated)
                assertEquals(EnqueueResult.ENQUEUED, store.enqueue(request(listOf(row)), now))
                assertNotNull(store.claim(now))
                migrated.browseEventDao().deleteEvent(7)
                assertTrue(migrated.monitorTaskDao().all().isEmpty())
                assertNull(migrated.browseEventDao().semanticsForEvent(7))
            } finally { migrated.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun deduplicationAndCapacityRemainAtomicAcrossIndependentConnections() = runBlocking {
        val name = "monitor-capacity-${UUID.randomUUID()}.db"
        val first = database(name)
        val second = database(name)
        try {
            val row = event(first)
            val one = MonitorTaskStore(first)
            val two = MonitorTaskStore(second)
            val input = request(listOf(row))
            assertEquals(EnqueueResult.ENQUEUED, one.enqueue(input, now))
            assertEquals(EnqueueResult.DUPLICATE, two.enqueue(input, now))
            repeat(5) { assertEquals(EnqueueResult.ENQUEUED,
                one.enqueue(request(listOf(row), "capacity-$it"), now + it + 1)) }
            assertEquals(EnqueueResult.FULL, two.enqueue(request(listOf(row), "capacity-overflow"), now + 10))
            val gate = CompletableDeferred<Unit>()
            val results = listOf(one, two).map { store -> async(Dispatchers.IO) {
                gate.await(); store.claim(now + 20)
            } }
            gate.complete(Unit)
            val leases = results.awaitAll().filterNotNull()
            assertEquals(2, leases.size)
            assertEquals(2, leases.map { it.task.id }.distinct().size)
            assertEquals(6, first.monitorTaskDao().pendingCount())
            assertEquals(1, first.browseEventDao().chronologicalEvents().size)
        } finally { first.close(); second.close(); context.deleteDatabase(name) }
    }

    @Test fun oneTaskCannotBeClaimedByTwoConcurrentStores() = runBlocking {
        val name = "monitor-single-claim-${UUID.randomUUID()}.db"
        val first = database(name)
        val second = database(name)
        try {
            val one = MonitorTaskStore(first)
            val two = MonitorTaskStore(second)
            assertEquals(EnqueueResult.ENQUEUED, one.enqueue(request(listOf(event(first))), now))
            val gate = CompletableDeferred<Unit>()
            val results = listOf(one, two).map { store -> async(Dispatchers.IO) {
                gate.await(); store.claim(now)
            } }
            gate.complete(Unit)
            val leases = results.awaitAll().filterNotNull()
            assertEquals(1, leases.size)
            two.recover(now + 1)
            assertTrue(one.isLeaseCurrent(leases.single(), now + 1))
            assertNull(two.claim(now + 1))
        } finally { first.close(); second.close(); context.deleteDatabase(name) }
    }

    @Test fun retriesKeepFactModeAndExhaustAfterThreeAttemptsWithoutSavingErrorText() = runBlocking {
        val db = database()
        try {
            val store = MonitorTaskStore(db)
            assertEquals(EnqueueResult.ENQUEUED, store.enqueue(request(listOf(event(db))), now))
            val first = requireNotNull(store.claim(now))
            assertTrue(store.retry(first, "network", now))
            assertEquals(now + 5000, store.nextReadyAt(now))
            assertNull(store.claim(now + 4999))
            assertEquals(1, store.counts.first().retrying)
            val second = requireNotNull(store.claim(now + 5000))
            assertTrue(second.request.checkFacts)
            assertTrue(store.retry(second, "synthetic raw text must never be persisted", now + 5000))
            assertEquals("processing_failed", db.monitorTaskDao().all().single().lastError)
            assertEquals(now + 35000, store.nextReadyAt(now + 5000))
            val third = requireNotNull(store.claim(now + 35000))
            assertEquals(3, third.task.attempts)
            assertTrue(third.request.checkFacts)
            assertFalse(store.retry(third, "network", now + 35000))
            val failed = db.monitorTaskDao().all().single()
            assertEquals("failed", failed.status)
            assertEquals("retry_exhausted", failed.lastError)
            assertEquals("", failed.payload)
            assertEquals(1, store.counts.first().failed)
            assertNull(store.nextReadyAt(now + 35000))
        } finally { db.close() }
    }

    @Test fun editsInvalidatePermanentlyWhileDurationMetadataCanContinueGrowing() = runBlocking {
        val db = database()
        try {
            val store = MonitorTaskStore(db)
            val row = event(db)
            val input = request(listOf(row))
            assertEquals(EnqueueResult.ENQUEUED, store.enqueue(input, now))
            db.browseEventDao().update(row.copy(endTimestamp = now + 9000, durationMillis = 9000,
                sampleCount = 5, visibilityIntervals = "new-duration-metadata"))
            val lease = requireNotNull(store.claim(now))
            assertTrue(store.isLeaseCurrent(lease, now))
            db.browseEventDao().updateText(row.id, row.visibleText + "，追加的新句子")
            db.browseEventDao().updateText(row.id, row.visibleText)
            assertFalse(store.isLeaseCurrent(lease, now + 1))
            assertFalse(store.finish(lease, now + 1))
            assertNull(store.claim(now + 1))
            assertEquals("cancelled", db.monitorTaskDao().all().single().status)
            assertEquals("", db.monitorTaskDao().all().single().payload)
            assertEquals(EnqueueResult.DUPLICATE, store.enqueue(input, now + 1))
            assertEquals(1, db.browseEventDao().chronologicalEvents().size)
        } finally { db.close() }
    }

    @Test fun changedWorkIdentityAndRemovedAudioDependencyRejectLateCompletion() = runBlocking {
        val db = database()
        try {
            val store = MonitorTaskStore(db)
            val row = event(db)
            assertEquals(EnqueueResult.ENQUEUED, store.enqueue(request(listOf(row)), now))
            val old = requireNotNull(store.claim(now))
            db.browseEventDao().update(row.copy(workKey = null))
            assertFalse(store.finish(old, now + 1))
            assertEquals("cancelled", db.monitorTaskDao().all().single().status)
            db.audioDao().insertSession(AudioSession("synthetic-audio", row.packageName, "import", "合成音频", now))
            val a = row.copy(id = 0, audioSessionId = "synthetic-audio", sourceType = "audio_transcript",
                mediaStartMillis = 0, mediaEndMillis = 1000)
            val b = a.copy(visibleText = "另一个合成音频片段", mediaStartMillis = 1000, mediaEndMillis = 2000)
            val inputs = listOf(a.copy(id = db.browseEventDao().insert(a)), b.copy(id = db.browseEventDao().insert(b)))
            assertEquals(EnqueueResult.ENQUEUED, store.enqueue(request(inputs, "audio-dependency"), now + 2))
            val lease = requireNotNull(store.claim(now + 2))
            db.browseEventDao().deleteEvent(inputs.first().id)
            assertFalse(store.finish(lease, now + 3))
            val cancelled = requireNotNull(db.monitorTaskDao().get(lease.task.id))
            assertEquals("cancelled", cancelled.status)
            assertEquals("input_deleted", cancelled.lastError)
            assertEquals("", cancelled.payload)
            db.audioDao().deleteSession("synthetic-audio")
            assertNull(db.monitorTaskDao().get(lease.task.id))
            assertEquals(1, db.browseEventDao().chronologicalEvents().size)
        } finally { db.close() }
    }

    @Test fun cancelledLeaseCannotSaveSemanticsEvenAfterOriginalTextIsRestored() = runBlocking {
        val db = database()
        try {
            val repository = BrowseRepository(db)
            val store = MonitorTaskStore(db)
            val row = event(db)
            assertEquals(EnqueueResult.ENQUEUED, store.enqueue(request(listOf(row)), now))
            val lease = requireNotNull(store.claim(now))
            db.browseEventDao().updateText(row.id, row.visibleText + "，原文已经发生变化")
            db.browseEventDao().updateText(row.id, row.visibleText)
            val semantics = EventSemantics(row.id, row.visibleText, now + 1, emptyList())
            assertTrue(semantics.validFor(requireNotNull(repository.findEvent(row.id))))
            assertFalse(repository.saveSemantics(row, semantics, repository.captureGeneration,
                transactionGuard = { store.isLeaseCurrent(lease, now + 1) }) { true })
            assertTrue(repository.semantics.first().isEmpty())
            assertEquals("cancelled", db.monitorTaskDao().all().single().status)
            assertEquals(1, repository.count.first())
        } finally { db.close() }
    }

    @Test fun completionAndDeletionSerializeWithoutRecreatingEvidence() = runBlocking {
        val db = database()
        try {
            val repository = BrowseRepository(db)
            val store = MonitorTaskStore(db)
            repeat(5) { iteration ->
                val row = event(db)
                assertEquals(EnqueueResult.ENQUEUED, store.enqueue(request(listOf(row), "delete-race-$iteration"), now))
                val lease = requireNotNull(store.claim(now))
                val gate = CompletableDeferred<Unit>()
                val save = async(Dispatchers.IO) {
                    gate.await()
                    repository.saveSemantics(row, EventSemantics(row.id, row.visibleText, now + 1, emptyList()),
                        repository.captureGeneration, transactionGuard = { store.isLeaseCurrent(lease, now + 1) }) { true }
                    store.finish(lease, now + 2)
                }
                val delete = async(Dispatchers.IO) { gate.await(); db.browseEventDao().deleteEvent(row.id) }
                gate.complete(Unit)
                save.await(); delete.await()
                assertNull(repository.findEvent(row.id))
                assertNull(db.browseEventDao().semanticsForEvent(row.id))
                assertNull(db.monitorTaskDao().get(lease.task.id))
                assertFalse(store.finish(lease, now + 3))
            }
            assertEquals(0, repository.count.first())
        } finally { db.close() }
    }

    @Test fun authorizationRevocationDoesNotReviveAndCancelledLatestHidesOldState() = runBlocking {
        val db = database()
        try {
            val store = MonitorTaskStore(db)
            val row = event(db)
            val input = request(listOf(row))
            assertEquals(EnqueueResult.INVALID, store.enqueue(input, now) { false })
            assertTrue(db.monitorTaskDao().all().isEmpty())
            assertEquals(EnqueueResult.ENQUEUED, store.enqueue(input, now))
            val first = requireNotNull(store.claim(now))
            assertTrue(store.fail(first, "http_401", now + 1))
            assertEquals("failed", store.observeInputStates().first()[row.id])
            val fresh = input.copy(requestId = "new-permission", authorizationKey = MonitorPolicy.hash("new-revision"))
            assertEquals(EnqueueResult.ENQUEUED, store.enqueue(fresh, now + 2))
            assertNull(store.claim(now + 2) { false })
            assertFalse(store.observeInputStates().first().containsKey(row.id))
            assertEquals(EnqueueResult.DUPLICATE, store.enqueue(fresh, now + 3))
            assertNull(store.claim(now + 3))
            assertEquals(1, store.counts.first().failed)
            assertEquals(0, store.counts.first().queued)
        } finally { db.close() }
    }

    @Test fun recoveryReleasesDeadProcessAndExpiredLeaseWithoutAllowingStaleCompletion() = runBlocking {
        val name = "monitor-lease-${UUID.randomUUID()}.db"
        try {
            val first = database(name)
            val old = try {
                val store = MonitorTaskStore(first)
                assertEquals(EnqueueResult.ENQUEUED, store.enqueue(request(listOf(event(first))), now))
                val lease = requireNotNull(store.claim(now))
                // Same PID with another process session models PID reuse after an actual application restart.
                first.monitorTaskDao().update(lease.task.copy(leaseOwner = "${android.os.Process.myPid()}|previous-process|old-lease"))
                lease
            } finally { first.close() }
            val reopened = database(name)
            try {
                val store = MonitorTaskStore(reopened)
                store.recover(now + 1)
                val second = requireNotNull(store.claim(now + 1))
                assertEquals(old.task.id, second.task.id)
                assertEquals(2, second.task.attempts)
                assertFalse(store.finish(old, now + 2))
                store.recover(now + 180002)
                val third = requireNotNull(store.claim(now + 180002))
                assertEquals(3, third.task.attempts)
                assertFalse(store.finish(second, now + 180003))
                assertTrue(store.finish(third, now + 180003))
                assertEquals("", reopened.monitorTaskDao().all().single().payload)
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun corruptPayloadAndExpiredTasksAreDiscardedWithoutDeletingOriginals() = runBlocking {
        val db = database()
        try {
            val store = MonitorTaskStore(db)
            val row = event(db)
            assertEquals(EnqueueResult.ENQUEUED, store.enqueue(request(listOf(row)), now))
            val task = db.monitorTaskDao().all().single()
            db.monitorTaskDao().update(task.copy(payload = "1:malformed-synthetic-envelope"))
            assertNull(store.claim(now))
            assertEquals("failed", db.monitorTaskDao().get(task.id)!!.status)
            assertEquals("invalid_payload", db.monitorTaskDao().get(task.id)!!.lastError)
            assertEquals("", db.monitorTaskDao().get(task.id)!!.payload)
            assertEquals(EnqueueResult.ENQUEUED, store.enqueue(request(listOf(row), "ttl-task"), now))
            store.prune(now + 24 * 60 * 60 * 1000L)
            assertTrue(db.monitorTaskDao().all().isEmpty())
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM monitor_task_inputs").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            assertEquals(row, db.browseEventDao().event(row.id))
            assertEquals(EnqueueResult.INVALID,
                store.enqueue(request(listOf(row), "too-old"), now + 24 * 60 * 60 * 1000L))
        } finally { db.close() }
    }
}
