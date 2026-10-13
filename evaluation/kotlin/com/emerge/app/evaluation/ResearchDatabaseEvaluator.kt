package com.emerge.app.evaluation

import com.emerge.app.analysis.CognitiveAnalyzer
import com.emerge.app.analysis.ViewpointTimeline
import com.emerge.app.data.ResearchProjection
import com.emerge.app.ui.StatisticsPeriod
import com.emerge.app.ui.calculateStatistics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZoneId

/** Independent Python SQLite windows compared with the real Kotlin projectors and aggregators. */
object ResearchDatabaseEvaluator {
    @JvmStatic fun main(args: Array<String>) {
        val folder = File(args[0])
        val zone = ZoneId.of("Asia/Shanghai")
        var profiles = 0; var windows = 0; var contacts = 0; var reportedExposure = 0
        val cohorts = JSONObject()
        val start = System.nanoTime()
        File(folder, "projections.jsonl").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val row = JSONObject(line)
                val profile = row.getJSONObject("profile")
                val data = ResearchProjection.decode(profile.getString("id"), profile.getLong("now"),
                    row.getJSONArray("contacts").objects(), row.getJSONArray("emotions").objects())
                profiles++; contacts += data.events.size
                check(data.semantics.size == data.events.size && data.samples.size == data.events.size)
                row.getJSONArray("expected").objects().forEach { expected ->
                    val now = expected.getLong("now")
                    val period = StatisticsPeriod.valueOf(expected.getString("period"))
                    val stats = calculateStatistics(data.events, period, now, zone)
                    val description = "profile=${profile.getInt("ordinal")} ${expected.getInt("phase")} $period"
                    check(stats.total == expected.getInt("works")) { "$description work count ${stats.total}" }
                    check(stats.visitCount == expected.getInt("visits")) { "$description visit count ${stats.visitCount}" }
                    check(stats.observedDurationMillis == expected.getLong("duration")) { "$description duration ${stats.observedDurationMillis}" }
                    check(stats.unidentifiedWorks == 0 && stats.unknownDurationRecords == 0)
                    val timeline = ViewpointTimeline.build(data.events, period, now, zone, semantics = data.semantics)
                    check(ViewpointTimeline.details(timeline.flatMap { it.items }).size == stats.total) { "$description timeline mismatch" }
                    val anxiety = data.emotions.filter { it.timestamp in expected.getLong("start") until now &&
                        it.expression == "焦虑" && it.attribution in setOf("affirmed", "reported") }.distinctBy { it.workKey }
                    check(anxiety.size == expected.getInt("anxiety_works") && anxiety.size <= stats.total)
                    windows++
                }
                // Actual App report; preset experimental conditions never override the analyzer.
                val report = CognitiveAnalyzer().analyze(data.events, StatisticsPeriod.WEEK, data.now, zone, semantics = data.semantics)
                if (report.exposure?.primary != null) reportedExposure++
                val cohort = "${profile.getString("concentration")}/${profile.getString("anxiety")}"
                cohorts.put(cohort, cohorts.optInt(cohort) + 1)
            }
        }
        check(profiles == 200 && windows == 1200 && contacts == 33360)
        val result = JSONObject().put("status", "passed").put("profiles", profiles).put("windows", windows)
            .put("contacts", contacts).put("work_count_errors", 0).put("visit_count_errors", 0)
            .put("duration_max_error_ms", 0).put("timeline_count_errors", 0).put("anxiety_count_errors", 0)
            .put("cohorts", cohorts).put("reports_with_primary_exposure", reportedExposure)
            .put("runtime", "JVM; supplied authored semantics; no Android device or model execution")
            .put("elapsed_seconds", (System.nanoTime() - start) / 1_000_000_000.0)
        File(folder, "app-projection-results.json").writeText(result.toString(2) + "\n")
        println(result.toString(2))
    }
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
}
