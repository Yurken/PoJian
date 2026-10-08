package com.emerge.app.capture

import com.emerge.app.data.BrowseEvent

internal data class CaptureSample(
    val event: BrowseEvent,
    val continuing: Boolean,
    val elapsedRealtime: Long,
    val revision: Long
)

/** Keeps deleted sessions as in-memory tombstones until the visible content changes. */
internal class CaptureSessionTracker {
    private var session: BrowseEvent? = null
    private var lastElapsed = 0L
    private var deleted = false
    @Volatile private var revision = 0L
    val packageName: String? get() = session?.packageName

    fun reset() {
        revision++
        session = null
        lastElapsed = 0L
        deleted = false
    }

    fun sample(packageName: String, text: String, now: Long, elapsed: Long, type: Int): CaptureSample {
        val old = session
        val gap = elapsed - lastElapsed
        val continuing = old != null && old.packageName == packageName && (gap in 0..6000 || deleted) &&
            ContentContinuity.same(old.visibleText, text)
        val event = if (continuing) old!!.copy(
            endTimestamp = now,
            durationMillis = old.durationMillis + (gap.takeIf { it in 0..6000 } ?: 0L),
            sampleCount = old.sampleCount + 1
        ) else BrowseEvent(
            packageName = packageName, timestamp = now, eventType = type,
            visibleText = text, endTimestamp = now, sampleCount = 1
        )
        return CaptureSample(event, continuing, elapsed, revision)
    }

    fun isCurrent(sample: CaptureSample): Boolean = sample.revision == revision

    fun saved(sample: CaptureSample, id: Long?) {
        if (!isCurrent(sample)) return
        // A missing row is not a reason to recreate it on the next periodic refresh.
        session = sample.event.copy(id = id ?: sample.event.id.takeIf { it > 0 } ?: -1)
        lastElapsed = sample.elapsedRealtime
        deleted = id == null
    }

}
