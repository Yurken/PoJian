package com.emerge.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkPersistenceTest {
    @Test fun changingSamplesRemainOneVisitAndDeletionCascadesWithoutResurrection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BrowseDatabase::class.java).build()
        try {
            val repository = BrowseRepository(db)
            val work = ObservedWork("work-a", "com.reader", "作品标题", "作者", "author_title", 1000)
            val visit = BrowseEvent(packageName = "com.reader", timestamp = 1000, eventType = 32,
                visibleText = "第一页", sampleCount = 1, endTimestamp = 1000,
                workKey = work.workKey, identityMethod = work.identityMethod)
            val generation = repository.captureGeneration
            val id = requireNotNull(repository.saveCapture(visit, false, generation, work, "第一页") { true })
            val next = visit.copy(id = id, visibleText = "第一页\n第二页", endTimestamp = 3000,
                durationMillis = 2000, sampleCount = 2)
            assertEquals(id, repository.saveCapture(next, true, generation, work, "第二页") { true })
            repository.saveCapture(next.copy(endTimestamp = 5000, durationMillis = 4000), true,
                generation, work, "第二页") { true }
            assertEquals(1, repository.count.first())
            assertEquals(4000L, repository.allEvents.first().single().durationMillis)
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM browse_samples").use {
                assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
            }
            repository.deleteEvent(id)
            assertNull(repository.saveCapture(next, true, generation, work, "迟到采样") { true })
            assertEquals(0, repository.count.first())
            listOf("browse_samples", "observed_works").forEach { table ->
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
            }
        } finally { db.close() }
    }
}
