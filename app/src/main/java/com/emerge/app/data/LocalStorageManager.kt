package com.emerge.app.data

import android.content.Context
import android.os.SystemClock
import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocalStorageUsage(
    val usedBytes: Long = 0,
    val limitBytes: Long = DEFAULT_STORAGE_LIMIT_BYTES,
    val status: String? = null,
    val loading: Boolean = true,
    val retentionDays: Int = 0
)

/** Application-scoped retention. SQLite free pages and WAL duplicates never trigger more deletion. */
class LocalStorageManager(
    context: Context,
    private val database: BrowseDatabase,
    private val repository: BrowseRepository,
    private val workThumbnails: WorkThumbnailStore,
    private val settings: LocalStorageSettings
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val thumbnailDirectory = File(context.noBackupFilesDir, "work-thumbnails")
    private val state = MutableStateFlow(LocalStorageUsage(limitBytes = settings.limitBytes.value,
        retentionDays = settings.retentionDays.value))
    val usage: StateFlow<LocalStorageUsage> = state.asStateFlow()
    private var lastCompactionAttempt = Long.MIN_VALUE
    private var compactionStatus: String? = null
    private val observer = object : InvalidationTracker.Observer(
        "browse_events", "browse_samples", "browse_semantics", "observed_works", "audio_sessions",
        "audio_transcripts", "work_thumbnails", "work_originals", "monitor_tasks", "monitor_task_inputs"
    ) {
        override fun onInvalidated(tables: Set<String>) { refresh() }
    }

    init {
        scope.launch {
            database.invalidationTracker.addObserver(observer)
            try {
                refresh()
                // A deletion invalidates Room too. Conflation queues a later check without cancelling it.
                for (request in requests) {
                    delay(750)
                    try {
                        maintain()
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        state.update { it.copy(limitBytes = settings.limitBytes.value, retentionDays = settings.retentionDays.value,
                            status = "存储检查失败，请重试", loading = false) }
                    }
                }
            } finally {
                database.invalidationTracker.removeObserver(observer)
            }
        }
        scope.launch {
            settings.limitBytes.collect { limit ->
                state.update { it.copy(limitBytes = limit) }
                refresh()
            }
        }
        scope.launch {
            settings.retentionDays.collect { days ->
                state.update { it.copy(retentionDays = days) }
                refresh()
            }
        }
        scope.launch {
            while (isActive) {
                delay(RETENTION_CHECK_MILLIS)
                if (settings.retentionDays.value > 0) refresh()
            }
        }
    }

    fun refresh() { requests.trySend(Unit) }

    private suspend fun maintain() {
        // Do not hold a database transaction while acquiring the thumbnail store's mutex.
        workThumbnails.maintain()
        var removed = 0
        while (removed < MAX_UNITS_PER_PASS) {
            currentCoroutineContext().ensureActive()
            val cutoff = storageRetentionCutoff(settings.retentionDays.value, System.currentTimeMillis()) ?: break
            if (!repository.evictOldestStorageUnit(beforeTimestamp = cutoff)) break
            removed++
        }
        if (removed > 0) workThumbnails.maintain()
        var snapshot = snapshot()
        var protectedOnly = false
        while (snapshot.usedBytes > settings.limitBytes.value && removed < MAX_UNITS_PER_PASS) {
            currentCoroutineContext().ensureActive()
            if (!repository.evictOldestStorageUnit()) {
                protectedOnly = true
                break
            }
            removed++
            workThumbnails.maintain()
            snapshot = snapshot()
        }
        // Reclaim allocation separately. A failed VACUUM must never cause another record to be removed.
        compactIfUseful(snapshot)
        snapshot = snapshot()
        val limit = settings.limitBytes.value
        val overLimit = snapshot.usedBytes > limit
        state.value = LocalStorageUsage(snapshot.usedBytes, limit, when {
            overLimit && protectedOnly -> "已超出上限，使用中的记录暂无法清理"
            overLimit -> "正在清理最早记录"
            else -> compactionStatus
        }, loading = false, retentionDays = settings.retentionDays.value)
        // Yield between bounded batches; an active session larger than the quota exits above.
        if (!protectedOnly && removed == MAX_UNITS_PER_PASS) refresh()
    }

    private data class Snapshot(val usedBytes: Long, val pageCount: Long, val freePages: Long, val pageSize: Long) {
        val reusableBytes: Long get() = retainedStorageBytes(freePages, 0, pageSize, 0)
    }

    private suspend fun snapshot(): Snapshot {
        val pages = database.withTransaction {
            val sqlite = database.openHelper.writableDatabase
            Triple(sqlite.pragmaLong("page_count"), sqlite.pragmaLong("freelist_count"), sqlite.pragmaLong("page_size"))
        }
        val thumbnails = thumbnailDirectory.listFiles().orEmpty().filter { it.isFile }
            .fold(0L) { bytes, file -> saturatedStorageSum(bytes, file.length().coerceAtLeast(0)) }
        return Snapshot(retainedStorageBytes(pages.first, pages.second, pages.third, thumbnails),
            pages.first, pages.second, pages.third)
    }

    private fun compactIfUseful(snapshot: Snapshot) {
        val sqlite = database.openHelper.writableDatabase
        val file = sqlite.path?.let(::File) ?: return
        val allocationOverLimit = snapshot.freePages > 0 &&
            saturatedStorageSum(snapshot.usedBytes, snapshot.reusableBytes) > settings.limitBytes.value
        val substantialFreeSpace = snapshot.reusableBytes >= COMPACTION_THRESHOLD_BYTES &&
            snapshot.freePages >= snapshot.pageCount / 4
        // Small reusable allocations are retained unless they leave physical allocation over the quota.
        if (!file.isFile || (!allocationOverLimit && !substantialFreeSpace)) {
            compactionStatus = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (lastCompactionAttempt != Long.MIN_VALUE && now - lastCompactionAttempt < COMPACTION_RETRY_MILLIS) return
        lastCompactionAttempt = now
        val databaseBytes = file.length()
        val required = if (databaseBytes > (Long.MAX_VALUE - COMPACTION_THRESHOLD_BYTES) / 2) Long.MAX_VALUE
            else databaseBytes * 2 + COMPACTION_THRESHOLD_BYTES
        if (file.parentFile?.usableSpace?.let { it >= required } != true) {
            compactionStatus = "记录已清理，磁盘空间回收待完成"
            return
        }
        try {
            if (sqlite.inTransaction()) {
                compactionStatus = "记录已清理，磁盘空间回收待完成"
                return
            }
            val busy = sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() && it.getLong(0) != 0L }
            if (busy) {
                compactionStatus = "记录已清理，磁盘空间回收待完成"
                return
            }
            sqlite.execSQL("VACUUM")
            val stillBusy = sqlite.query("PRAGMA wal_checkpoint(TRUNCATE)").use {
                it.moveToFirst() && it.getLong(0) != 0L
            }
            compactionStatus = if (stillBusy) "记录已清理，磁盘空间回收待完成" else null
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            compactionStatus = "记录已清理，磁盘空间回收待完成"
        }
    }

    private fun SupportSQLiteDatabase.pragmaLong(name: String): Long = query("PRAGMA $name").use {
        check(it.moveToFirst())
        it.getLong(0)
    }

    override fun close() {
        scope.cancel()
        requests.close()
    }

    private companion object {
        const val MAX_UNITS_PER_PASS = 128
        const val COMPACTION_THRESHOLD_BYTES = 32L * 1024 * 1024
        const val COMPACTION_RETRY_MILLIS = 60_000L
        const val RETENTION_CHECK_MILLIS = 60 * 60_000L
    }
}
