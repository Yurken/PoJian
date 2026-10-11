package com.emerge.app.analysis

import java.math.BigDecimal

/**
 * A finite comparison-key normalizer; never use its output as retained or displayed evidence.
 *
 * Recognizes well-formed Chinese/Arabic quantities followed by a listed measurement unit,
 * percentages, and a narrow calendar-scoped, uncompleted recruitment-capacity construction.
 * Predicate order, units, signs, inequalities, negation, completed hiring and offer/application
 * counts are retained. Elliptic numbers ("一百二"), ranges and unfamiliar syntax are not inferred.
 * Quoted titles, recognized source spans, personal-record names and organization names are
 * protected. This cannot resolve arbitrary entities, aliases, quotation scopes or recruitment
 * intent: callers must still compare separately extracted identities and other semantic scopes.
 */
object EvidenceTextCanonicalizer {
    private const val numeral = "(?:[0-9０-９]+(?:[.．][0-9０-９]+)?|[零〇一二两三四五六七八九十百千壹贰叁肆伍陆柒捌玖拾佰仟万萬亿億]+(?:[点點][零〇一二三四五六七八九壹贰叁肆伍陆柒捌玖]+)?)"
    private const val sign = "(?:[+＋\\-−－]|负|負|正)?"
    private const val scale = "[万萬亿億]?"
    private const val unit = "(?:个百分点|个招聘名额|招聘名额|千米|公里|厘米|毫米|公斤|千克|毫升|小时|分钟|人|名|个|份|件|封|次|家|所|台|元|天|秒|米|克|吨|升|倍|[%％])"
    private val quantity = Regex("($sign)($numeral)($scale)($unit)")
    private val percentage = Regex("($sign)百分之($numeral)($scale)")
    private val numericCharacter = Regex("[0-9０-９零〇一二两三四五六七八九十百千壹贰叁肆伍陆柒捌玖拾佰仟万萬亿億点點.．]")
    private val rangeBefore = Regex("$numeral\\s*(?:[至到～~—–/、±\\-])\\s*$")
    private val identifierBefore = Regex("(?:编号|代号|型号|版本|ID|id)\\s*[:：]?\\s*$")
    private val protected = listOf(
        Regex("《[^《》\\n]*》|「[^「」\\n]*」|『[^『』\\n]*』|“[^“”\\n]*”|‘[^‘’\\n]*’|\"[^\"\\n]*\""),
        Regex("(?:根据|依据|来自|据)([^\\s，,。；;：:\\n]{1,40}?)的(?:本人|个人|亲身)(?:记录|自述|经历)"),
        Regex("(?:^|[，,；;：:\\s])([^\\s，,。；;：:\\n]{1,40}?)的(?:本人|个人|亲身)(?:记录|自述|经历)"),
        Regex("((?:报告|调查|研究|统计资料)[^\\s，,。；;：:\\n]{1,40}?)(?:显示|记录|指出|表明|公布|提供|说明|预测)"),
        Regex("(?:根据|依据|来自|据)([^\\s，,。；;：:\\n]{1,40}?)(?=显示|记录|指出|表明|公布|说明|预测|[，,；;：:]|$)"),
        Regex("[^\\s，,。；;：:\\n]{1,40}?(?:公司|集团|企业|大学|医院|研究院|研究所|协会|基金会)"),
        Regex("(?:求职者|受访者|患者|学生|居民|工程师|设计师)[^\\s，,。；;：:\\n]{1,40}?(?=的|在|于|称|说|指出|表示|记录|投递|参加|就诊|经历)")
    )
    private val calendarRecruitment = Regex("(本月|当月|本季度|今年|本年度|[0-9]{4}年[0-9]{1,2}月)\\s*(?:提供([0-9]+)(?:个)?招聘名额|仅招聘([0-9]+)人|只能招(?:聘)?([0-9]+)人)")
    private val unsafeRecruitmentContext = Regex("已|实际|最终|累计|成功|完成|录用|入职|应聘|申请|不|未|没有|否认|如果|假如|假设|是否|可能|预计")
    private val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '壹' to 1, '二' to 2, '贰' to 2,
        '两' to 2, '三' to 3, '叁' to 3, '四' to 4, '肆' to 4, '五' to 5, '伍' to 5,
        '六' to 6, '陆' to 6, '七' to 7, '柒' to 7, '八' to 8, '捌' to 8, '九' to 9, '玖' to 9)
    private val smallUnits = mapOf('十' to 10L, '拾' to 10L, '百' to 100L, '佰' to 100L, '千' to 1000L, '仟' to 1000L)

    fun canonicalize(text: String): String {
        val ranges = protected.flatMap { expression -> expression.findAll(text).map { match ->
            // Attribute wrappers stay outside the protected identity span whenever a capture exists.
            if (match.groupValues.size > 1) match.groups[1]?.range ?: match.range else match.range
        }.toList() }.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (range in ranges) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
            else merged += range
        }
        return buildString {
            var offset = 0
            for (range in merged) {
                append(canonicalizePlain(text.substring(offset, range.first)))
                append(text.substring(range.first, range.last + 1))
                offset = range.last + 1
            }
            append(canonicalizePlain(text.substring(offset)))
        }
    }

    private fun canonicalizePlain(text: String): String {
        val percents = percentage.replace(text) { match ->
            if (!safeBoundary(text, match.range) ||
                text.substring(match.range.last + 1).firstOrNull()?.let { it in "人名个份件封家台元米克吨升倍" } == true) match.value
            else amount(match.groupValues[1], match.groupValues[2], match.groupValues[3])?.let { "$it%" } ?: match.value
        }
        val quantities = quantity.replace(percents) { match ->
            if (!safeBoundary(percents, match.range)) match.value
            else amount(match.groupValues[1], match.groupValues[2], match.groupValues[3])?.let {
                it + match.groupValues[4].replace('％', '%')
            } ?: match.value
        }.replace("这个月", "本月")
        return calendarRecruitment.replace(quantities) { match ->
            val clauseStart = quantities.lastIndexOfAny(charArrayOf('，', ',', '。', '；', ';', '\n'), match.range.first - 1) + 1
            val context = quantities.substring(clauseStart, match.range.first)
            val count = match.groupValues.drop(2).first(String::isNotEmpty)
            if (unsafeRecruitmentContext.containsMatchIn(context)) match.value
            else "${match.groupValues[1]}招聘名额${count}人"
        }
    }

    private fun safeBoundary(text: String, range: IntRange): Boolean {
        val before = text.substring(0, range.first)
        val previous = before.lastOrNull()
        if (previous != null && (previous in 'a'..'z' || previous in 'A'..'Z' || previous == '_' || numericCharacter.matches(previous.toString()))) return false
        if (rangeBefore.containsMatchIn(before.takeLast(64)) || identifierBefore.containsMatchIn(before.takeLast(32))) return false
        val after = text.getOrNull(range.last + 1)
        return after == null || !numericCharacter.matches(after.toString())
    }

    private fun amount(signText: String, numeralText: String, scaleText: String): String? {
        if (numeralText.length > 48) return null
        val normalized = numeralText.map { if (it in '０'..'９') '0' + (it - '０') else it }.joinToString("").replace('．', '.')
        val value = if (normalized.firstOrNull()?.isDigit() == true) normalized.toBigDecimalOrNull()
        else chineseNumber(normalized)
        if (value == null || value.precision() > 24 || value.scale() > 12) return null
        val multiplier = when (scaleText) { "万", "萬" -> 10_000L; "亿", "億" -> 100_000_000L; else -> 1L }
        val number = value.multiply(BigDecimal.valueOf(multiplier)).stripTrailingZeros().toPlainString()
        val prefix = when (signText) { "-", "−", "－", "负", "負" -> "-"; "+", "＋", "正" -> "+"; else -> "" }
        return prefix + number
    }

    private fun chineseNumber(text: String): BigDecimal? {
        val parts = text.replace('點', '点').split('点')
        if (parts.size > 2) return null
        val integer = chineseInteger(parts[0]) ?: return null
        if (parts.size == 1) return BigDecimal.valueOf(integer)
        // A decimal before a scale is supported; "一万点五" is deliberately not interpreted.
        if (parts[0].any { it in "万萬亿億" } || parts[1].isEmpty() || parts[1].length > 12 || parts[1].contains('两')) return null
        val fraction = parts[1].map { digits[it] ?: return null }.joinToString("")
        return "$integer.$fraction".toBigDecimalOrNull()
    }

    private fun chineseInteger(text: String): Long? {
        val normalized = text.replace('萬', '万').replace('億', '亿')
        if (normalized.count { it == '亿' } > 1) return null
        if ('亿' !in normalized) return wanNumber(normalized)
        val parts = normalized.split('亿')
        val high = wanNumber(parts[0])?.takeIf { it in 1..99_999_999L } ?: return null
        val low = lowerSection(parts[1], 100_000_000L, ::wanNumber) ?: return null
        return high * 100_000_000L + low
    }

    private fun wanNumber(text: String): Long? {
        if (text.count { it == '万' } > 1 || text.contains('亿')) return null
        if ('万' !in text) return smallNumber(text)
        val parts = text.split('万')
        val high = smallNumber(parts[0])?.takeIf { it in 1..9999L } ?: return null
        val low = lowerSection(parts[1], 10_000L, ::smallNumber) ?: return null
        return high * 10_000L + low
    }

    private fun lowerSection(text: String, upperUnit: Long, parse: (String) -> Long?): Long? {
        if (text.isEmpty()) return 0
        val hasZero = text.first() == '零' || text.first() == '〇'
        val value = parse(if (hasZero) text.drop(1) else text)?.takeIf { it in 1 until upperUnit } ?: return null
        // Missing lower-order digits require an explicit zero; otherwise "一万二" is ambiguous.
        if ((value < upperUnit / 10) != hasZero) return null
        return value
    }

    private fun smallNumber(text: String): Long? {
        if (text.length == 1 && text[0] in digits) return digits[text[0]]?.toLong()
        if (text.isEmpty()) return null
        var total = 0L
        var pending: Int? = null
        var lastUnit = 10_000L
        var gap = false
        var position = 0
        for (char in text) {
            val digit = digits[char]
            if (digit != null) {
                if (digit == 0) {
                    if (position == 0 || pending != null || gap) return null
                    gap = true
                } else {
                    if (pending != null) return null
                    pending = digit
                }
            } else {
                val current = smallUnits[char] ?: return null
                val value = pending ?: if (position == 0 && current == 10L) 1 else return null
                if (current >= lastUnit || (lastUnit != 10_000L && (current < lastUnit / 10) != gap)) return null
                total += value * current
                lastUnit = current
                pending = null
                gap = false
            }
            position++
        }
        if (pending != null) {
            if (lastUnit != 10_000L && (lastUnit > 10L) != gap) return null
            total += pending
        } else if (gap) return null
        return total.takeIf { it in 0..9999L }
    }
}
