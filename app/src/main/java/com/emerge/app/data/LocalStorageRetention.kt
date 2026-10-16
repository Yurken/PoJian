package com.emerge.app.data

val STORAGE_RETENTION_DAYS: List<Int> = listOf(0, 7, 30, 90)

/** Zero keeps the capacity-only policy; a period is a rolling number of complete days. */
internal fun storageRetentionCutoff(days: Int, now: Long): Long? {
    require(days in STORAGE_RETENTION_DAYS)
    if (days == 0) return null
    val duration = days * 86_400_000L
    return if (now < Long.MIN_VALUE + duration) Long.MIN_VALUE else now - duration
}

internal fun shortensStorageRetention(previousDays: Int, nextDays: Int): Boolean =
    nextDays > 0 && (previousDays == 0 || nextDays < previousDays)
