package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import java.security.MessageDigest

/** Presentation/statistics identity only; retained visits and their source text stay intact. */
object BrowseWorkKey {
    /** Preserve a known adjacent visit identity even when its retained snapshots grew. */
    fun resolve(events: List<BrowseEvent>): List<String> {
        val keys = events.mapIndexed { index, event -> of(event, "${event.id.takeIf { it > 0 } ?: index}:${event.timestamp}") }
        val parents = mutableMapOf<String, String>()
        fun root(key: String): String {
            var current = key
            while (parents[current] != null) current = parents.getValue(current)
            return current
        }
        val byWork = mutableMapOf<List<String>, String>()
        events.forEachIndexed { index, event ->
            event.workKey?.takeIf { it.isNotBlank() }?.let { work ->
                val identity = listOf(event.packageName, event.sourceType, work)
                val earlier = byWork.getOrPut(identity) { keys[index] }
                val first = root(earlier)
                val next = root(keys[index])
                if (first != next) parents[maxOf(first, next)] = minOf(first, next)
            }
        }
        return keys.map(::root)
    }

    fun of(event: BrowseEvent, fallback: String = "${event.id}:${event.timestamp}"): String {
        val key = event.workKey?.takeIf { it.isNotBlank() }
        val anonymous = event.identityMethod == "session_estimate" || key == null || key.startsWith("visit:")
        val text = normalize(event.visibleText)
        val identity = if (anonymous && event.visibleText.lines().any { line -> line.count(Char::isLetterOrDigit) >= 16 }) {
            // Compare the entire snapshot, not a repeated quote, UI label or fuzzy sentence match.
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            "snapshot:$digest"
        } else key ?: "record:$fallback"
        return "${event.packageName}|${event.sourceType}|$identity"
    }

    internal fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")
}
