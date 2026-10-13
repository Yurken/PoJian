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
    @Test fun truncatedReturnResolvesOnlyAUniqueKnownFullCaption() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BrowseDatabase::class.java).build()
        try {
            val repository = BrowseRepository(db)
            val prefix = "这是同一个作者发布的足够长的文案开头"
            db.browseEventDao().insertWork(ObservedWork("a","reader",prefix+"甲","作者","author_title",1000))
            val short = com.emerge.app.capture.WorkHint(prefix,"作者",truncated=true)
            assertEquals("a",repository.resolveHint("reader",short).key("reader"))
            db.browseEventDao().insertWork(ObservedWork("b","reader",prefix+"乙","作者","author_title",2000))
            assertNull(repository.resolveHint("reader",short).key("reader"))
            assertNull(repository.resolveHint("other",short).key("other"))
        } finally { db.close() }
    }
    @Test fun returnWithoutExposedNativeIdCanUseUniquePreviouslyObservedAuthorAndCaption() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BrowseDatabase::class.java).build()
        try {
            val repository = BrowseRepository(db)
            val hint = com.emerge.app.capture.WorkHint("这是之前已经明确观察过的完整作品标题", "作者", nativeId="123456789")
            val key = requireNotNull(hint.key("reader"))
            db.browseEventDao().insertWork(ObservedWork(key,"reader",hint.title,hint.author,"platform_id",1000,"visible_platform_id",hint.nativeId))
            val resolved = repository.resolveHint("reader",hint.copy(nativeId=""))
            assertEquals(key,resolved.key("reader"))
            assertEquals("unique_observed_author_caption",resolved.evidence)
            assertEquals(hint.nativeId,resolved.nativeId)
        } finally { db.close() }
    }

    @Test fun upgradeRepairsOnlySupportedIdentityAndPreservesAllVisitsAndSamples() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, BrowseDatabase::class.java).build()
        try {
            val dao = db.browseEventDao()
            val body = "不写bug\n关注\n图片,第1张,共1张,双指左划或右划即可查看更多内容\n更多内容\n神经网络 GNN CNN 创新创新点 创新思路\n图神经网络创新点\n点赞7"
            suspend fun put(text: String, start: Long, duration: Long): Long {
                val id = dao.insert(BrowseEvent(packageName = "com.xingin.xhs", timestamp = start,
                    endTimestamp = start + duration, durationMillis = duration, sampleCount = 2,
                    eventType = 4096, visibleText = text))
                dao.insertSample(BrowseSample(visitId = id, capturedAt = start, eventType = 4096, visibleText = text))
                return id
            }
            val first = put(body, 1000, 2000)
            put(body.replace("点赞7", "点赞8"), 4000, 3000)
            put(body.replace("神经网络 GNN CNN 创新创新点 创新思路", "这是另一篇独立作品的正文标题"), 8000, 2000)
            put(body, 12000, 1000)
            put("笔记 第一篇摘要 来自甲作者 8赞\n笔记 第二篇摘要 来自乙作者 9赞", 15000, 0)
            val repository = BrowseRepository(db)
            repository.repairAdjacentVisits()
            val rows = repository.allEvents.first().sortedBy { it.timestamp }
            assertEquals(4, rows.size)
            assertEquals(first, rows.first().id)
            assertEquals(2000L, rows.first().durationMillis)
            assertEquals(3000L, rows[1].durationMillis) // Revisit boundaries and missing seconds survive.
            assertEquals(2, rows.first().sampleCount)
            assertEquals(rows.first().workKey, rows.last().workKey)
            assertEquals(rows.first().workKey, rows[1].workKey)
            assertNotEquals(rows.first().workKey, rows[2].workKey)
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM browse_samples WHERE visitId = $first").use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
            repository.repairAdjacentVisits()
            assertEquals(4, repository.count.first())
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM browse_events WHERE sourceType = 'recommendation_list'").use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        } finally { db.close() }
    }

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
