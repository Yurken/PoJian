package com.emerge.app.ui

import com.emerge.app.analysis.ClaimObservation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

internal data class SwissEvidenceBucket(val label: String, val fresh: Int, val repeated: Int, val unknown: Int,
    val start: Long, val end: Long) {
    val total: Int get() = fresh + repeated + unknown
}

/** Display counts of retained fragments; never a count of verified independent sources. */
internal fun swissEvidenceTrend(claims: List<ClaimObservation>, now: Long,
    zone: ZoneId = ZoneId.systemDefault()): List<SwissEvidenceBucket> {
    val start = now - 86_400_000L
    val first = Instant.ofEpochMilli(start).atZone(zone).truncatedTo(ChronoUnit.HOURS)
    val end = Instant.ofEpochMilli(now).atZone(zone).truncatedTo(ChronoUnit.HOURS)
    val hours = ChronoUnit.HOURS.between(first, end).toInt() + 1
    val material = claims.filter { it.timestamp in start..now }.groupBy {
        Instant.ofEpochMilli(it.timestamp).atZone(zone).truncatedTo(ChronoUnit.HOURS)
    }
    return List(hours) { index ->
        val hour = first.plusHours(index.toLong())
        val fragments = material[hour].orEmpty().flatMap { claim -> claim.evidenceDetails.map { claim.eventId to it } }
            .distinctBy { (id, fragment) -> id to fragment.text }.map { it.second }
        val repeated = fragments.count { !it.identityUnresolved && it.historyMatches.isNotEmpty() }
        val fresh = fragments.count { !it.identityUnresolved && it.historyMatches.isEmpty() && it.isNew }
        SwissEvidenceBucket(hour.format(DateTimeFormatter.ofPattern("HH:mm")), fresh, repeated, fragments.size - fresh - repeated,
            maxOf(start, hour.toInstant().toEpochMilli()), minOf(now, hour.plusHours(1).toInstant().toEpochMilli()))
    }
}

internal fun swissEvidenceInterval(bucket: SwissEvidenceBucket, zone: ZoneId = ZoneId.systemDefault()): String {
    val format = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    return "${Instant.ofEpochMilli(bucket.start).atZone(zone).format(format)} – ${Instant.ofEpochMilli(bucket.end).atZone(zone).format(format)}"
}
