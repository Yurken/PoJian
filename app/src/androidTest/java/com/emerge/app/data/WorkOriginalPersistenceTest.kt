package com.emerge.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkOriginalPersistenceTest {
    private val pkg = "com.ss.android.ugc.aweme"
    private val link = "https://v.douyin.com/AbCdEf/"
    private fun database() = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, BrowseDatabase::class.java).build()

    @Test fun sharedLinkSurvivesOneVisitDeletionAndIsRemovedWithLastVisit() = runBlocking {
        val db = database()
        try {
            val repository = BrowseRepository(db)
            val work = ObservedWork("w", pkg, "真实作品标题", "作者", "author_title", 1000)
            db.browseEventDao().insertWork(work)
            val first = db.browseEventDao().insert(BrowseEvent(packageName=pkg,timestamp=1000,eventType=0,visibleText="原文",workKey="w"))
            val second = db.browseEventDao().insert(BrowseEvent(packageName=pkg,timestamp=2000,eventType=0,visibleText="原文",workKey="w"))
            assertTrue(repository.saveOriginal(first, link))
            assertFalse(repository.saveOriginal(first, "https://evil.example/work"))
            assertEquals(link, db.workOriginalDao().get("w")?.url)
            repository.deleteEvent(first)
            assertEquals(link, db.workOriginalDao().get("w")?.url)
            repository.deleteEvent(second)
            assertNull(db.workOriginalDao().get("w"))
            assertFalse(repository.saveOriginal(first, link))
        } finally { db.close() }
    }
    @Test fun captionIdentityUpgradePreservesExplicitShareLinkAndInputSnapshot() = runBlocking {
        val db = database()
        try {
            val repository = BrowseRepository(db)
            val old = ObservedWork("old",pkg,"真实作品标题","作者","author_title",1000)
            val event = BrowseEvent(packageName=pkg,timestamp=1000,eventType=0,visibleText="完整采样正文",workKey="old")
            val id = requireNotNull(repository.saveCapture(event,false,repository.captureGeneration,old) { true })
            assertTrue(repository.saveOriginal(id, link))
            val upgraded = old.copy(workKey="new",identityMethod="platform_id",nativeId="7000000000000000000")
            repository.saveCapture(event.copy(id=id,workKey="new"),true,repository.captureGeneration,upgraded) { true }
            assertEquals(link, db.workOriginalDao().get("new")?.url)
            assertEquals("user_link", db.workOriginalDao().get("new")?.source)
            assertNull(db.workOriginalDao().get("old"))
            assertEquals(event.visibleText, db.browseEventDao().event(id)?.visibleText)
        } finally { db.close() }
    }
    @Test fun missingLegacyIdentityCanBeAttachedWithoutCreatingAnotherViewing() = runBlocking {
        val db = database()
        try {
            val repository = BrowseRepository(db)
            val id = db.browseEventDao().insert(BrowseEvent(packageName=pkg,timestamp=1000,eventType=0,visibleText="历史正文"))
            assertTrue(repository.saveOriginal(id, link))
            assertEquals(link, db.workOriginalDao().get("record:$id")?.url)
            assertEquals(1, db.browseEventDao().chronologicalEvents().size)
            repository.clear()
            assertNull(db.workOriginalDao().get("record:$id"))
        } finally { db.close() }
    }
}
