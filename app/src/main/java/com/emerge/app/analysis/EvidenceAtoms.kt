package com.emerge.app.analysis

import java.time.ZoneId

/** A continuous retained quote and its comparison payload; inherited references stay attached to the atom. */
data class EvidenceAtom(val text: String, val comparisonText: String, val identity: EvidenceIdentity,
    val strictPayload: Boolean = false, val relativeTimeScope: String = "")

/**
 * Bounded literal coverage, not learned entailment. Only independent numerical clauses and explicit
 * material-attributed metric enumerations are split. Unresolved scope and unsupported grammar stay whole.
 */
object EvidenceAtoms {
    private const val LIMIT = 8
    private const val materialName = "(?:报告|调查|研究|统计资料)(?:(?!显示|记录|指出|表明|说明|列出|公布|提供|预测)[\\p{L}\\p{N}·_-]){1,20}?"
    private val sourcePrefix = Regex("^(?:依据|根据|据)?\\s*(《[^《》\\n]{1,78}》|$materialName)(?:显示|记录|指出|表明|说明|列出|公布|提供)\\s*")
    private val directedSource = Regex("^(?:依据|根据|据)\\s*(《[^《》\\n]{1,78}》|$materialName)[，,]\\s*")
    private fun prefix(text: String) = sourcePrefix.find(text) ?: directedSource.find(text)
    private fun sourceKey(text: String) = text.trim().removeSurrounding("《", "》")
    private val unsafeScope = Regex("如果|假如|假设|若|一旦|即使|即便|虽然|尽管|除非|否则|只有|只要|不|没|未|否认|以下|如下|条件|情况下|仅在|前提|但|然而|而是|或者|或是|(?:^|[，,；;])或|因为|由于|因此|所以|(?<!同)比|分别|其中|其余|剩余|这些|上述|该|同时|随后|先后|[：:《》“”\"'（）()\\[\\]]")
    private val quantity = Regex("[-+−<>≤≥]?[0-9零〇一二两三四五六七八九十百千万亿点]+(?:\\.[0-9]+)?(?:[%％]|人|个|份|封|元|厘米|米|瓦|吨|例|度)")
    private val metric = Regex("岗位|人数|名额|预算|数量|比例|录用率|透明度|温度|收入|销量|病例|能耗|排放|投递|收到|录取|录用|提供|招聘")
    private val timeMarker = Regex("今年|去年|前年|明年|后年|本年|本年度|本月|这个月|当月|上月|下月|本季度|当前季度|这个季度|上季度|下季度|[0-9]{4}年|[0-9]{1,2}月")
    private val scopedPlace = Regex("(?:在|向|位于|居住于)(?:北京|上海|深圳|广州|天津|重庆|杭州|成都|南京|武汉|西安|苏州|[\\p{L}]{2,12}?(?:市|省|县|地区))|地区为|城市为|城市[:：]")
    private val enumeration = Regex("^([^\\s，,；;]{1,40}?)(招聘岗位|岗位数量|岗位数|报名人数|录用人数|培训预算|销售额|销量|透明度|温度|能耗|排放量)(?:为|达到|达|有)?([-+−<>≤≥]?[0-9零〇一二两三四五六七八九十百千万亿点]+(?:\\.[0-9]+)?(?:[%％]|人|个|元|厘米|米|瓦|吨|例|度))$")

