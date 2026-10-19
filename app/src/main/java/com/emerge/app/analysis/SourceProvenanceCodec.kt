package com.emerge.app.analysis

import org.json.JSONArray
import org.json.JSONObject

object SourceProvenanceCodec {
    fun encode(value: SourceProvenance): JSONObject = JSONObject().put("complete", value.complete)
        .put("sources", JSONArray().apply { value.sources.forEach { source -> put(JSONObject()
            .put("producer", source.producer).put("material", source.material).put("kind", source.kind)
            .put("basis", source.basis).put("upstream", JSONArray(source.upstream))) } })

    fun decode(value: JSONObject): SourceProvenance {
        val complete = value.get("complete") as? Boolean ?: error("Invalid source completeness")
        val sources = value.get("sources") as? JSONArray ?: error("Invalid source provenance")
        require(sources.length() <= 4)
        return SourceProvenance(complete, (0 until sources.length()).map { index ->
            val source = sources.getJSONObject(index)
            fun string(key: String) = source.get(key) as? String ?: error("Invalid source anchor")
            val upstream = source.get("upstream") as? JSONArray ?: error("Invalid upstream sources")
            require(upstream.length() <= 4)
            EvidenceSource(string("producer"), string("material"), string("kind"), string("basis"),
                (0 until upstream.length()).map { upstream.get(it) as? String ?: error("Invalid upstream source") })
        })
    }
}
