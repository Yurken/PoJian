package com.emerge.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.capture.VisibilityInterval
import com.emerge.app.capture.VisibilityIntervals
import com.emerge.app.capture.VisibilityRegion
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisibilityPersistenceTest {
    @Test fun visibilityIntervalsRoundTripAndDisappearWithTheirVisit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, BrowseDatabase::class.java).build()
        try {
            val dao = database.browseEventDao()
            val encoded = VisibilityIntervals.encode(listOf(VisibilityInterval(1000, 3000, 2000, VisibilityRegion.MAIN),
                VisibilityInterval(5000, 7000, 2000, VisibilityRegion.COMMENTS)))
            val id = dao.insert(BrowseEvent(packageName = "synthetic.reader", timestamp = 1000, eventType = 32,
                visibleText = "合成正文与评论", endTimestamp = 7000, durationMillis = 4000, sampleCount = 4,
                visibilityIntervals = encoded))
            val saved = dao.chronologicalEvents().single()
            assertEquals(encoded, saved.visibilityIntervals)
            assertEquals(4000L, VisibilityIntervals.duration(VisibilityIntervals.decode(saved.visibilityIntervals!!)!!))
            dao.deleteEvent(id)
            assertTrue(dao.chronologicalEvents().isEmpty())
            val legacyId = dao.insert(BrowseEvent(packageName = "synthetic.reader", timestamp = 1000,
                eventType = 32, visibleText = "旧式合成记录"))
            assertNull(dao.chronologicalEvents().single { it.id == legacyId }.visibilityIntervals)
        } finally { database.close() }
    }
}
