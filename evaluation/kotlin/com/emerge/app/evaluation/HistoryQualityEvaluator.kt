package com.emerge.app.evaluation

import com.emerge.app.analysis.ArgumentUnit
import com.emerge.app.analysis.CognitiveAnalyzer
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.Properties

/** Ground-truth labels are read from separate files and never passed to the analyzer. */
object HistoryQualityEvaluator {
    val incrementLabels = listOf("FIRST", "NEW", "REPEATED", "CONDITIONS", "UNKNOWN", "NO_CLAIM")
    val alertLabels = listOf("ALERT", "NO_ALERT", "INSUFFICIENT")
    private val base = Instant.parse("2026-10-05T00:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")

    data class Input(val id: String, val sequence: String, val eventId: Long, val seconds: Long,
        val text: String, val quote: String, val topic: String, val subject: String,
        val dimension: String, val stance: String, val evidence: String, val conditions: String,
        val uncertain: Boolean, val mode: String) {
        fun unit() = ArgumentUnit(quote, topic, subject, dimension, stance, evidence, conditions, uncertain)
    }
    data class Gold(val expected: String, val challenge: String, val rationale: String)
    data class Dataset(val metadata: Map<String, String>, val inputs: List<Input>,
        val gold: Map<String, Gold>, val windows: Map<String, Gold>)
    data class Prediction(val id: String, val sequence: String, val expected: String, val predicted: String,
        val challenge: String, val rationale: String, val source: String)
    data class Score(val labels: List<String>, val matrix: List<List<Int>>, val classes: List<Map<String, Any?>>,
        val total: Int, val correct: Int, val macroF1: Double?) {
        fun json(): Map<String, Any?> = linkedMapOf("labels" to labels, "confusion_matrix_rows_gold_columns_prediction" to matrix,
            "per_class" to classes, "total" to total, "correct" to correct, "accuracy" to ratio(correct, total),
            "macro_f1_supported_classes" to macroF1)
    }

    fun score(labels: List<String>, pairs: List<Pair<String, String>>): Score {
        require(labels.distinct().size == labels.size && labels.isNotEmpty())
        val indexes = labels.withIndex().associate { it.value to it.index }
        val matrix = Array(labels.size) { IntArray(labels.size) }
        pairs.forEach { (expected, predicted) ->
            val expectedIndex = indexes[expected] ?: error("Unknown gold label: $expected")
            val predictedIndex = indexes[predicted] ?: error("Unknown prediction label: $predicted")
            matrix[expectedIndex][predictedIndex]++
        }
        val classes = labels.mapIndexed { index, label ->
            val support = matrix[index].sum()
            val predicted = matrix.sumOf { it[index] }
            val truePositive = matrix[index][index]
            linkedMapOf<String, Any?>("label" to label, "support" to support, "predicted" to predicted,
                "true_positive" to truePositive, "precision" to ratio(truePositive, predicted),
                "recall" to ratio(truePositive, support), "f1" to ratio(2 * truePositive, support + predicted))
        }
        val supported = classes.filter { (it.getValue("support") as Int) > 0 }
        return Score(labels, matrix.map { it.toList() }, classes, pairs.size,
            matrix.indices.sumOf { matrix[it][it] }, supported.takeIf { it.isNotEmpty() }
                ?.map { it.getValue("f1") as Double }?.average())
    }

