package com.emerge.app.analysis

/** Source relationships asserted by retained text, not external verification of that text. */
data class EvidenceSource(val producer: String, val material: String, val kind: String,
    val basis: String, val upstream: List<String> = emptyList()) {
    fun validFor(evidence: String, context: String = evidence): Boolean {
        if (basis.isBlank() || basis.length > 500 || !evidence.contains(basis)) return false
        fun anchor(value: String) = value.isNotBlank() && value.length <= 80 && basis.contains(value)
        if (!anchor(producer) || !anchor(material) || kind !in setOf("primary", "secondary", "unknown") ||
            upstream.size > 4 || upstream.distinct().size != upstream.size || !upstream.all(::anchor)) return false
        if (kind == "secondary" && upstream.isEmpty()) return false
        return kind != "primary" || upstream.isEmpty() && !unresolved.containsMatchIn(context) &&
            !anonymous.containsMatchIn(producer) &&
            Regex(Regex.escape(producer) + "[\\s，,：:]*(?:已|曾)?(?:开展|进行|完成)?(?:$primaryCollection)").containsMatchIn(basis)
    }

    private companion object {
        const val primaryCollection = "(?:独立|自行|自主)(?:开展|进行|完成)?(?:调查|采样|采集|采访|测量|实验|实测|统计)|一手采访|现场实测"
        val unresolved = Regex("不|未|没有|并非|否认|据说|网传|声称|自称|可能|如果|假如|假设|计划|拟|将|预计|转引|转载|引用|援引|基于")
        val anonymous = Regex("^(?:我|我们|本人|作者|博主|记者|本机构|本团队|该机构|该团队)$|^(?:某|匿名|一位|一名)")
    }
}

data class SourceProvenance(val complete: Boolean, val sources: List<EvidenceSource>) {
    fun validFor(evidence: String, input: String = evidence): Boolean {
        if (sources.size > 4 || complete && sources.isEmpty() ||
            sources.distinctBy { it.producer to it.material }.size != sources.size) return false
        val context = provenanceContext(evidence, input)
        return sources.all { it.validFor(evidence, context) }
    }
}

private fun provenanceContext(evidence: String, input: String): String {
    if (evidence.isEmpty()) return evidence
    val boundaries = charArrayOf('。', '！', '？', '；', ';', '\n')
    val contexts = mutableListOf<String>()
    var offset = 0
    while (offset < input.length) {
        val start = input.indexOf(evidence, offset)
        if (start < 0) break
        val end = start + evidence.length
        val left = if (start == 0) 0 else input.lastIndexOfAny(boundaries, start - 1) + 1
        val right = input.indexOfAny(boundaries, end).takeIf { it >= 0 } ?: input.length
        contexts += input.substring(left, right)
        offset = end
    }
    return contexts.joinToString("\n").ifEmpty { evidence }
}
