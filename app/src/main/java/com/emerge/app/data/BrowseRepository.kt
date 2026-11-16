package com.emerge.app.data

import androidx.room.withTransaction
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.analysis.SemanticsCodec
import com.emerge.app.capture.WorkIdentityExtractor
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BrowseRepository(private val database: BrowseDatabase) {
    private val dao = database.browseEventDao()
    private val audio = database.audioDao()
    private val rollups = database.dailyTopicRollupDao()

    /** Must run inside the same Room transaction as the triggering mutation. */
    private suspend fun refreshRollups(zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) {
        val sql = database.openHelper.writableDatabase
        // Read persisted schema state so a rolled-back first transaction cannot leave a stale process flag.
        val triggersInstalled = sql.query("SELECT 1 FROM sqlite_master WHERE type='trigger' AND name='rollup_event_insert'")
            .use { it.moveToFirst() }
        if (!triggersInstalled) DailyTopicRollupSchema.installTriggers(sql)
        if (rollups.zone() != zone.id) {
            // A timezone change intentionally rebuilds once; ordinary reads only drain changed IDs.
            sql.execSQL("DELETE FROM rollup_facts")
            sql.execSQL("DELETE FROM rollup_contributions")
            sql.execSQL("DELETE FROM daily_topic_rollup")
            sql.execSQL("INSERT OR IGNORE INTO rollup_dirty SELECT id FROM browse_events")
            rollups.putMetadata(RollupMetadata(zone = zone.id))
        }
        while (true) {
            val dirty = rollups.dirty()
            if (dirty.isEmpty()) break
            for (id in dirty) {
                rollups.removeFacts(id)
                rollups.removeContributions(id)
                dao.event(id)?.let { event ->
                    val semantic = dao.semanticsForEvent(id)?.let(SemanticsCodec::decode)
                    val projection = DailyTopicRollupProjector.project(event, semantic, zone)
                    if (projection.contributions.isNotEmpty()) rollups.insertContributions(projection.contributions)
                    if (projection.facts.isNotEmpty()) rollups.insertFacts(projection.facts)
                }
                rollups.clean(id)
            }
        }
    }

    suspend fun dailyTopicRollups(first: java.time.LocalDate, last: java.time.LocalDate,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<DailyTopicRollupEntity> =
        database.withTransaction {
            require(!last.isBefore(first))
            refreshRollups(zone)
            rollups.days(first.toString(), last.toString())
        }

    /** Calendar-day summary. Range distinct counts use small indexed identity rows, never raw text. */
    suspend fun rollupSummary(first: java.time.LocalDate, last: java.time.LocalDate,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        through: Long = Long.MAX_VALUE): DailyRollupSummary = database.withTransaction {
        require(!last.isBefore(first))
        refreshRollups(zone)
        val rows = if (rollups.hasFuture(first.toString(), last.toString(), through))
            rollups.filteredDays(first.toString(), last.toString(), through)
            else rollups.days(first.toString(), last.toString())
        val events = rollups.eventTotals(first.toString(), last.toString(), through)
        val facts = rollups.factTotals(first.toString(), last.toString(), through)
        DailyRollupSummary(rows, events.viewEvents, events.uniqueWorks, facts.dedupFacts, facts.newFacts,
            events.analyzedEvents, events.guidanceEvents, rollups.guidanceMillis(first.toString(), last.toString(), through) / 1000,
            RollupSemanticFingerprint.combine(rollups.semanticKeys(first.toString(), last.toString(), through)))
    }

    suspend fun recentHistoryFacts(topic: String? = null, limit: Int = 15,
        before: Long = Long.MAX_VALUE): List<HistoryFact> = database.withTransaction {
        refreshRollups()
        rollups.history(topic?.trim()?.takeIf(String::isNotEmpty), limit.coerceIn(1, 15), before)
    }
    private val captureMutex = Mutex()
    private val deletionGeneration = AtomicLong()
    private data class DecodedSemantic(val claimsHash: ByteArray, val value: EventSemantics)
    // Accessed only inside Room's serialized transactions. UI and background
    // monitoring collectors can share immutable results without sharing stale flows.
    private val decodedSemantics = mutableMapOf<Long, DecodedSemantic>()
    val recentEvents = dao.observeRecent()
    val allEvents = dao.observeAll().onStart { repairAdjacentVisits() }
    val works = dao.observeWorks()
    // Keep only one page of raw JSON alongside the decoded snapshot. Room emits on
    // table invalidation even when the count is unchanged (for example a reanalysis).
    val semantics = dao.observeSemanticsInvalidation().map {
        database.withTransaction {
            val digest = MessageDigest.getInstance("SHA-256")
            val snapshot = buildList {
                var afterId: Long? = null
                while (true) {
                    val page = afterId?.let { dao.semanticsPage(it, 128) } ?: dao.firstSemanticsPage(128)
                    if (page.isEmpty()) break
                    for (row in page) {
                        decode(row, digest)?.let(::add)
                    }
                    afterId = page.last().eventId
                }
            }
            decodedSemantics.keys.retainAll(snapshot.mapTo(hashSetOf()) { it.eventId })
            snapshot.sortedWith(compareByDescending<EventSemantics> { it.analyzedAt }.thenByDescending { it.eventId })
        }
    }
    val count = dao.observeCount()
    private var repaired = false

    private fun decode(row: BrowseSemantic, digest: MessageDigest): EventSemantics? {
        val hash = digest.digest(row.claimsJson.toByteArray(Charsets.UTF_8))
        val cached = decodedSemantics[row.eventId]
        val value = cached?.value?.takeIf {
            it.analyzedAt == row.analyzedAt && it.inputText == row.inputText &&
                it.inputKind == row.inputKind && cached.claimsHash.contentEquals(hash)
        } ?: SemanticsCodec.decode(row)
        if (value != null) decodedSemantics[row.eventId] = DecodedSemantic(hash, value)
        else decodedSemantics.remove(row.eventId)
        return value
    }

    /** Fresh, bounded reads for the queue's existing 24-hour eligibility window. */
    suspend fun recentTextSnapshot(now: Long): Pair<List<BrowseEvent>, List<EventSemantics>> {
        repairAdjacentVisits()
        return database.withTransaction {
            val events = dao.recentTextEvents(now - 86_400_000L, now)
            val digest = MessageDigest.getInstance("SHA-256")
            val extracted = events.chunked(128).flatMap { page ->
                dao.semanticsForEvents(page.map { it.id }).mapNotNull { decode(it, digest) }
            }
            events to extracted
        }
    }

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
                if (original.identityMethod in setOf("unknown", "session_estimate")) {
                    val hint = WorkIdentityExtractor.extract(original.packageName, original.visibleText)
                    val key = hint.key(original.packageName) ?: continue
                    dao.insertWork(ObservedWork(key, original.packageName, hint.title, hint.author, hint.method,
                        original.timestamp, hint.evidence, hint.nativeId))
                    original.workKey?.takeIf { it != key }?.let { oldKey ->
                        database.workOriginalDao().get(oldKey)?.let { old ->
                            database.workOriginalDao().insert(old.copy(workKey = key))
                        }
                    }
                    dao.update(original.copy(workKey = key, identityMethod = hint.method))
                    dao.updateMaterialIdentity(original.id, key, hint.method)
                }
            }
            // A search fallback remains explicitly marked and never replaces a saved direct link.
            database.workOriginalDao().works().forEach { work -> saveCapturedOriginal(work) }
            dao.trimWorks()
            refreshRollups()
        }
        repaired = true
    }

    suspend fun record(event: BrowseEvent) = database.withTransaction {
        val id = dao.insert(event)
        dao.trimWorks()
        refreshRollups()
        id
    }
    suspend fun updateSession(event: BrowseEvent): Long? = database.withTransaction {
        event.id.takeIf { dao.update(event) > 0 }.also { refreshRollups() }
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

    private suspend fun saveCapturedOriginal(work: ObservedWork, text: String? = null, capturedAt: Long = work.firstSeenAt) {
        val candidate = text?.let { OriginalLinks.fromInput(it, work.packageName) }
            ?.let { WorkOriginal(work.workKey, it, "captured_link", capturedAt) }
            ?: OriginalLinks.forCapturedWork(work) ?: return
        val originals = database.workOriginalDao()
        val existing = originals.get(work.workKey)
        if (existing == null || existing.source == "platform_search" && candidate.source != "platform_search") {
            originals.put(candidate)
        }
    }

    suspend fun findEvent(id: Long): BrowseEvent? = dao.event(id)
    suspend fun getEvent(id: Long): BrowseEvent? = findEvent(id)
    suspend fun saveOriginal(eventId: Long, input: String): Boolean = captureMutex.withLock {
        database.withTransaction {
            val event = dao.event(eventId) ?: return@withTransaction false
            val root = event.parentEventId?.let { dao.event(it) } ?: event
            if (root.sourceType != "accessibility") return@withTransaction false
            val url = OriginalLinks.fromInput(input, root.packageName) ?: return@withTransaction false
            val key = root.workKey ?: "record:${root.id}"
            dao.insertWork(ObservedWork(key, root.packageName, "", "", root.identityMethod, root.timestamp))
            if (root.workKey == null) {
                dao.update(root.copy(workKey = key))
                dao.updateMaterialIdentity(root.id, key, root.identityMethod)
            }
            database.workOriginalDao().put(WorkOriginal(key, url, "user_link", System.currentTimeMillis()))
            refreshRollups()
            true
        }
    }
    suspend fun clearSemantics() = captureMutex.withLock {
        database.withTransaction { dao.clearSemantics(); refreshRollups() }
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
            refreshRollups()
            true
        }
        // Revocation/deletion can change while Room completes the transaction.
        if (saved && !current()) {
            database.withTransaction {
                dao.removeSemanticsVersion(snapshot.id, semantics.analyzedAt, semantics.inputText)
                refreshRollups()
            }
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
            val previousKey = if (continuing) dao.event(event.id)?.workKey else null
            val savedId = if (continuing) {
                // Updates are deliberately never upserts: a deleted session stays deleted.
                event.id.takeIf { dao.update(event) > 0 }
            } else {
                val inserted = dao.insert(event)
                if (current()) inserted else {
                    dao.deleteEvent(inserted)
                    null
                }
            }
            if (savedId != null) {
                work?.let {
                    dao.insertWork(it)
                    val originals = database.workOriginalDao()
                    // Keep a manually attached link when a visit gains a stronger platform identity.
                    if (previousKey != null && previousKey != it.workKey) {
                        originals.get(previousKey)?.let { old -> originals.insert(old.copy(workKey = it.workKey)) }
                    }
                    saveCapturedOriginal(it, event.visibleText, event.timestamp)
                }
                if (continuing) dao.updateMaterialIdentity(savedId, event.workKey, event.identityMethod)
                if (sampleText != null && dao.lastSampleText(savedId) != sampleText) {
                    dao.insertSample(BrowseSample(visitId = savedId,
                        capturedAt = event.endTimestamp, eventType = event.eventType, visibleText = sampleText))
                    dao.trimSamples(savedId)
                }
            }
            dao.trimWorks()
            refreshRollups()
            savedId
        }
        // Settings/deletion may change while Room resumes the transaction result.
        if (id != null && !current()) {
            if (!continuing) database.withTransaction { dao.deleteEvent(id); dao.trimWorks() }
            null
        } else id
    }

    suspend fun deleteEvent(id: Long) {
        deletionGeneration.incrementAndGet()
        captureMutex.withLock { database.withTransaction {
            refreshRollups()
            audio.invalidateAllAnalyses()
            audio.invalidateForEvent(id)
            dao.deleteEvent(id)
            dao.trimWorks()
        } }
    }

    /** One complete, unprotected unit per transaction; selection cannot race an audio append. */
    internal suspend fun evictOldestStorageUnit(beforeTimestamp: Long? = null): Boolean = captureMutex.withLock {
        database.withTransaction {
            refreshRollups()
            val visit = dao.oldestStorageVisit(beforeTimestamp)
            val session = audio.oldestStorageSession(beforeTimestamp)
            if (visit == null && session == null) return@withTransaction false
            // Do not revoke a new visit sampled concurrently with unrelated retention cleanup.
            // Existing capture/semantic writes check the row/parent and never upsert a deleted visit.
            if (session != null && (visit == null || session.startedAt <= visit.timestamp)) {
                audio.invalidateAllAnalyses()
                audio.deleteSession(session.id)
            } else {
                dao.deleteEvent(requireNotNull(visit).id)
            }
            dao.trimWorks()
            true
        }
    }

    suspend fun deletePackage(packageName: String) {
        deletionGeneration.incrementAndGet()
        captureMutex.withLock { database.withTransaction {
            refreshRollups()
            audio.invalidateAllAnalyses()
            audio.deletePackage(packageName)
            dao.deletePackage(packageName)
            dao.trimWorks()
        } }
    }

    suspend fun clear() {
        deletionGeneration.incrementAndGet()
        captureMutex.withLock { database.withTransaction {
            refreshRollups()
            audio.clear()
            dao.clear()
            dao.trimWorks()
        } }
    }
}