    fun load(folder: File): Dataset {
        val properties = Properties().apply { File(folder, "metadata.properties").reader(Charsets.UTF_8).use(::load) }
        val metadata = properties.stringPropertyNames().sorted().associateWith { properties.getProperty(it) }
        require(metadata["id"]?.isNotBlank() == true && metadata["kind"] in setOf("synthetic", "consented_real")) {
            "metadata requires id and kind=synthetic or consented_real"
        }
        val inputHeader = listOf("sample_id", "sequence_id", "event_id", "observed_seconds", "visible_text", "quote",
            "topic", "subject", "dimension", "stance", "evidence", "conditions", "uncertain", "mode")
        val inputs = readTable(File(folder, "inputs.tsv"), inputHeader).map { row ->
            require(row[12] in setOf("true", "false") && row[13] in setOf("structured", "local", "empty"))
            Input(row[0], row[1], row[2].toLong(), row[3].toLong(), row[4], row[5], row[6], row[7],
                row[8], row[9], row[10], row[11], row[12].toBooleanStrict(), row[13])
        }
        require(inputs.isNotEmpty() && inputs.map { it.id }.distinct().size == inputs.size) { "Inputs need unique sample IDs" }
        require(inputs.all { it.id.isNotBlank() && it.sequence.isNotBlank() && it.eventId > 0 && it.seconds in 0..518_400 &&
            it.text.isNotBlank() && (it.mode == "empty" || it.quote.isNotBlank()) }) { "Invalid input identity, time, or quotation" }
        inputs.groupBy { it.sequence to it.eventId }.forEach { (_, units) ->
            val first = units.first()
            require(units.all { it.seconds == first.seconds && it.text == first.text && it.mode == first.mode }) {
                "All units of an event must share its actual text, time, and input mode"
            }
            require(first.mode == "structured" || units.size == 1) { "Only structured events can supply multiple units" }
            val event = toEvent(first)
            if (first.mode != "local") require(EventSemantics(event.id, event.visibleText, base,
                if (first.mode == "empty") emptyList() else units.map(Input::unit)).validFor(event)) {
                "Malformed structured event ${first.sequence}/${first.eventId}"
            }
            require(units.map { Triple(it.quote, it.dimension, it.stance) }.distinct().size == units.size) { "Ambiguous duplicate units" }
        }
        fun annotations(file: String, key: String, value: String, labels: List<String>): Map<String, Gold> {
            val rows = readTable(File(folder, file), listOf(key, value, "challenge", "rationale"))
            require(rows.map { it[0] }.distinct().size == rows.size) { "Duplicate annotations in $file" }
            return rows.associate { row ->
                require(row[1] in labels && row[2].isNotBlank() && row[3].isNotBlank()) { "Invalid annotation in $file: ${row[0]}" }
                row[0] to Gold(row[1], row[2], row[3])
            }
        }
        val gold = annotations("labels.tsv", "sample_id", "expected_increment", incrementLabels)
        require(gold.keys == inputs.map { it.id }.toSet()) { "Every input needs exactly one independent annotation" }
        val windows = annotations("windows.tsv", "sequence_id", "expected_alert", alertLabels)
        require(windows.keys.all { it in inputs.map(Input::sequence) }) { "Unknown window sequence" }
        return Dataset(metadata, inputs, gold, windows)
    }

