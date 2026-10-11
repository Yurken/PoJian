package com.emerge.app.capture

import com.emerge.app.data.BrowseEvent
import com.emerge.app.data.ObservedWork
import java.util.UUID

internal data class CaptureSample(
    val event: BrowseEvent,
    val continuing: Boolean,
    val elapsedRealtime: Long,
    val revision: Long,
    val rawText: String,
    val hint: WorkHint,
    val work: ObservedWork,
    val region: VisibilityRegion
)

/** Keeps deleted sessions as in-memory tombstones until the visible content changes. */
internal class CaptureSessionTracker {
    private var session: BrowseEvent? = null
    private var lastElapsed = 0L
    private var deleted = false
    private var lastText = ""
    private var lastHint = WorkHint()
    private var work: ObservedWork? = null
    private var paused = false
    private var lastRegion = VisibilityRegion.UNLOCATED
    fun pause() { paused = true }
    @Volatile private var revision = 0L
    val packageName: String? get() = session?.packageName
    fun canAttachComments(packageName: String): Boolean = session?.packageName == packageName &&
        lastHint.page in setOf(WorkPage.VIDEO, WorkPage.CAPTION)

    fun reset() {
        revision++
        session = null
        lastElapsed = 0L
        deleted = false
        lastText = ""
        lastHint = WorkHint()
        work = null
        paused = false
        lastRegion = VisibilityRegion.UNLOCATED
    }

    fun sample(packageName: String, text: String, now: Long, elapsed: Long, type: Int,
        hint: WorkHint = WorkIdentityExtractor.extract(packageName, text)): CaptureSample {
        val old = session
        val gap = elapsed - lastElapsed
        val region = VisibilityIntervals.region(hint)
        val inherited = hint.page == WorkPage.COMMENTS && canAttachComments(packageName)
        val matchedCaption = hint.matchesCaption(lastHint)
        val effectiveHint = if (inherited || matchedCaption) lastHint else hint
        val key = effectiveHint.key(packageName)
        val conflictingIdentity = (key != null && lastHint.key(packageName) != null && key != lastHint.key(packageName)) ||
            (hint.author.isNotBlank() && lastHint.author.isNotBlank() && hint.author != lastHint.author)
        val same = !conflictingIdentity && (inherited || matchedCaption || (key != null && key == old?.workKey) ||
            ContentContinuity.same(lastText, text) || hint.followsGallery(lastHint))
        // A deleted visible work must not become a new insert through clock or capacity splitting.
        if (deleted && old != null && old.packageName == packageName && same) {
            return CaptureSample(old, true, elapsed, revision, text, effectiveHint, work!!, region)
        }
        val wallGap = old?.let { now - it.endTimestamp } ?: 0L
        val clockDiscontinuity = old != null && (wallGap < 0 || gap < 0 ||
            gap <= 6000 && kotlin.math.abs(wallGap - gap) > 1000)
        var continuing = old != null && old.packageName == packageName && !clockDiscontinuity &&
            (gap in 0..6000 || (paused && gap in 0..15_000) || deleted) && same
        var intervals = if (continuing) old!!.visibilityIntervals else ""
        val measured = if (continuing && !paused && gap in 1..6000 && wallGap > 0) minOf(gap, wallGap) else 0L
        if (measured > 0) {
            val added = VisibilityIntervals.append(intervals ?: "", VisibilityInterval(old!!.endTimestamp, now,
                measured, if (region == lastRegion) region else VisibilityRegion.UNLOCATED))
            if (added == null) continuing = false else intervals = added
        }
        // Clock changes and interval capacity split a visit while retaining its known adjacent work identity.
        val splitSameWork = old != null && old.packageName == packageName && same &&
            (clockDiscontinuity || !continuing && measured > 0)
        val content = if ((continuing || splitSameWork) && (key == null || work!!.identityMethod != "session_estimate")) work!! else ObservedWork(
            key ?: "visit:${UUID.randomUUID()}", packageName, hint.title, hint.author,
            if (key == null) "session_estimate" else hint.method, if (continuing) old!!.timestamp else now)
        val event = if (continuing) old!!.copy(
            workKey = content.workKey, identityMethod = content.identityMethod,
            visibleText = (old.visibleText.lines() + text.lines()).distinct().joinToString("\n").take(16000),
            endTimestamp = now,
            durationMillis = old.durationMillis + measured,
            sampleCount = old.sampleCount + 1,
            visibilityIntervals = intervals
        ) else BrowseEvent(
            packageName = packageName, timestamp = if (measured > 0 && !clockDiscontinuity) old!!.endTimestamp else now, eventType = type,
            visibleText = text, endTimestamp = now, sampleCount = 1,
            workKey = content.workKey, identityMethod = content.identityMethod,
            visibilityIntervals = if (measured > 0 && !clockDiscontinuity)
                VisibilityIntervals.encode(listOf(VisibilityInterval(old!!.endTimestamp, now, measured,
                    if (region == lastRegion) region else VisibilityRegion.UNLOCATED))) else "",
            durationMillis = if (measured > 0 && !clockDiscontinuity) measured else 0L
        )
        return CaptureSample(event, continuing, elapsed, revision, text, effectiveHint, content, region)
    }

    fun isCurrent(sample: CaptureSample): Boolean = sample.revision == revision

    fun saved(sample: CaptureSample, id: Long?) {
        if (!isCurrent(sample)) return
        // A missing row is not a reason to recreate it on the next periodic refresh.
        session = sample.event.copy(id = id ?: sample.event.id.takeIf { it > 0 } ?: -1)
        lastText = sample.rawText
        lastHint = sample.hint
        work = sample.work
        lastElapsed = sample.elapsedRealtime
        deleted = id == null
        paused = false
        lastRegion = sample.region
    }

}
