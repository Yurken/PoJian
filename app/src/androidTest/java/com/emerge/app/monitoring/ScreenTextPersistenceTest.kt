package com.emerge.app.monitoring

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.analysis.ArgumentUnit
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.data.BrowseDatabase
import com.emerge.app.data.BrowseEvent
import com.emerge.app.data.BrowseRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScreenTextPersistenceTest {
    @Test fun changingAndRestoringParentStillInvalidatesTheOldTask() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BrowseDatabase::class.java).build()
        try {
            val repo = BrowseRepository(db)
            val row = BrowseEvent(packageName = "reader", timestamp = 1000, eventType = 32, visibleText = "标题")
            val parent = row.copy(id = repo.record(row))
            val other = row.copy(id = repo.record(row.copy(visibleText = "另一标题")))
            val material = ScreenTextMaterial.event("reader", "图中原文", 2000, parent)
            val saved = material.copy(id = requireNotNull(repo.saveCapture(material, false, repo.captureGeneration) { true }))
            val store = MonitorTaskStore(db)
            val request = MonitorTaskRequest("ocr-parent-change", "reader", "text", saved.visibleText, 2000,
                "screen", "visit", listOf(saved), MonitorPolicy.hash("service"), MonitorPolicy.hash("authorized"), true)
            assertEquals(EnqueueResult.ENQUEUED, store.enqueue(request, 3000))
            val lease = requireNotNull(store.claim(3001))
            db.browseEventDao().update(saved.copy(parentEventId = other.id))
            db.browseEventDao().update(saved)
            assertFalse(store.isLeaseCurrent(lease, 3002))
            assertNull(store.claim(3003))
        } finally { db.close() }
    }

    @Test fun parentDeletionCascadesToOcrSemanticsAndPendingAnalysis() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BrowseDatabase::class.java).build()
        try {
            val repo = BrowseRepository(db)
            val parent = BrowseEvent(packageName = "reader", timestamp = 1000, eventType = 32,
                visibleText = "页面标题", endTimestamp = 2000)
            val savedParent = parent.copy(id = repo.record(parent))
            val material = ScreenTextMaterial.event("reader", "招聘岗位正在减少，因为岗位同比减少20%", 2500, savedParent)
            val saved = material.copy(id = requireNotNull(repo.saveCapture(material, false, repo.captureGeneration) { true }))
            assertTrue(repo.saveSemantics(saved, EventSemantics(saved.id, saved.visibleText, 3000,
                listOf(ArgumentUnit("招聘岗位正在减少", "就业", "岗位", "数量", "减少", "岗位同比减少20%", uncertain = false)), "text"),
                repo.captureGeneration) { true })
            val store = MonitorTaskStore(db)
            val request = MonitorTaskRequest("ocr-test", "reader", "text", saved.visibleText, 2500, "screen", "visit",
                listOf(saved), MonitorPolicy.hash("service"), MonitorPolicy.hash("authorized"), true)
            assertEquals(EnqueueResult.ENQUEUED, store.enqueue(request, 3000))
            val lease = requireNotNull(store.claim(3001))
            assertEquals(savedParent.id, lease.request.snapshots.single().parentEventId)
            repo.deleteEvent(savedParent.id)
            assertNull(repo.findEvent(saved.id))
            assertTrue(repo.semantics.first().isEmpty())
            assertFalse(store.isLeaseCurrent(lease, 3002))
            assertNull(store.claim(3003))
        } finally { db.close() }
    }

    @Test fun missingOrForeignParentAndStaleGenerationCannotSaveMaterial() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BrowseDatabase::class.java).build()
        try {
            val repo = BrowseRepository(db)
            val parent = BrowseEvent(packageName = "reader", timestamp = 1000, eventType = 32,
                visibleText = "页面标题", endTimestamp = 2000)
            val savedParent = parent.copy(id = repo.record(parent))
            val material = ScreenTextMaterial.event("reader", "原图识别文字", 2500, savedParent)
            val generation = repo.captureGeneration
            assertNull(repo.saveCapture(material.copy(packageName = "other"), false, generation) { true })
            assertNull(repo.saveCapture(material, false, generation) { false })
            repo.deleteEvent(savedParent.id)
            assertNull(repo.saveCapture(material, false, generation) { true })
            assertNull(repo.saveCapture(material, false, repo.captureGeneration) { true })
        } finally { db.close() }
    }
}
