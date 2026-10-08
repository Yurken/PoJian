package com.emerge.app.data

import androidx.room.withTransaction
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BrowseRepository(private val database: BrowseDatabase) {
    private val dao = database.browseEventDao()
    private val audio = database.audioDao()
    private val captureMutex = Mutex()
    private val deletionGeneration = AtomicLong()
    val recentEvents = dao.observeRecent()
    val allEvents = dao.observeAll()
    val count = dao.observeCount()
    suspend fun record(event: BrowseEvent) = database.withTransaction {
        val id = dao.insert(event)
        if (audio.trimsAudio()) audio.invalidateAllAnalyses()
        audio.invalidateTrimmedSessions()
        dao.trim()
        id
    }
    suspend fun updateSession(event: BrowseEvent): Long? = database.withTransaction {
        event.id.takeIf { dao.update(event) > 0 }
    }

    /** A sample taken before deletion must never become a new row after deletion. */
    internal val captureGeneration: Long get() = deletionGeneration.get()

    internal suspend fun saveCapture(
        event: BrowseEvent,
        continuing: Boolean,
        generation: Long,
        permitted: () -> Boolean
    ): Long? = captureMutex.withLock {
        fun current() = deletionGeneration.get() == generation && permitted()
        if (!current()) return@withLock null
        val id = database.withTransaction {
            if (!current()) return@withTransaction null
            if (continuing) {
                // Updates are deliberately never upserts: a deleted session stays deleted.
                event.id.takeIf { dao.update(event) > 0 }
            } else {
                val inserted = dao.insert(event)
                if (audio.trimsAudio()) audio.invalidateAllAnalyses()
                audio.invalidateTrimmedSessions()
                dao.trim()
                if (current()) inserted else {
                    dao.deleteEvent(inserted)
                    null
                }
            }
        }
        // Settings/deletion may change while Room resumes the transaction result.
        if (id != null && !current()) {
            if (!continuing) dao.deleteEvent(id)
            null
        } else id
    }

    suspend fun deleteEvent(id: Long) {
        deletionGeneration.incrementAndGet()
        captureMutex.withLock { database.withTransaction {
            audio.invalidateAllAnalyses()
            audio.invalidateForEvent(id)
            dao.deleteEvent(id)
        } }
    }

    suspend fun deletePackage(packageName: String) {
        deletionGeneration.incrementAndGet()
        captureMutex.withLock { database.withTransaction {
            audio.invalidateAllAnalyses()
            audio.deletePackage(packageName)
            dao.deletePackage(packageName)
        } }
    }

    suspend fun clear() {
        deletionGeneration.incrementAndGet()
        captureMutex.withLock { database.withTransaction {
            audio.clear()
            dao.clear()
        } }
    }
}
