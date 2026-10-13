package com.emerge.app.evaluation

import com.emerge.app.capture.CaptureSessionTracker
import com.emerge.app.capture.WorkHint
import com.emerge.app.capture.WorkPage
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import com.emerge.app.ui.calculateStatistics
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.time.ZoneId
import kotlin.math.abs

/** Actual capture tracker predictions are frozen before loading the expected boundaries. */
object SyntheticTraceEvaluator {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "dataset output" }
        val input = File(args[0], "inputs/traces.jsonl")
        val output = File(args[1]).apply { mkdirs() }
        val zone = ZoneId.of("Asia/Shanghai")
        val predictions = input.readLines().filter(String::isNotBlank).map { line ->
            val trace = JSONObject(line)
            val tracker = CaptureSessionTracker()
            val rows = linkedMapOf<Long, BrowseEvent>()
            val keys = mutableListOf<String?>()
            val boundaries = mutableListOf<Boolean>()
            var id = 0L
            val start = trace.getLong("started_at")
            val steps = trace.getJSONArray("steps")
            for (index in 0 until steps.length()) {
                val step = steps.getJSONObject(index)
                if (step.getBoolean("reset")) tracker.reset()
                if (step.getBoolean("pause")) tracker.pause()
                val offset = step.getLong("offset_ms")
                val hint = WorkHint(title = step.getString("title"), author = step.getString("author"),
                    nativeId = step.getString("native_id"), page = WorkPage.valueOf(step.getString("page")),
                    truncated = step.getBoolean("truncated"), galleryIndex = step.getInt("gallery_index"),
                    galleryTotal = step.getInt("gallery_total"))
                val sample = tracker.sample(trace.getString("platform"), step.getString("text"), start + offset,
                    1000L + offset, 4096, hint)
                val eventId = if (sample.continuing) sample.event.id else ++id
                rows[eventId] = sample.event.copy(id = eventId)
                tracker.saved(sample, eventId)
                keys += sample.event.workKey
                boundaries += !sample.continuing
            }
            val now = start + steps.getJSONObject(steps.length() - 1).getLong("offset_ms") + 1000
            val stats = calculateStatistics(rows.values.toList(), StatisticsPeriod.DAY, now, zone)
            JSONObject().put("id", trace.getString("id")).put("scenario", trace.getString("scenario"))
                .put("platform", trace.getString("platform")).put("work_keys", JSONArray(keys)).put("new_visit", JSONArray(boundaries))
                .put("distinct_works", stats.total).put("visit_count", stats.visitCount)
                .put("visible_ms", stats.observedDurationMillis)
        }
        File(output, "trace-predictions.jsonl").writeText(predictions.joinToString("\n") { it.toString() } + "\n")
        val gold = File(args[0], "labels/trace_labels.jsonl").readLines().filter(String::isNotBlank)
            .map(::JSONObject).associateBy { it.getString("id") }
        var boundaryErrors = 0
        var boundaries = 0
        var identityPairs = 0
        var identityErrors = 0
        var countErrors = 0
        var visitErrors = 0
        val durationErrors = mutableListOf<Long>()
        for (prediction in predictions) {
            val expected = gold.getValue(prediction.getString("id"))
            val targetSteps = expected.getJSONArray("boundaries")
            val keys = prediction.getJSONArray("work_keys")
            val detected = prediction.getJSONArray("new_visit")
            for (i in 0 until targetSteps.length()) {
                boundaries++
                if (targetSteps.getJSONObject(i).getBoolean("new_visit") != detected.getBoolean(i)) boundaryErrors++
                for (j in 0 until i) {
                    identityPairs++
                    val same = targetSteps.getJSONObject(i).getString("work") == targetSteps.getJSONObject(j).getString("work")
                    if (same != (keys.getString(i) == keys.getString(j))) identityErrors++
                }
            }
            if (prediction.getInt("distinct_works") != expected.getInt("distinct_works")) countErrors++
            if (prediction.getInt("visit_count") != expected.getInt("visit_count")) visitErrors++
            durationErrors += abs(prediction.getLong("visible_ms") - expected.getLong("visible_ms"))
        }
        val summary = JSONObject().put("scope", "synthetic_tracker_with_supplied_page_hints_not_real_device")
            .put("traces", predictions.size).put("samples", boundaries).put("boundary_errors", boundaryErrors)
            .put("identity_pairs", identityPairs).put("identity_errors", identityErrors)
            .put("distinct_count_error_traces", countErrors).put("visit_count_error_traces", visitErrors)
            .put("duration_mae_ms", durationErrors.average()).put("duration_max_abs_ms", durationErrors.maxOrNull())
            .put("device_safety_scenarios_executed", 0)
        File(output, "trace-summary.json").writeText(summary.toString(2) + "\n")
        println(summary.toString())
    }
}
