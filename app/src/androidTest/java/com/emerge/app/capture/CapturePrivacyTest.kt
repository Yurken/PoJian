package com.emerge.app.capture

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.data.BrowseDatabase
import com.emerge.app.data.BrowseEvent
import com.emerge.app.data.BrowseRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CapturePrivacyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: BrowseDatabase
    private lateinit var repository: BrowseRepository
    private val isolatedContext by lazy {
        object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("capture_privacy_qa", mode)
        }
    }
    private val preferences get() = isolatedContext.getSharedPreferences("capture", Context.MODE_PRIVATE)

    @Before fun prepare() {
        preferences.edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, BrowseDatabase::class.java).build()
        repository = BrowseRepository(database)
    }

    @After fun close() {
        database.close()
        preferences.edit().clear().commit()
    }

    private fun event(packageName: String = "com.reader", text: String = "Visible reading content") =
        BrowseEvent(packageName = packageName, timestamp = 1000L, eventType = 32, visibleText = text)

    @Test fun oldEnabledPreferenceDoesNotAuthorizeAnyApplication() {
        preferences.edit().putBoolean("enabled", true).commit()
        val settings = CaptureSettings(isolatedContext)
        assertTrue(settings.enabled.value)
        assertTrue(settings.allowedPackages.value.isEmpty())
        assertFalse(settings.isPackageAllowed("com.reader"))
        assertNull(settings.permissionToken("com.reader"))
    }

    @Test fun selectedPackagesPersistWithoutMutableSetLeaks() {
        val settings = CaptureSettings(isolatedContext)
        val selected = mutableSetOf("com.reader")
        settings.setAllowedPackages(selected)
        selected += "com.unselected"
        assertEquals(setOf("com.reader"), settings.allowedPackages.value)
        assertEquals(setOf("com.reader"), CaptureSettings(isolatedContext).allowedPackages.value)
        try {
            (settings.allowedPackages.value as MutableSet<String>).add("com.unselected")
            fail("The published package set must be immutable")
        } catch (_: UnsupportedOperationException) { }
    }

    @Test fun pauseAndScopeChangesInvalidateOldPermissionEvenAfterResuming() {
        val settings = CaptureSettings(isolatedContext)
        settings.setAllowedPackages(setOf("com.reader"))
        assertNull(settings.permissionToken("com.reader"))
        settings.setEnabled(true)
        val permission = requireNotNull(settings.permissionToken("com.reader"))
        assertNull(settings.permissionToken("com.other"))
        settings.setEnabled(false)
        settings.setEnabled(true)
        assertFalse(settings.isPermissionCurrent("com.reader", permission))
        val resumed = requireNotNull(settings.permissionToken("com.reader"))
        settings.setAllowedPackages(emptySet())
        assertFalse(settings.isPermissionCurrent("com.reader", resumed))
    }

    @Test fun deletedSessionCannotBeReinsertedByUpdate() = runBlocking {
        val original = event()
        val id = repository.record(original)
        repository.deleteEvent(id)
        assertNull(repository.updateSession(original.copy(id = id, sampleCount = 2)))
        assertEquals(0, repository.count.first())
    }

    @Test fun packageDeletionRetainsOtherApplicationsAndInvalidatesOldSample() = runBlocking {
        repository.record(event())
        repository.record(event("com.other"))
        val generation = repository.captureGeneration
        repository.deletePackage("com.reader")
        assertNull(repository.saveCapture(event(), false, generation) { true })
        assertEquals(listOf("com.other"), repository.allEvents.first().map { it.packageName })
    }

    @Test fun clearingPreventsAnAlreadyReadBodyFromBeingSavedLater() = runBlocking {
        val generation = repository.captureGeneration
        repository.clear()
        assertNull(repository.saveCapture(event(), false, generation) { true })
        assertEquals(0, repository.count.first())
    }

    @Test fun permissionRevokedDuringWriteDiscardsTheNewRow() = runBlocking {
        var checks = 0
        val result = repository.saveCapture(event(), false, repository.captureGeneration) { ++checks < 3 }
        assertNull(result)
        assertEquals(0, repository.count.first())
    }
}
