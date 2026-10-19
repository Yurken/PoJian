package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.time.ZoneId

data class TopicTimeGroup(val title: String, val works: List<ViewpointItem>)
data class TopicTimeBucket(val label: String, val start: Long, val end: Long,
    val works: List<ViewpointItem>, val topics: List<TopicTimeGroup>)

object TopicTimeline {
    fun build(events: List<BrowseEvent>, period: StatisticsPeriod, now: Long, zone: ZoneId,
        report: CognitiveReport? = null, semantics: List<EventSemantics> = emptyList()): List<TopicTimeBucket> =
        fromViewpoints(ViewpointTimeline.build(events, period, now, zone, report, semantics))

    fun fromViewpoints(buckets: List<ViewpointBucket>): List<TopicTimeBucket> = buckets.map { bucket ->
            val works = ViewpointTimeline.details(bucket.items)
            val topics = linkedMapOf<String, MutableList<ViewpointItem>>()
            works.forEach { work ->
                val names = work.claims.map { claim ->
                    claim.topic.trim().takeIf { it.isNotEmpty() }
                        ?: WorkTopicPresentation.category(claim).takeUnless { it == "其他" }
                        ?: claim.label
                }.ifEmpty { listOf("未识别") }.map(LiteralWhitespace::collapse).distinct()
                names.forEach { name -> topics.getOrPut(name) { mutableListOf() }.add(work) }
            }
            TopicTimeBucket(bucket.label, bucket.start, bucket.end, works,
                topics.map { (title, entries) -> TopicTimeGroup(title, entries.distinctBy { it.workIdentity }) }
                    .sortedWith(compareByDescending<TopicTimeGroup> { it.works.size }.thenBy { it.title }))
        }
}