    fun decodeCell(value: String): String = buildString {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char != '\\') append(char) else {
                require(index < value.length) { "Incomplete TSV escape" }
                append(when (val escaped = value[index++]) {
                    'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; '\\' -> '\\'
                    else -> error("Unsupported TSV escape: \\$escaped")
                })
            }
        }
    }
    private fun encodeCell(value: String) = value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")
    private fun readTable(file: File, header: List<String>): List<List<String>> {
        val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }
        require(lines.firstOrNull()?.split('\t') == header) { "Unexpected header in ${file.name}" }
        return lines.drop(1).mapIndexed { index, line ->
            val cells = line.split('\t')
            require(cells.size == header.size) { "Wrong column count in ${file.name}:${index + 2}" }
            cells.map(::decodeCell)
        }
    }
    private fun toEvent(input: Input) = BrowseEvent(input.eventId, "evaluation.synthetic", base + input.seconds * 1_000,
        32, input.text, endTimestamp = base + input.seconds * 1_000 + 15_000,
        durationMillis = 15_000, sampleCount = 2, workKey = "${input.sequence}:${input.eventId}", identityMethod = "fixture")

    // Local rules retain the whole sentence, including an inline reason. Match an independently
    // annotated continuous claim span within it; do not use its expected increment for matching.
    fun quoteMatches(mode: String, annotated: String, predicted: String): Boolean =
        annotated.isNotBlank() && if (mode == "local") predicted.contains(annotated) else predicted == annotated

    fun evaluate(dataset: Dataset, folder: File, output: File, repository: File): Map<String, Any?> {
        val predictions = mutableListOf<Prediction>()
        val windows = mutableListOf<Prediction>()
        var eventsTotal = 0
        var structured = 0
        var fallback = 0
        var rejected = 0
        dataset.inputs.groupBy { it.sequence }.forEach { (sequence, inputs) ->
            val byEvent = inputs.groupBy { it.eventId }
            val events = byEvent.values.map { toEvent(it.first()) }
            val completedAt = events.maxOf { it.endTimestamp } + 30_000
            val semantics = byEvent.values.mapNotNull { units ->
                val first = units.first()
                if (first.mode == "local") null else EventSemantics(first.eventId, first.text, completedAt,
                    if (first.mode == "empty") emptyList() else units.map(Input::unit))
            }
            // Deliberately reverse both lists: processing must follow browsing time, not file or completion order.
            val report = CognitiveAnalyzer().analyze(events.reversed(), StatisticsPeriod.WEEK, completedAt, zone,
                semantics = semantics.reversed())
            eventsTotal += events.size
            structured += report.structuredAnalyzedRecords
            fallback += report.localFallbackRecords
            rejected += report.rejectedSemanticsRecords
            inputs.forEach { input ->
                val matches = report.claims.filter { it.eventId == input.eventId && quoteMatches(input.mode, input.quote, it.quote) &&
                    (input.mode != "structured" || it.dimension == input.dimension.trim() && it.stance == input.stance.trim()) }
                require(matches.size <= 1) { "Ambiguous predicted unit: ${input.id}" }
                val observation = matches.singleOrNull()
                val gold = dataset.gold.getValue(input.id)
                predictions += Prediction(input.id, sequence, gold.expected, observation?.increment?.name ?: "NO_CLAIM",
                    gold.challenge, gold.rationale, observation?.analysisSource ?: "no_claim")
            }
            dataset.windows[sequence]?.let { gold ->
                val predicted = when {
                    report.findings.any { it.title.startsWith("观察到持续低增量重复") } -> "ALERT"
                    report.findings.any { it.title.startsWith("观察到依据有补充") || it.title.startsWith("暂未发现重复线索") } -> "NO_ALERT"
                    else -> "INSUFFICIENT"
                }
                windows += Prediction(sequence, sequence, gold.expected, predicted, gold.challenge, gold.rationale, "report_rule")
            }
        }
        val incrementScore = score(incrementLabels, predictions.map { it.expected to it.predicted })
        val windowScore = score(alertLabels, windows.map { it.expected to it.predicted })
        val decided = predictions.filter { it.predicted !in setOf("UNKNOWN", "NO_CLAIM") }
        val falseAlerts = windows.count { it.predicted == "ALERT" && it.expected == "NO_ALERT" }
        val unsafeAlerts = windows.count { it.predicted == "ALERT" && it.expected == "INSUFFICIENT" }
        val summary = linkedMapOf<String, Any?>(
            "schema_version" to 1, "dataset" to dataset.metadata,
            "scope" to "Offline downstream history analysis with supplied structured fields; no model/API, extraction accuracy, truth verification, or psychological diagnosis is evaluated.",
            "dataset_sha256" to listOf("metadata.properties", "inputs.tsv", "labels.tsv", "windows.tsv").associateWith { sha(File(folder, it)) },
            "source_sha256" to listOf("app/src/main/java/com/emerge/app/analysis/CognitiveAnalyzer.kt",
                "app/src/main/java/com/emerge/app/analysis/EvidenceIdentity.kt",
                "app/src/main/java/com/emerge/app/analysis/StructuredSemantics.kt",
                "app/src/main/java/com/emerge/app/analysis/ObservationWindow.kt",
                "evaluation/kotlin/com/emerge/app/evaluation/HistoryQualityEvaluator.kt").associateWith { sha(File(repository, it)) },
            "increments" to incrementScore.json(), "windows" to windowScore.json(),
            "coverage" to linkedMapOf("annotated_units" to predictions.size, "retained_events" to eventsTotal,
                "structured_analyzed_events" to structured, "local_fallback_events" to fallback, "rejected_semantics_events" to rejected,
                "structured_event_rate" to ratio(structured, eventsTotal), "resolved_increment_units" to decided.size,
                "resolved_increment_rate" to ratio(decided.size, predictions.size), "unknown_units" to predictions.count { it.predicted == "UNKNOWN" },
                "no_claim_units" to predictions.count { it.predicted == "NO_CLAIM" },
                "resolved_only_accuracy" to ratio(decided.count { it.expected == it.predicted }, decided.size),
                "decided_windows" to windows.count { it.predicted != "INSUFFICIENT" },
                "window_decision_rate" to ratio(windows.count { it.predicted != "INSUFFICIENT" }, windows.size)),
            "alerts" to linkedMapOf("annotated_windows" to windows.size, "negative_windows" to windows.count { it.expected == "NO_ALERT" },
                "false_alerts" to falseAlerts, "false_alert_rate_over_negative_windows" to ratio(falseAlerts, windows.count { it.expected == "NO_ALERT" }),
                "unsupported_alerts_on_insufficient_windows" to unsafeAlerts,
                "missed_positive_windows" to windows.count { it.expected == "ALERT" && it.predicted != "ALERT" }),
            "by_challenge" to predictions.groupBy { it.challenge }.toSortedMap().mapValues { (_, rows) ->
                linkedMapOf("units" to rows.size, "correct" to rows.count { it.expected == it.predicted },
                    "accuracy" to ratio(rows.count { it.expected == it.predicted }, rows.size)) })
        output.mkdirs()
        File(output, "summary.json").writeText(json(summary) + "\n")
        writePredictions(File(output, "predictions.tsv"), predictions)
        writePredictions(File(output, "window-predictions.tsv"), windows)
        File(output, "report.md").writeText(markdown(dataset, incrementScore, windowScore, summary, predictions, windows))
        return summary
    }

    private fun writePredictions(file: File, rows: List<Prediction>) {
        file.writeText("sample_id\tsequence_id\texpected\tpredicted\tcorrect\tchallenge\tanalysis_source\trationale\n" +
            rows.joinToString("\n", postfix = "\n") { row ->
                listOf(row.id, row.sequence, row.expected, row.predicted, (row.expected == row.predicted).toString(),
                    row.challenge, row.source, row.rationale).joinToString("\t", transform = ::encodeCell)
            })
    }
    private fun markdown(dataset: Dataset, increments: Score, windows: Score, summary: Map<String, Any?>,
        predictions: List<Prediction>, windowPredictions: List<Prediction>) = buildString {
        appendLine("# 结构观点与个人历史离线评测")
        appendLine()
        appendLine("数据集：`${dataset.metadata.getValue("id")}`；性质：`${dataset.metadata.getValue("kind")}`；标注状态：`${dataset.metadata["annotation_status"] ?: "未说明"}`。")
        appendLine()
        if (dataset.metadata["kind"] == "synthetic") appendLine("所有内容和序列均为手工构造的合成诊断样本。结果不能代表真实平台准确率、用户风险率或云端模型抽取准确率。")
        appendLine("本入口固定提供结构字段，评测实际 CognitiveAnalyzer 的历史比较与提醒规则。UNKNOWN 和无观点均保留在完整混淆矩阵与总体分母中，未按预测成功情况删样本。")
        appendLine()
        fun table(title: String, score: Score) {
            appendLine("## $title")
            appendLine()
            appendLine("样本 ${score.total}；整体准确率 ${percent(ratio(score.correct, score.total))}；有金标类别的 macro-F1 ${decimal(score.macroF1)}。")
            appendLine()
            appendLine("| 类别 | 金标数 | 预测数 | precision | recall | F1 |")
            appendLine("| --- | ---: | ---: | ---: | ---: | ---: |")
            score.classes.forEach { row -> appendLine("| ${row["label"]} | ${row["support"]} | ${row["predicted"]} | ${decimal(row["precision"] as Double?)} | ${decimal(row["recall"] as Double?)} | ${decimal(row["f1"] as Double?)} |") }
            appendLine()
            appendLine("混淆矩阵：行是独立金标，列是预测。")
            appendLine()
            appendLine("| 金标\\预测 | ${score.labels.joinToString(" | ")} |")
            appendLine("| --- | ${score.labels.joinToString(" | ") { "---:" }} |")
            score.labels.forEachIndexed { i, label -> appendLine("| $label | ${score.matrix[i].joinToString(" | ")} |") }
            appendLine()
        }
        table("信息增量", increments)
        table("窗口提醒", windows)
        appendLine("## 覆盖与误提醒")
        appendLine()
        @Suppress("UNCHECKED_CAST") val coverage = summary.getValue("coverage") as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val alerts = summary.getValue("alerts") as Map<String, Any?>
        appendLine("结构结果覆盖 ${coverage["structured_analyzed_events"]}/${coverage["retained_events"]} 个事件（${percent(coverage["structured_event_rate"] as Double?)}），本地兜底 ${coverage["local_fallback_events"]} 个。此处表示提供结果的覆盖，不证明观点抽取完整。")
        appendLine("增量可判断 ${coverage["resolved_increment_units"]}/${coverage["annotated_units"]}（${percent(coverage["resolved_increment_rate"] as Double?)}）；UNKNOWN ${coverage["unknown_units"]}，未提取到目标观点 ${coverage["no_claim_units"]}。仅可判断项准确率 ${percent(coverage["resolved_only_accuracy"] as Double?)}须与完整总体准确率一起阅读。")
        appendLine("负类窗口误提醒 ${alerts["false_alerts"]}/${alerts["negative_windows"]}（${percent(alerts["false_alert_rate_over_negative_windows"] as Double?)}）；证据不足窗口仍提醒 ${alerts["unsupported_alerts_on_insufficient_windows"]} 个；正类窗口漏提醒 ${alerts["missed_positive_windows"]} 个。窗口可判断覆盖 ${percent(coverage["window_decision_rate"] as Double?)}。")
        appendLine()
        appendLine("## 错误样本")
        appendLine()
        (predictions + windowPredictions).filter { it.expected != it.predicted }.forEach { row ->
            appendLine("- `${row.id}`（${row.challenge}）：${row.expected} → ${row.predicted}。${row.rationale}")
        }
        if ((predictions + windowPredictions).all { it.expected == it.predicted }) appendLine("此固定样本中没有错误，不代表真实场景没有错误。")
        appendLine()
        appendLine("机器结果、完整预测及逐项理由见同目录 summary.json、predictions.tsv、window-predictions.tsv。数据与源码 SHA-256 记录在 JSON 中；重复运行不会写入当前时间，以便逐字比较。真实标注与双人复核步骤见 docs/OFFLINE_QUALITY_EVALUATION.md。")
    }
    private fun ratio(numerator: Int, denominator: Int): Double? = if (denominator == 0) null else numerator.toDouble() / denominator
    private fun decimal(value: Double?) = value?.let { String.format(Locale.ROOT, "%.4f", it) } ?: "—"
    private fun percent(value: Double?) = value?.let { String.format(Locale.ROOT, "%.2f%%", it * 100) } ?: "—"
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun json(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"" + value.flatMap { char -> when (char) {
            '"' -> "\\\""; '\\' -> "\\\\"; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"
            else -> if (char.code < 32) "\\u%04x".format(char.code) else char.toString()
        }.toList() }.joinToString("") + "\""
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(",", "{", "}") { json(it.key.toString()) + ":" + json(it.value) }
        is Iterable<*> -> value.joinToString(",", "[", "]", transform = ::json)
        else -> error("Unsupported JSON type")
    }

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 6 && args[0] == "--dataset" && args[2] == "--output" && args[4] == "--repository") {
            "Usage: --dataset <folder> --output <folder> --repository <root>"
        }
        val folder = File(args[1]).canonicalFile
        val output = File(args[3]).canonicalFile
        val repository = File(args[5]).canonicalFile
        require(output != folder && !output.toPath().startsWith(folder.toPath())) { "Output must not overwrite the annotated dataset" }
        val dataset = load(folder)
        evaluate(dataset, folder, output, repository)
        println(File(output, "report.md").readText())
    }
}