    fun split(text: String, identity: EvidenceIdentity = EvidenceIdentityComparator.identity(text),
        capturedAt: Long? = null, zone: ZoneId = ZoneId.systemDefault()): List<EvidenceAtom> {
        fun scoped(atoms: List<EvidenceAtom>) = if (capturedAt == null) atoms else atoms.map { item ->
            val content = (item.identity.actors + item.identity.origins).fold(item.comparisonText) { value, anchor -> value.replace(anchor, " ") }
            item.copy(relativeTimeScope = SemanticFieldKeys.relativeScope(content, capturedAt, zone))
        }
        fun whole(unresolved: Boolean = false) = scoped(listOf(atom(text, identity.copy(
            unresolvedReferences = identity.unresolvedReferences || unresolved))))
        if (identity.unresolvedReferences || identity.unresolvedCase) return whole()
        // A known title is metadata rather than quoted speech; other quotation/bracket scopes stay indivisible.
        val scopeText = identity.origins.fold(text) { value, origin ->
            value.replace("《${sourceKey(origin)}》", " ").replace(origin, " ") }
        if (unsafeScope.containsMatchIn(scopeText)) return whole()
        if (text.contains(';') || text.contains('；')) {
            val pieces = text.split(Regex("[；;]")).map(String::trim)
            if (pieces.size > LIMIT) return whole(unresolved = true)
            if (pieces.size < 2 || pieces.any { it.isEmpty() }) return whole()
            val atoms = pieces.map { local -> atom(local, localIdentity(local, identity)) }
            if (atoms.any { !independent(it.comparisonText) } || !referencesAssigned(atoms, identity) ||
                !independentScopes(atoms.map { it.comparisonText })) return whole()
            return scoped(atoms.map { it.copy(strictPayload = true) })
        }
        if (!text.contains(',') && !text.contains('，')) return whole()
        val prefix = prefix(text) ?: return whole()
        val source = prefix.groupValues[1]
        if (identity.actors.isNotEmpty() || identity.origins.size != 1 || sourceKey(identity.origins.single()) != sourceKey(source)) return whole()
        val pieces = text.substring(prefix.range.last + 1).split(Regex("[，,]")).map(String::trim)
        if (pieces.size > LIMIT) return whole(unresolved = true)
        if (pieces.size < 2 || pieces.any { it.isEmpty() }) return whole()
        val parsed = pieces.map { enumeration.matchEntire(it) ?: return whole() }
        // Each explicit subject carries the same metric; units and quantities remain bound to its own clause.
        if (parsed.map { it.groupValues[2] }.distinct().size != 1 || !independentScopes(pieces)) return whole()
        return scoped(pieces.mapIndexed { index, value ->
            val retained = if (index == 0) text.substring(0, prefix.range.last + 1) + value else value
            EvidenceAtom(retained, value, EvidenceIdentity(origins = identity.origins), true)
        })
    }

    private fun independent(value: String) = value.length >= 4 && quantity.containsMatchIn(value) && metric.containsMatchIn(value) &&
        !value.startsWith("并") && !value.startsWith("而") && !value.startsWith("且")

    private fun independentScopes(pieces: List<String>) = listOf(timeMarker, scopedPlace).all { marker ->
        val present = pieces.map(marker::containsMatchIn)
        present.none { it } || present.all { it }
    }

    private fun localIdentity(text: String, original: EvidenceIdentity) = EvidenceIdentityComparator.identity(text,
        original.actors.filter(text::contains), original.origins.filter(text::contains))

    private fun referencesAssigned(atoms: List<EvidenceAtom>, original: EvidenceIdentity): Boolean =
        atoms.none { it.identity.unresolvedCase || it.identity.unresolvedReferences } &&
            (original.actors.isEmpty() || atoms.all { it.identity.actors.isNotEmpty() }) &&
            (original.origins.isEmpty() || atoms.all { it.identity.origins.isNotEmpty() })

    private fun atom(text: String, identity: EvidenceIdentity): EvidenceAtom {
        val prefix = prefix(text)
        val payload = if (prefix != null && identity.origins.any { sourceKey(it) == sourceKey(prefix.groupValues[1]) })
            text.substring(prefix.range.last + 1).trim().ifEmpty { text } else text
        return EvidenceAtom(text, payload, identity)
    }

    fun compare(current: EvidenceAtom, previous: EvidenceAtom): EvidenceRelation {
        if (current.identity.unresolvedReferences || previous.identity.unresolvedReferences) return EvidenceRelation.UNRESOLVED
        if (current.relativeTimeScope != previous.relativeTimeScope)
            return if (current.relativeTimeScope.isEmpty() || previous.relativeTimeScope.isEmpty())
                EvidenceRelation.UNRESOLVED else EvidenceRelation.CHANGED
        val relation = EvidenceIdentityComparator.compare(current.comparisonText, previous.comparisonText,
            current.identity, previous.identity)
        if (relation != EvidenceRelation.REUSED || !current.strictPayload && !previous.strictPayload) return relation
        // An atom cannot borrow a neighbour's number, city or metric through approximate string similarity.
        fun key(text: String) = EvidenceTextCanonicalizer.canonicalize(text).lowercase().replace(Regex("[^\\p{L}\\p{N}%％+−<>≤≥-]"), "")
        return if (key(current.comparisonText) == key(previous.comparisonText)) relation else EvidenceRelation.CHANGED
    }
}
