package com.emerge.app.data

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.capture.CaptureSettings
import com.emerge.app.monitoring.ThumbnailEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Device-only storage/bitmap checks. Fixtures use isolated preferences and a private test directory. */
@RunWith(AndroidJUnit4::class)
class WorkThumbnailStoreTest {
    private class Fixture(space: Long = Long.MAX_VALUE, maxFiles: Int = 400) : AutoCloseable {
        private val base = InstrumentationRegistry.getInstrumentation().targetContext
        private val suffix = UUID.randomUUID().toString()
        private val folder = File(base.cacheDir,"thumbnail-test-$suffix")
        private val context = object : ContextWrapper(base) {
            override fun getNoBackupFilesDir() = folder
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$name-$suffix",mode)
        }
        val settings = CaptureSettings(context)
        val db = Room.inMemoryDatabaseBuilder(context,BrowseDatabase::class.java).build()
        val repository = BrowseRepository(db)
        val store = WorkThumbnailStore(context,db,settings,maxFiles=maxFiles,availableSpace={ space })
        init { settings.setAllowedPackages(setOf("reader")); settings.setEnabled(true) }
        suspend fun event(key: String, at: Long = 1000): BrowseEvent {
            val work = ObservedWork(key,"reader","测试作品标题","作者","platform_id",at)
            val row = BrowseEvent(packageName="reader",timestamp=at,eventType=32,visibleText="测试正文",
                endTimestamp=at,sampleCount=1,workKey=key,identityMethod=work.identityMethod)
            return row.copy(id=requireNotNull(repository.saveCapture(row,false,repository.captureGeneration,work) { true }))
        }
        fun image() = Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888).let { bitmap ->
            try {
                for(y in 0 until bitmap.height) for(x in 0 until bitmap.width)
                    bitmap.setPixel(x,y,if((x/16+y/16)%2==0) Color.WHITE else Color.BLUE)
                requireNotNull(ThumbnailEncoder.encode(bitmap,null,"content_crop"))
            } finally { bitmap.recycle() }
        }
        override fun close() { store.close(); db.close(); folder.deleteRecursively(); base.deleteSharedPreferences("capture-$suffix") }
    }

    @Test fun consentAndStorageFailureNeverCreateExtraRecordsOrPictures() = runBlocking {
        Fixture(space=0).use { f ->
            val event=f.event("a")
            assertFalse(f.store.save(event,f.image(),f.settings.thumbnailRevision) { true })
            f.settings.setLocalThumbnails(true)
            assertFalse(f.store.save(event,f.image(),f.settings.thumbnailRevision) { true })
            assertTrue(f.store.status.value.contains("存储空间不足"))
            assertTrue(f.db.workThumbnailDao().all().isEmpty())
            assertEquals(1,f.db.browseEventDao().chronologicalEvents().size)
        }
    }
    @Test fun oneRepresentativeSurvivesDeletingOneOfTwoVisitsAndCleansLastDependency() = runBlocking {
        Fixture().use { f ->
            f.settings.setLocalThumbnails(true)
            val first=f.event("a"); val second=f.event("a",2000)
            assertTrue(f.store.save(first,f.image(),f.settings.thumbnailRevision) { true })
            assertFalse(f.store.save(second,f.image(),f.settings.thumbnailRevision) { true })
            val row=f.db.workThumbnailDao().all().single()
            assertNotNull(f.store.load(row))
            assertSame(f.store.load(row),f.store.load(row))
            f.repository.deleteEvent(first.id); f.store.maintain()
            assertEquals(1,f.db.workThumbnailDao().all().size)
            assertTrue(File(f.store.directory,row.fileName).exists())
            f.repository.deleteEvent(second.id); f.store.maintain()
            assertTrue(f.db.workThumbnailDao().all().isEmpty())
            assertTrue(f.store.directory.listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun revocationLateCallbackAndOrphanCleanupAreIndependentOfCloud() = runBlocking {
        Fixture().use { f ->
            f.settings.setLocalThumbnails(true)
            val revision=f.settings.thumbnailRevision
            val event=f.event("a")
            assertFalse(f.store.save(event,f.image(),revision) { false })
            assertTrue(f.store.save(event,f.image(),revision) { true })
            val row=f.db.workThumbnailDao().all().single()
            File(f.store.directory,"orphan.jpg").writeText("interrupted write")
            f.store.maintain()
            assertFalse(File(f.store.directory,"orphan.jpg").exists())
            f.settings.setLocalThumbnails(false)
            assertNull(f.store.load(row))
            f.store.maintain()
            assertTrue(f.db.workThumbnailDao().all().isEmpty())
            f.settings.setLocalThumbnails(true)
            assertFalse(f.store.save(event,f.image(),revision) { true })
            assertEquals(1,f.db.browseEventDao().chronologicalEvents().size)
        }
    }
    @Test fun boundedStorageEvictsOldestRepresentativeAndClearsOnPackageRemoval() = runBlocking {
        Fixture(maxFiles=2).use { f ->
            f.settings.setLocalThumbnails(true)
            for(key in listOf("a","b","c")) assertTrue(f.store.save(f.event(key),f.image(),f.settings.thumbnailRevision) { true })
            f.store.maintain()
            assertEquals(2,f.db.workThumbnailDao().all().size)
            assertEquals(2,f.store.directory.listFiles().orEmpty().size)
            assertEquals(3,f.db.browseEventDao().chronologicalEvents().size)
            f.settings.setAllowedPackages(emptySet()); f.store.maintain()
            assertTrue(f.db.workThumbnailDao().all().isEmpty())
            assertTrue(f.store.directory.listFiles().orEmpty().isEmpty())
        }
    }
}
