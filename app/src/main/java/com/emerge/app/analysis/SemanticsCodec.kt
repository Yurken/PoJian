package com.emerge.app.analysis

import com.emerge.app.data.BrowseEvent
import com.emerge.app.data.BrowseSemantic
import org.json.JSONArray
import org.json.JSONObject

/** Strict storage codec: a corrupt row is ignored as a whole, never partly accepted. */
object SemanticsCodec {
    fun encode(semantics: EventSemantics): BrowseSemantic {
        val claims = JSONArray().apply {
            semantics.claims.forEach { claim -> put(JSONObject().apply {
                put("quote", claim.quote)
                put("topic", claim.topic)
                put("subject", claim.subject)
                put("dimension", claim.dimension)
                put("stance", claim.stance)
                put("evidence", claim.evidence)
                put("conditions", claim.conditions)
                put("uncertain", claim.uncertain)
                put("evidence_actors", JSONArray(claim.evidenceActors))
                put("evidence_origins", JSONArray(claim.evidenceOrigins))
                claim.presentation?.let { put("presentation", ClaimPresentationCodec.encode(it)) }
                claim.sourceProvenance?.let { put("source_provenance", SourceProvenanceCodec.encode(it)) }
            }) }
        }
        return BrowseSemantic(semantics.eventId, semantics.inputText, semantics.analyzedAt,
            claims.toString(), semantics.inputKind)
    }

    fun decode(row: BrowseSemantic): EventSemantics? = runCatching {
        require(row.claimsJson.length <= 131_072)
        val json = JSONArray(row.claimsJson)
        require(json.length() <= 3)
        val claims = (0 until json.length()).map { index ->
            val claim = json.getJSONObject(index)
            fun string(name: String): String = claim.get(name) as? String
                ?: error("Invalid semantic field")
            fun anchors(name: String): List<String> {
                if (!claim.has(name)) return emptyList()
                val values = claim.get(name) as? JSONArray ?: error("Invalid evidence identity field")
                require(values.length() <= 4)
                return (0 until values.length()).map { item -> values.get(item) as? String
                    ?: error("Invalid evidence identity anchor") }
            }
            ArgumentUnit(quote = string("quote"), topic = string("topic"), subject = string("subject"),
                dimension = string("dimension"), stance = string("stance"), evidence = string("evidence"),
                conditions = string("conditions"), uncertain = claim.get("uncertain") as? Boolean
                    ?: error("Invalid uncertainty flag"), evidenceActors = anchors("evidence_actors"),
                evidenceOrigins = anchors("evidence_origins"),
                presentation = if (claim.has("presentation")) ClaimPresentationCodec.decode(claim.getJSONObject("presentation")) else null,
                sourceProvenance = if (claim.has("source_provenance")) SourceProvenanceCodec.decode(claim.getJSONObject("source_provenance")) else null)
        }
        EventSemantics(row.eventId, row.inputText, row.analyzedAt, claims, row.inputKind).also { result ->
            // Validate stored shape and verbatim evidence against the saved input. The consumer
            // must still call validFor with the current event to reject later text revisions.
            require(result.validFor(BrowseEvent(id = row.eventId, packageName = "", timestamp = 0,
                eventType = 0, visibleText = row.inputText)))
        }
    }.getOrNull()
}
