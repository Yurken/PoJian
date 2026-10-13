package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent

/** One identity source for every window, platform, chart and work card. Text is not a work ID. */
object BrowseWorkKey {
    fun resolve(events: List<BrowseEvent>): List<String> {
        val parents = events.filter { it.sourceType == "accessibility" }.associateBy { it.id }
        return events.mapIndexed { index, event ->
            val parent = event.parentEventId?.let(parents::get)?.takeIf { it.packageName == event.packageName }
            of(parent ?: event, "${event.id.takeIf { it > 0 } ?: index}:${event.timestamp}")
        }
    }

    fun of(event: BrowseEvent, fallback: String = "${event.id}:${event.timestamp}"): String {
        val key = event.workKey?.takeIf(String::isNotBlank) ?: "record:$fallback"
        return "${event.packageName}|${if(event.sourceType == "accessibility" || event.parentEventId != null) "work" else event.sourceType}|$key"
    }

    internal fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")
}
