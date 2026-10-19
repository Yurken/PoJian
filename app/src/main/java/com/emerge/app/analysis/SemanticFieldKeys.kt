package com.emerge.app.analysis

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Finite comparison keys for already extracted fields, not a general paraphrase model.
 * Unknown wording, negation, modality, population, place and connectors remain literal.
 * Relative calendar periods refer to the observation's time and zone, not analysis time.
 */
object SemanticFieldKeys {
    private val demandDimensions = setOf("岗位数量", "招聘岗位数量", "招聘数量", "岗位需求", "招聘需求",
        "招聘需求量", "岗位需求量")
    private val demandSubjects = Regex("^.*(?:招聘需求|招聘岗位)$")
    private val demandStance = Regex("^(岗位需求|招聘需求)(下降|减少|上升|增加|增长)$")
    private val connectors = Regex("\\s+|[，,；;、]|并且|以及|或者|且|或|和|及|与")

    private data class RelativePeriod(val unit: Char, val offset: Long)
    private val relativePeriods = mapOf(
        "今年" to RelativePeriod('Y', 0), "本年" to RelativePeriod('Y', 0),
        "当前年份" to RelativePeriod('Y', 0), "去年" to RelativePeriod('Y', -1),
        "上一年" to RelativePeriod('Y', -1), "上年" to RelativePeriod('Y', -1),
        "明年" to RelativePeriod('Y', 1), "下一年" to RelativePeriod('Y', 1),
        "下年" to RelativePeriod('Y', 1),
        "本月" to RelativePeriod('M', 0), "这个月" to RelativePeriod('M', 0),
        "当前月份" to RelativePeriod('M', 0), "上月" to RelativePeriod('M', -1),
        "上个月" to RelativePeriod('M', -1), "下月" to RelativePeriod('M', 1),
        "下个月" to RelativePeriod('M', 1),
        "本季度" to RelativePeriod('Q', 0), "当前季度" to RelativePeriod('Q', 0),
        "这个季度" to RelativePeriod('Q', 0), "上季度" to RelativePeriod('Q', -1),
        "上一季度" to RelativePeriod('Q', -1), "下季度" to RelativePeriod('Q', 1),
        "下一季度" to RelativePeriod('Q', 1)
    )
    private val relativeWords = Regex(relativePeriods.keys.sortedByDescending(String::length)
        .joinToString("|") { Regex.escape(it) })

    fun stance(value: String, subject: String, dimension: String): String {
        val text = literal(value)
        val objectKey = literal(subject)
        val dimensionKey = literal(dimension)
        var predicate = text
        if (objectKey.isNotEmpty() && dimensionKey.isNotEmpty()) {
            val demand = demandStance.matchEntire(text)
            if (demand != null && demandDimensions.contains(dimensionKey) && demandSubjects.matches(objectKey)) {
                predicate = demand.groupValues[2]
            }
            predicate = when (predicate) {
                "下降", "减少" -> "quantity-decrease"
                "上升", "增加", "增长" -> "quantity-increase"
                else -> return frame('s', objectKey) + frame('d', dimensionKey) + frame('r', text)
            }
            return frame('s', objectKey) + frame('d', dimensionKey) + frame('p', predicate)
        }
        return frame('s', objectKey) + frame('d', dimensionKey) + frame('r', text)
    }

    fun conditions(value: String, capturedAt: Long, zone: ZoneId): String {
        val text = literal(value)
        if (text.isEmpty()) return frame('r', text)
        val date = Instant.ofEpochMilli(capturedAt).atZone(zone).toLocalDate()
        val key = StringBuilder()
        fun appendPart(part: String) {
            val period = relativePeriods[part]
            if (period != null) {
                key.append(frame('t', calendarPeriod(period, date, zone)))
            } else {
                key.append(frame('r', part))
                // Embedded time words are not paraphrased. Their own period still changes across boundaries.
                relativeWords.findAll(part).forEach { word ->
                    key.append(frame('a', calendarPeriod(relativePeriods.getValue(word.value), date, zone)))
                }
            }
        }
        var cursor = 0
        connectors.findAll(text).forEach { connector ->
            appendPart(text.substring(cursor, connector.range.first))
            key.append(frame('c', connector.value))
            cursor = connector.range.last + 1
        }
        appendPart(text.substring(cursor))
        return key.toString()
    }

    /** Evidence periods are anchored separately from claim conditions; names/titles are masked by the caller. */
    fun relativeScope(value: String, capturedAt: Long, zone: ZoneId): String {
        val date = Instant.ofEpochMilli(capturedAt).atZone(zone).toLocalDate()
        return relativeWords.findAll(value).map { calendarPeriod(relativePeriods.getValue(it.value), date, zone) }
            .toSet().sorted().joinToString("|")
    }

    private fun calendarPeriod(period: RelativePeriod, date: LocalDate, zone: ZoneId): String {
        val currentStart = when (period.unit) {
            'Y' -> date.withDayOfYear(1)
            'Q' -> date.withMonth((date.monthValue - 1) / 3 * 3 + 1).withDayOfMonth(1)
            else -> date.withDayOfMonth(1)
        }
        val start = when (period.unit) {
            'Y' -> currentStart.plusYears(period.offset)
            'Q' -> currentStart.plusMonths(period.offset * 3)
            else -> currentStart.plusMonths(period.offset)
        }
        val end = when (period.unit) {
            'Y' -> start.plusYears(1)
            'Q' -> start.plusMonths(3)
            else -> start.plusMonths(1)
        }
        return "${period.unit}:${start.atStartOfDay(zone).toEpochSecond()}:" +
            end.atStartOfDay(zone).toEpochSecond()
    }

    private fun literal(value: String) = LiteralWhitespace.collapse(value.trim().lowercase(Locale.ROOT))
    private fun frame(type: Char, value: String) = "$type${value.length}:$value"
}
