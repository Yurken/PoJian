package com.emerge.app.data

import androidx.room.withTransaction
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.analysis.SemanticsCodec
import com.emerge.app.capture.WorkIdentityExtractor
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BrowseRepository(private val database: BrowseDatabase) {
    private val dao = database.browseEventDao()
    private val audio = database.audioDao()
    private val captureMutex = Mutex()
    private val deletionGeneration = AtomicLong()
    val recentEvents = dao.observeRecent()
    val allEvents = dao.observeAll().onStart { repairAdjacentVisits() }
    val works = dao.observeWorks()
    val semantics = dao.observeSemantics().map { rows -> rows.mapNotNull(SemanticsCodec::decode) }
    val count = dao.observeCount()
    private var repaired = false

    /** Conservative upgrade: preserve samples, never join across another work or an unknown gap. */
    internal suspend fun repairAdjacentVisits() = captureMutex.withLock { repairLocked() }
    private suspend fun repairLocked() {
        if (repaired) return
        database.withTransaction {
            for (original in dao.chronologicalEvents()) {
                if (original.sourceType != "accessibility") continue
                if (WorkIdentityExtractor.isRecommendationList(original.packageName, original.visibleText)) {
                    dao.update(original.copy(sourceType = "recommendation_list"))
                    continue
                }
                // Identity repair must never erase a distinct viewing event or its samples.
                val hint = WorkIdentityExtractor.extract(original.packageName, original.visibleText)
                val key = hint.key(original.packageName)
                if (key != null && original.identityMethod in setOf("unknown", "session_estimate")) {
                    dao.insertWork(ObservedWork(key, original.packageName, hint.title, hint.author, hint.method,
                        original.timestamp, hint.evidence, hint.nativeId))
                    dao.update(original.copy(workKey = key, identityMethod = hint.method))
                    dao.updateMaterialIdentity(original.id, key, hint.method)
                }
            }
            dao.trimWorks()
        }
        repaired = true
    }

    suspend fun record(event: BrowseEvent) = database.withTransaction {
        val id = dao.insert(event)
        if (audio.trimsAudio()) audio.invalidateAllAnalyses()
        audio.invalidateTrimmedSessions()
        dao.trim()
        dao.trimWorks()
        id
    }
    suspend fun updateSession(event: BrowseEvent): Long? = database.withTransaction {
        event.id.takeIf { dao.update(event) > 0 }
    }

    /** A sample taken before deletion must never become a new row after deletion. */
    internal val captureGeneration: Long get() = deletionGeneration.get()

    internal suspend fun resolveHint(pkg: String, hint: com.emerge.app.capture.WorkHint): com.emerge.app.capture.WorkHint {
        if (hint.nativeId.isNotBlank() || hint.title.length < (if (hint.truncated) 16 else 8) || hint.author.isBlank()) return hint
        val matches = dao.captionCandidates(pkg, hint.author).filter {
            if (hint.truncated) it.title.startsWith(hint.title) else it.title == hint.title
        }
        return matches.singleOrNull()?.let { hint.copy(canonicalKey = it.workKey, nativeId = it.nativeId) } ?: hint
    }

    suspend fun findEvent(id: Long): BrowseEvent? = dao.event(id)
    suspend fun getEvent(id: Long): BrowseEvent? = findEvent(id)
    suspend fun clearSemantics() = captureMutex.withLock {
        database.withTransaction { dao.clearSemantics() }
    }

    /** Bind a completed result to the full input snapshot; never upsert the encounter itself. */
    suspend fun saveSemantics(
        snapshot: BrowseEvent,
        semantics: EventSemantics,
        generation: Long,
        transactionGuard: suspend () -> Boolean = { true },
        permitted: () -> Boolean
    ): Boolean = captureMutex.withLock {
        fun current() = deletionGeneration.get() == generation && permitted()
        if (!current() || !semantics.validFor(snapshot)) return@withLock false
        val saved = database.withTransaction {
            if (!current() || !transactionGuard()) return@withTransaction false
            val event = dao.event(snapshot.id) ?: return@withTransaction false
            if (!semantics.validFor(event) || event.workKey != snapshot.workKey ||
                event.parentEventId != snapshot.parentEventId) return@withTransaction false
            val previous = dao.semanticsForEvent(snapshot.id)
            if (previous != null && previous.analyzedAt > semantics.analyzedAt) return@withTransaction false
            if (!current() || !transactionGuard()) return@withTransaction false
            dao.putSemantics(SemanticsCodec.encode(semantics))
            true
        }
        // Revocation/deletion can change while Room completes the transaction.
        if (saved && !current()) {
            dao.removeSemanticsVersion(snapshot.id, semantics.analyzedAt, semantics.inputText)
            false
        } else saved
    }

    internal suspend fun saveCapture(
        event: BrowseEvent,
        continuing: Boolean,
        generation: Long,
        work: ObservedWork? = null,
        sampleText: String? = null,
        permitted: () -> Boolean
    ): Long? = captureMutex.withLock {
        repairLocked()
        fun current() = deletionGeneration.get() == generation && permitted()
        if (!current()) return@withLock null
        val id = database.withTransaction {
            if (!current()) return@withTransaction null
            event.parentEventId?.let { parentId ->
                val parent = dao.event(parentId) ?: return@withTransaction null
                if (parent.sourceType != "accessibility" || parent.packageName != event.packageName ||
                    parent.timestamp > event.timestamp || parent.workKey != event.workKey) return@withTransaction null
            }
            val savedId = if (continuing) {
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
            if (savedId != null) {
                work?.let { dao.insertWork(it) }
                if (continuing) dao.updateMaterialIdentity(savedId, event.workKey, event.identityMethod)
                if (sampleText != null && dao.lastSampleText(savedId) != sampleText) {
                    dao.insertSample(BrowseSample(visitId = savedId,
                        capturedAt = event.endTimestamp, eventType = event.eventType, visibleText = sampleText))
                    dao.trimSamples(savedId)
                    dao.trimAllSamples()
                }
            }
            dao.trimWorks()
            savedId
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
            dao.trimWorks()
        } }
    }

    suspend fun deletePackage(packageName: String) {
        deletionGeneration.incrementAndGet()
        captureMutex.withLock { database.withTransaction {
            audio.invalidateAllAnalyses()
            audio.deletePackage(packageName)
            dao.deletePackage(packageName)
            dao.trimWorks()
        } }
    }

    suspend fun clear() {
        deletionGeneration.incrementAndGet()
        captureMutex.withLock { database.withTransaction {
            audio.clear()
            dao.clear()
            dao.trimWorks()
        } }
    }
}
