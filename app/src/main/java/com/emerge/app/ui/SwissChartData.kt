package com.emerge.app.ui

import com.emerge.app.analysis.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.roundToInt

internal data class SwissStanceDay(val date: LocalDate, val value: Float?, val counts: Map<ViewpointStance, Int>)

/** Presentation projection only; ambiguous or mixed stances do not become neutral observations. */
internal fun swissStanceTrend(items: List<ViewpointItem>): Float? =
    swissStanceValue(viewpointSpectrum(ViewpointDistribution.topics(ViewpointTimeline.groups(items))))

private fun swissStanceValue(shares: List<ViewpointStanceShare>): Float? {
    val known = shares.filter { it.stance in setOf(ViewpointStance.SUPPORT, ViewpointStance.NEUTRAL, ViewpointStance.QUESTION) }
    val total = known.sumOf { it.count }
    if (total == 0) return null
    return known.sumOf { when (it.stance) {
        ViewpointStance.SUPPORT -> it.count
        ViewpointStance.QUESTION -> -it.count
        else -> 0
    } }.toFloat() / total
}

internal fun swissTopicItems(items: List<ViewpointItem>, selectedTopic: String?): List<ViewpointItem> {
    if (selectedTopic == null) return items
    return items.mapNotNull { item ->
        val claims = item.claims.filter { it.topic == selectedTopic || it.subject == selectedTopic }
        if (claims.isEmpty()) null else item.copy(claims = claims, quote = claims.first().quote)
    }
}

internal fun swissStanceDay(bucket: ViewpointBucket, selectedTopic: String?, zone: ZoneId): SwissStanceDay {
    val items = swissTopicItems(bucket.items, selectedTopic)
    val shares = viewpointSpectrum(ViewpointDistribution.topics(ViewpointTimeline.groups(items)))
    return SwissStanceDay(Instant.ofEpochMilli(bucket.start).atZone(zone).toLocalDate(), swissStanceValue(shares),
        ViewpointStance.entries.associateWith { stance -> shares.firstOrNull { it.stance == stance }?.count ?: 0 })
}

/** Hour bars occupy cells; daily points occupy both ends of the plotting area. */
internal fun swissChartSelection(x: Float, width: Float, count: Int, edgePoints: Boolean = false): Int? {
    if (count <= 0 || width <= 0 || !x.isFinite() || !width.isFinite()) return null
    val fraction = (x / width).coerceIn(0f, 1f)
    return (if (edgePoints) (fraction * (count - 1)).roundToInt() else floor(fraction * count).toInt())
        .coerceIn(0, count - 1)
}
