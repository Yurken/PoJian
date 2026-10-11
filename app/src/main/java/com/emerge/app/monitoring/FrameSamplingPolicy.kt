package com.emerge.app.monitoring

/** Main-thread policy. Times are elapsedRealtime, never wall-clock timestamps. */
internal class FrameSamplingPolicy {
    private var context = ""
    private var stableSince = 0L
    private var lastAttempt: Long? = null
    private var unchanged = 0
    private var fingerprint: String? = null
    private var completedAt = 0L

    fun observeContext(value: String, now: Long) {
        if (value == context) return
        context = value
        stableSince = now
        unchanged = 0
        fingerprint = null
        // Keep the global rate limit when scrolling or switching applications.
    }

    fun shouldCapture(now: Long, intervalSeconds: Int, manual: Boolean, blocked: Boolean): Boolean {
        if (context.isBlank() || blocked) return false
        if (manual) return true
        if (now - stableSince < 800) return false
        val base = intervalSeconds.coerceIn(15, 120) * 1000L
        val interval = (base * (1 shl unchanged)).coerceAtMost(120_000L)
        return lastAttempt?.let { now - it >= interval } ?: true
    }

    fun started(now: Long) { lastAttempt = now }

    fun previousFingerprint(now: Long): String? =
        fingerprint?.takeIf { now - completedAt in 0 until 300_000L }

    fun sampled(scope: String, duplicate: Boolean) {
        if (scope == context) unchanged = if (duplicate) (unchanged + 1).coerceAtMost(2) else 0
    }

    fun completed(scope: String, value: String, success: Boolean, now: Long) {
        if (scope != context) return
        fingerprint = value.takeIf { success }
        completedAt = now
        if (!success) unchanged = 0
    }
}
