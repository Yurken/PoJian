package com.emerge.app.analysis

import java.util.Locale

data class EvidenceMetric(val known: Int, val unknown: Int, val total: Int) {
    val display: String get() = when {
        total == 0 -> "—"
        known == 0 && unknown > 0 -> "未核实"
        unknown > 0 -> "$known+"
        else -> known.toString()
    }
}

data class SourceRecord(val eventId: Long, val evidence: String, val provenance: SourceProvenance?)

fun CognitiveReport.independentSources(): EvidenceMetric = independentSourceMetric(claims.map { claim ->
    claim.sourceRecord?.takeIf { !claim.uncertainAttribution } ?: SourceRecord(claim.eventId, "", null)
})

/** Count retained FACT fragments compared with history; FIRST and changed scope remain unassessed. */
fun newFactMetric(claims: List<ClaimObservation>): EvidenceMetric {
    data class Key(val content: String, val actors: List<String>, val origins: List<String>, val scope: String)
    val facts = claims.flatMap { claim -> claim.evidenceDetails.filter { it.type == EvidenceType.FACT }.map { claim to it } }
        .groupBy { (_, fragment) -> Key(EvidenceTextCanonicalizer.canonicalize(fragment.comparisonText).trim(),
            fragment.identity.actors.map(::sourceKey).sorted(), fragment.identity.origins.map(::sourceKey).sorted(), fragment.relativeTimeScope) }
    var known = 0
    var unknown = claims.count { it.evidenceDetails.isEmpty() }
    facts.values.forEach { versions ->
        val assessable = versions.filter { (claim, fragment) -> !claim.uncertainAttribution &&
            !fragment.identityUnresolved && !fragment.identity.unresolvedCase && !fragment.identity.unresolvedReferences &&
            !claim.historyComparisonTruncated }
        when {
            assessable.any { (claim, fragment) -> claim.increment == Increment.NEW && fragment.isNew && fragment.historyMatches.isEmpty() } -> known++
            assessable.size == versions.size && assessable.all { (_, fragment) -> fragment.historyMatches.isNotEmpty() } -> Unit
            else -> unknown++
        }
    }
    return EvidenceMetric(known, unknown, claims.size)
}

private fun sourceKey(value: String) = LiteralWhitespace.collapse(value.trim().removeSurrounding("《", "》")).lowercase(Locale.ROOT)

/** Resolve quoted material dependencies, then merge roots with a shared original producer. */
fun independentSourceMetric(records: List<SourceRecord>): EvidenceMetric {
    val valid = records.map { record -> record.provenance?.takeIf { it.validFor(record.evidence) } }
    val materials = valid.filterNotNull().flatMap { it.sources }.groupBy { sourceKey(it.material) }
    val roots = materials.filterValues { rows -> rows.all { it.kind == "primary" } }.keys
    val resolved = roots.toMutableSet()
    val waiting = mutableMapOf<String, Int>()
    val dependants = mutableMapOf<String, MutableList<String>>()
    materials.forEach { (material, rows) ->
        val dependencies = rows.filter { it.kind == "secondary" }.flatMap { it.upstream }.map(::sourceKey).toSet()
        if (dependencies.isNotEmpty() && rows.all { it.kind == "secondary" }) {
            waiting[material] = dependencies.size
            dependencies.forEach { dependants.getOrPut(it) { mutableListOf() } += material }
        }
    }
    // Each edge is visited once. Missing parents, conflicting declarations and cycles stay unresolved.
    val ready = ArrayDeque(roots)
    while (ready.isNotEmpty()) {
        dependants[ready.removeFirst()].orEmpty().forEach { material ->
            val left = waiting.getValue(material) - 1
            waiting[material] = left
            if (left == 0 && resolved.add(material)) ready.addLast(material)
        }
    }
    val unknown = valid.count { provenance -> provenance == null || !provenance.complete ||
        provenance.sources.isEmpty() || provenance.sources.any { sourceKey(it.material) !in resolved } }
    // One producer can publish several reports; co-produced material also joins those producers.
    val parent = roots.associateWith { it }.toMutableMap()
    fun root(value: String): String {
        var current = value
        while (parent.getValue(current) != current) current = parent.getValue(current)
        var node = value
        while (parent.getValue(node) != node) { val next = parent.getValue(node); parent[node] = current; node = next }
        return current
    }
    val producers = mutableMapOf<String, String>()
    roots.forEach { material -> materials.getValue(material).forEach { source ->
        val producer = sourceKey(source.producer)
        producers.putIfAbsent(producer, material)?.let { previous -> parent[root(material)] = root(previous) }
    } }
    return EvidenceMetric(roots.map(::root).distinct().size, unknown, records.size)
}
