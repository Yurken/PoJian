package com.emerge.app.monitoring

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.room.withTransaction
import com.emerge.app.data.BrowseDatabase
import com.emerge.app.data.BrowseEvent
import com.emerge.app.data.MonitorTask
import com.emerge.app.data.MonitorTaskInput
import com.emerge.app.data.MonitorTaskSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class MonitorTaskRequest(val requestId: String, val packageName: String, val kind: String,
    val text: String, val capturedAt: Long, val contextToken: String, val encounter: String,
    val snapshots: List<BrowseEvent>, val serviceFingerprint: String, val authorizationKey: String,
    val checkFacts: Boolean)

data class MonitorTaskLease(val task: MonitorTask, val request: MonitorTaskRequest, val owner: String)
data class MonitorTaskCounts(val queued: Int = 0, val running: Int = 0, val retrying: Int = 0,
    val failed: Int = 0, val completed: Int = 0)
enum class EnqueueResult { ENQUEUED, DUPLICATE, FULL, INVALID }

/** All transitions and dependency checks run in SQLite transactions, including concurrent claimers. */
class MonitorTaskStore(private val database: BrowseDatabase,
    private val cipher: MonitorPayloadCipher = MonitorPayloadCipher()) {
    private val dao = database.monitorTaskDao()
    val tasks: Flow<List<MonitorTask>> = dao.observeTasks()
    val counts = tasks.map { rows -> MonitorTaskCounts(
        queued = rows.count { it.status == QUEUED }, running = rows.count { it.status == RUNNING },
        retrying = rows.count { it.status == QUEUED && it.attempts > 0 },
        failed = rows.count { it.status == FAILED }, completed = rows.count { it.status == COMPLETED }) }

    fun observeInputStates(): Flow<Map<Long, String>> = dao.observeInputStates().map { rows ->
        buildMap {
            val seen = mutableSetOf<Long>()
            rows.forEach { row -> if (seen.add(row.eventId) && row.status != CANCELLED) put(row.eventId, row.status) }
        }
    }

    suspend fun enqueue(request: MonitorTaskRequest, now: Long = System.currentTimeMillis(),
        permitted: () -> Boolean = { true }): EnqueueResult = transaction {
        pruneLocked(now)
        if (!permitted() || !validRequest(request) || request.capturedAt > now ||
            now - request.capturedAt >= TTL) return@transaction EnqueueResult.INVALID
        for (snapshot in request.snapshots) {
            val current = dao.event(snapshot.id) ?: return@transaction EnqueueResult.INVALID
            if (fingerprint(current) != fingerprint(snapshot)) return@transaction EnqueueResult.INVALID
        }
        val key = taskKey(request)
        if (dao.get(key) != null) return@transaction EnqueueResult.DUPLICATE
        if (dao.pendingCount() >= CAPACITY) return@transaction EnqueueResult.FULL
        val envelope = runCatching { cipher.seal(encode(request), key) }.getOrNull()
            ?: return@transaction EnqueueResult.INVALID
        if (!permitted()) return@transaction EnqueueResult.INVALID
        dao.insert(MonitorTask(key, request.snapshots.last().id, envelope, MonitorPolicy.hash(request.packageName),
            request.serviceFingerprint, request.authorizationKey, QUEUED, 0, now,
            minOf(now + TTL, request.capturedAt + TTL), now, now, "", 0, ""))
        dao.insertInputs(request.snapshots.map { MonitorTaskInput(key, it.id, fingerprint(it)) })
        if (!permitted()) {
            terminal(requireNotNull(dao.get(key)), CANCELLED, "authorization_changed", now)
            EnqueueResult.INVALID
        } else EnqueueResult.ENQUEUED
    }

    suspend fun claim(now: Long = System.currentTimeMillis(),
        permitted: (MonitorTaskRequest) -> Boolean = { true }): MonitorTaskLease? = transaction {
        pruneLocked(now)
        recoverLocked(now)
        for (task in dao.all().filter { it.status == QUEUED && it.nextAttemptAt <= now }) {
            val request = load(task)
            if (request == null) { terminal(task, FAILED, "invalid_payload", now); continue }
            if (!inputsValid(task, request)) { terminal(task, CANCELLED, "input_changed", now); continue }
            if (!permitted(request)) { terminal(task, CANCELLED, "authorization_changed", now); continue }
            if (task.attempts >= MAX_ATTEMPTS) { terminal(task, FAILED, "retry_exhausted", now); continue }
            val owner = "${Process.myPid()}|$PROCESS_SESSION|${UUID.randomUUID()}"
            val running = task.copy(status = RUNNING, attempts = task.attempts + 1, leaseOwner = owner,
                leaseUntil = now + LEASE_MILLIS, updatedAt = now)
            dao.update(running)
            if (!permitted(request)) {
                terminal(running, CANCELLED, "authorization_changed", now)
                continue
            }
            return@transaction MonitorTaskLease(running, request, owner)
        }
        null
    }

    suspend fun isLeaseCurrent(lease: MonitorTaskLease, now: Long = System.currentTimeMillis(),
        permitted: () -> Boolean = { true }): Boolean = transaction {
        val task = dao.get(lease.task.id) ?: return@transaction false
        permitted() && owns(task, lease, now) && inputsValid(task, lease.request)
    }

    suspend fun finish(lease: MonitorTaskLease, now: Long = System.currentTimeMillis(),
        permitted: () -> Boolean = { true }): Boolean = transaction {
        val task = dao.get(lease.task.id) ?: return@transaction false
        if (task.status == COMPLETED && task.leaseOwner == lease.owner)
            return@transaction permitted() && inputsValid(task, lease.request)
        if (!owns(task, lease, now)) return@transaction false
        if (!permitted() || !inputsValid(task, lease.request)) {
            terminal(task, CANCELLED, "authorization_changed", now)
            return@transaction false
        }
        // Keep only deduplication metadata and the owner for idempotent completion.
        dao.update(task.copy(status = COMPLETED, payload = "", leaseUntil = 0, updatedAt = now, lastError = ""))
        true
    }

    suspend fun retry(lease: MonitorTaskLease, reason: String, now: Long = System.currentTimeMillis(),
        permitted: () -> Boolean = { true }): Boolean = transaction {
        val task = dao.get(lease.task.id) ?: return@transaction false
        if (!owns(task, lease, now)) return@transaction false
        if (!permitted() || !inputsValid(task, lease.request)) {
            terminal(task, CANCELLED, "authorization_changed", now)
            return@transaction false
        }
        if (task.attempts >= MAX_ATTEMPTS) {
            terminal(task, FAILED, "retry_exhausted", now)
            return@transaction false
        }
        val next = now + RETRY_DELAYS[(task.attempts - 1).coerceIn(RETRY_DELAYS.indices)]
        if (next >= task.expiresAt) {
            terminal(task, FAILED, "expired", now)
            return@transaction false
        }
        dao.update(task.copy(status = QUEUED, nextAttemptAt = next, leaseOwner = "", leaseUntil = 0,
            updatedAt = now, lastError = safeReason(reason)))
        true
    }

    suspend fun fail(lease: MonitorTaskLease, reason: String, now: Long = System.currentTimeMillis(),
        permitted: () -> Boolean = { true }): Boolean = transaction {
        val task = dao.get(lease.task.id) ?: return@transaction false
        if (!owns(task, lease, now)) return@transaction false
        if (!permitted() || !inputsValid(task, lease.request)) {
            terminal(task, CANCELLED, "authorization_changed", now)
            false
        } else { terminal(task, FAILED, safeReason(reason), now); true }
    }

    suspend fun cancel(lease: MonitorTaskLease) = transaction {
        val task = dao.get(lease.task.id)
        if (task != null && task.status == RUNNING && task.leaseOwner == lease.owner)
            terminal(task, CANCELLED, "cancelled", System.currentTimeMillis())
    }
    suspend fun cancelAll() = transaction {
        dao.all().filter { it.status in PENDING }.forEach { terminal(it, CANCELLED, "authorization_changed", System.currentTimeMillis()) }
    }
    suspend fun cancelPackage(packageName: String) = transaction {
        val scope = MonitorPolicy.hash(packageName)
        dao.all().filter { it.status in PENDING && it.scopeFingerprint == scope }
            .forEach { terminal(it, CANCELLED, "authorization_changed", System.currentTimeMillis()) }
    }
    suspend fun clear() = transaction { dao.clear() }
    suspend fun prune(now: Long = System.currentTimeMillis()) = transaction { pruneLocked(now) }
    suspend fun recover(now: Long = System.currentTimeMillis()) = transaction { pruneLocked(now); recoverLocked(now) }
    suspend fun nextReadyAt(now: Long = System.currentTimeMillis()): Long? = transaction {
        pruneLocked(now)
        recoverLocked(now)
        dao.nextReadyAt(now)
    }

    private suspend fun <T> transaction(block: suspend () -> T): T = database.withTransaction {
        // Room creates tables on a fresh database; migration creates these triggers on old databases.
        MonitorTaskSchema.installTriggers(database.openHelper.writableDatabase)
        block()
    }

    private suspend fun pruneLocked(now: Long) {
        dao.deleteExpired(now)
        for (task in dao.all().filter { it.status in PENDING }) {
            val request = load(task)
            if (request == null) terminal(task, FAILED, "invalid_payload", now)
            else if (!inputsValid(task, request)) terminal(task, CANCELLED, "input_changed", now)
        }
    }

    private suspend fun recoverLocked(now: Long) {
        for (task in dao.all().filter { it.status == RUNNING && (it.leaseUntil <= now || !ownerAlive(it.leaseOwner)) }) {
            if (task.attempts >= MAX_ATTEMPTS) terminal(task, FAILED, "retry_exhausted", now)
            else dao.update(task.copy(status = QUEUED, nextAttemptAt = now, leaseOwner = "", leaseUntil = 0,
                updatedAt = now, lastError = "lease_recovered"))
        }
    }

    private suspend fun inputsValid(task: MonitorTask, request: MonitorTaskRequest): Boolean {
        val inputs = dao.inputs(task.id)
        if (inputs.size != request.snapshots.size || inputs.map { it.eventId }.toSet() != request.snapshots.map { it.id }.toSet()) return false
        for (input in inputs) {
            val event = dao.event(input.eventId) ?: return false
            if (fingerprint(event) != input.inputFingerprint ||
                fingerprint(request.snapshots.first { it.id == input.eventId }) != input.inputFingerprint) return false
        }
        return true
    }

    private suspend fun terminal(task: MonitorTask, status: String, reason: String, now: Long) {
        dao.update(task.copy(status = status, payload = "", leaseOwner = "", leaseUntil = 0,
            updatedAt = now, lastError = safeReason(reason)))
    }
    private fun owns(task: MonitorTask, lease: MonitorTaskLease, now: Long) =
        task.status == RUNNING && task.leaseOwner == lease.owner && task.leaseUntil > now &&
            task.expiresAt > now && task.payload == lease.task.payload

    private fun load(task: MonitorTask): MonitorTaskRequest? = runCatching {
        decode(cipher.open(task.payload, task.id)).also {
            require(validRequest(it) && taskKey(it) == task.id &&
                MonitorPolicy.hash(it.packageName) == task.scopeFingerprint &&
                it.serviceFingerprint == task.serviceFingerprint && it.authorizationKey == task.authorizationKey)
        }
    }.getOrNull()

    private fun validRequest(request: MonitorTaskRequest): Boolean = request.requestId.length in 1..120 &&
        request.packageName.isNotBlank() && request.packageName.length <= 200 && request.kind in setOf("text", "audio") &&
        request.text.isNotBlank() && request.text.length <= 6000 && request.capturedAt >= 0 &&
        request.contextToken.length <= 256 && request.encounter.length <= 256 &&
        HASH.matches(request.serviceFingerprint) && HASH.matches(request.authorizationKey) &&
        request.snapshots.size in 1..4 && request.snapshots.map { it.id }.distinct().size == request.snapshots.size &&
        request.snapshots.all { it.id > 0 && it.packageName == request.packageName &&
            it.visibleText.isNotBlank() && it.visibleText.length <= 16_000 && it.sourceType != "visual_model" }

    private fun fingerprint(event: BrowseEvent): String = MonitorPolicy.hash(JSONObject()
        .put("package", event.packageName).put("source", event.sourceType).put("text", event.visibleText)
        .put("timestamp", event.timestamp).put("audioSession", event.audioSessionId ?: JSONObject.NULL)
        .put("mediaStart", event.mediaStartMillis).put("mediaEnd", event.mediaEndMillis)
        .put("work", event.workKey ?: JSONObject.NULL).toString())
    private fun taskKey(request: MonitorTaskRequest): String = MonitorPolicy.hash(JSONObject()
        .put("request", request.requestId).put("kind", request.kind).put("text", request.text)
        .put("service", request.serviceFingerprint).put("authorization", request.authorizationKey)
        .put("facts", request.checkFacts).put("inputs", JSONArray().apply {
            request.snapshots.sortedBy { it.id }.forEach { put(JSONObject().put("id", it.id).put("hash", fingerprint(it))) }
        }).toString())

    private fun encode(request: MonitorTaskRequest) = JSONObject().apply {
        put("requestId", request.requestId); put("packageName", request.packageName); put("kind", request.kind)
        put("text", request.text); put("capturedAt", request.capturedAt); put("contextToken", request.contextToken)
        put("encounter", request.encounter); put("serviceFingerprint", request.serviceFingerprint)
        put("authorizationKey", request.authorizationKey); put("checkFacts", request.checkFacts)
        put("snapshots", JSONArray().apply { request.snapshots.forEach { event -> put(JSONObject().apply {
            put("id", event.id); put("packageName", event.packageName); put("timestamp", event.timestamp)
            put("eventType", event.eventType); put("visibleText", event.visibleText); put("sourceType", event.sourceType)
            put("endTimestamp", event.endTimestamp); put("durationMillis", event.durationMillis); put("sampleCount", event.sampleCount)
            put("audioSessionId", event.audioSessionId ?: JSONObject.NULL)
            put("mediaStartMillis", event.mediaStartMillis); put("mediaEndMillis", event.mediaEndMillis)
            put("workKey", event.workKey ?: JSONObject.NULL); put("identityMethod", event.identityMethod)
            // Visibility intervals affect dwell estimates and are deliberately outside this semantic snapshot.
        }) } })
    }.toString()

    private fun decode(plaintext: String): MonitorTaskRequest {
        val json = JSONObject(plaintext)
        fun string(name: String) = json.get(name) as? String ?: error("Invalid queued field")
        val rows = json.getJSONArray("snapshots")
        require(rows.length() in 1..4)
        val snapshots = (0 until rows.length()).map { index -> rows.getJSONObject(index).let { event ->
            fun value(name: String) = event.get(name) as? String ?: error("Invalid snapshot field")
            BrowseEvent(id = event.getLong("id"), packageName = value("packageName"), timestamp = event.getLong("timestamp"),
                eventType = event.getInt("eventType"), visibleText = value("visibleText"), sourceType = value("sourceType"),
                endTimestamp = event.getLong("endTimestamp"), durationMillis = event.getLong("durationMillis"), sampleCount = event.getInt("sampleCount"),
                audioSessionId = if (event.isNull("audioSessionId")) null else value("audioSessionId"),
                mediaStartMillis = event.getLong("mediaStartMillis"), mediaEndMillis = event.getLong("mediaEndMillis"),
                workKey = if (event.isNull("workKey")) null else value("workKey"), identityMethod = value("identityMethod"))
        } }
        return MonitorTaskRequest(string("requestId"), string("packageName"), string("kind"), string("text"),
            json.getLong("capturedAt"), string("contextToken"), string("encounter"), snapshots,
            string("serviceFingerprint"), string("authorizationKey"), json.get("checkFacts") as? Boolean
                ?: error("Invalid queued fact mode"))
    }

    private fun ownerAlive(owner: String): Boolean {
        val parts = owner.split('|')
        val pid = parts.firstOrNull()?.toIntOrNull() ?: return false
        if (parts.size != 3 || pid <= 0) return false
        if (pid == Process.myPid()) return parts[1] == PROCESS_SESSION
        return try { Os.kill(pid, 0); true } catch (error: ErrnoException) { error.errno != OsConstants.ESRCH }
    }
    private fun safeReason(reason: String) = if (reason in REASONS || Regex("http_[1-5][0-9]{2}").matches(reason)) reason else "processing_failed"

    companion object {
        private const val QUEUED = "queued"
        private const val RUNNING = "running"
        private const val COMPLETED = "completed"
        private const val FAILED = "failed"
        private const val CANCELLED = "cancelled"
        private val PENDING = setOf(QUEUED, RUNNING)
        private val HASH = Regex("[a-f0-9]{64}")
        internal const val CAPACITY = 6
        private const val MAX_ATTEMPTS = 3
        private const val TTL = 24 * 60 * 60 * 1000L
        private const val LEASE_MILLIS = 120_000L
        private val RETRY_DELAYS = longArrayOf(5000, 30_000, 120_000)
        private val PROCESS_SESSION = UUID.randomUUID().toString()
        private val REASONS = setOf("cancelled", "authorization_changed", "input_changed", "input_deleted", "expired",
            "invalid_payload", "invalid_response", "network", "frame_budget", "request_budget", "retry_exhausted",
            "lease_recovered", "processing_failed")
    }
}
