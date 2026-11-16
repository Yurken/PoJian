package com.emerge.app.audioanalysis

import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object RemoteAudioInsights {
    suspend fun analyze(config: TextServiceConfig, current: List<TranscriptEvidence>, history: List<TranscriptEvidence>,
        application: com.emerge.app.EmergeApplication? = null, isCurrent: () -> Boolean = { true }): AudioInsightReport = withContext(Dispatchers.IO) {
        require(current.isNotEmpty()) { "暂无转写可分析" }
        require(current.sumOf { it.text.length } <= 20_000) { "转写较长，请使用本地分析或分段导入" }
        if (com.emerge.app.monitoring.SecureChannelConfig.applies(config)) {
            val app = requireNotNull(application) { "安全分析上下文不可用" }
            val client = com.emerge.app.monitoring.SecureSemanticClient(app)
            val claims = mutableListOf<AudioClaimInsight>()
            for (segment in current) {
                require(segment.text.length <= 16000) { "转写较长，请分段分析" }
                val facts = app.repository.recentHistoryFacts(before = segment.timestamp)
                val workId = "audio:${segment.sessionId}:${segment.id}"
                val payload = JSONObject().put("work_id", workId).put("platform", "audio_transcript")
                    .put("title", "").put("author", "").put("clean_text", com.emerge.app.monitoring.CloudPrivacyStats.sanitize(segment.text))
                    .put("dwell_seconds", ((segment.endMillis - segment.startMillis) / 1000).coerceIn(0, 86400))
                    .put("history_facts", JSONArray().apply { facts.forEach { fact -> put(JSONObject()
                        .put("fact_id", fact.factId).put("fact_text", com.emerge.app.monitoring.CloudPrivacyStats.sanitize(fact.factText))
                        .put("source_platform", fact.sourcePlatform)
                        .put("captured_at", java.time.Instant.ofEpochMilli(fact.capturedAt).toString())) } })
                val result = client.analyze(config, payload, isCurrent)
                val event = com.emerge.app.data.BrowseEvent(id = segment.id, packageName = "audio_transcript",
                    timestamp = segment.timestamp, eventType = 0, visibleText = segment.text, workKey = workId)
                val semantic = com.emerge.app.analysis.SemanticsCodec.decodeV2(result, event).semanticV2!!
                require(semantic.facts.all { it.matchedHistoryId == null || it.matchedHistoryId in facts.map { fact -> fact.factId } })
                semantic.viewpoints.forEach { viewpoint ->
                    val evidence = viewpoint.evidences.firstOrNull { segment.text.contains(it.factSpan) }
                    if (evidence != null) claims += AudioClaimInsight(viewpoint.stanceSummary, evidence.factSpan, segment.id,
                        viewpoint.authorAttitude, if (evidence.isCoveredByHistory) "依据重复" else "依据有新增")
                }
            }
            return@withContext AudioInsightReport("识别出 ${claims.size} 个观点", "安全文字服务 · 引文已校验", claims)
        }
        fun rows(list: List<TranscriptEvidence>) = JSONArray().apply { list.forEach { row -> put(JSONObject().apply {
            put("id", row.id); put("text", com.emerge.app.monitoring.CloudPrivacyStats.sanitize(row.text)); put("timestamp", row.timestamp)
            put("startMillis", row.startMillis); put("endMillis", row.endMillis)
        }) } }
        var historicalCharacters = 0
        val candidates = LocalAudioInsights.candidates(current, history).filter { row ->
            if (historicalCharacters + row.text.length > 20_000) false
            else { historicalCharacters += row.text.length; true }
        }
        val payload = JSONObject().apply { put("segments", rows(current)); put("history", rows(candidates)); put("version", 1) }.toString()
        val connection = (URL(config.endpoint.trimEnd('/') + "/analyze").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 45_000; instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (config.token.isNotEmpty()) setRequestProperty("Authorization", "Bearer ${config.token}")
        }
        try {
            currentCoroutineContext().ensureActive()
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode in 200..299) { "文字服务返回 ${connection.responseCode}，请检查地址、凭据和服务状态" }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (output.size() <= 256 * 1024) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            check(bytes.size <= 256 * 1024) { "文字服务响应过大" }
            currentCoroutineContext().ensureActive()
            validate(JSONObject(String(bytes, Charsets.UTF_8)), current, candidates)
        } finally { connection.disconnect() }
    }

    /** Quotes and historical references are checked again on the device, regardless of provider. */
    fun validate(json: JSONObject, current: List<TranscriptEvidence>, history: List<TranscriptEvidence>): AudioInsightReport {
        val sources = current.associateBy { it.id }; val previous = history.associateBy { it.id }
        val rows = json.getJSONArray("claims")
        require(rows.length() <= 24) { "服务返回的观点过多" }
        val claims = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val source = sources[row.getLong("segmentId")] ?: error("服务引用了不存在的转写片段")
            val evidence = row.getString("evidence")
            require(evidence.isNotBlank() && evidence.length <= 1000 && source.text.contains(evidence)) { "服务引文与原始转写不一致" }
            val historical = if (row.isNull("historySegmentId")) null else previous[row.getLong("historySegmentId")]
                ?: error("服务引用了未提供的历史")
            val oldQuote = row.optString("historyQuote", "").takeIf { it.isNotBlank() }
            require(historical != null || oldQuote == null) { "历史引文缺少来源" }
            if (historical != null) require(oldQuote != null && historical.text.contains(oldQuote)) { "历史引文不匹配" }
            val claim = row.getString("claim").take(500)
            require(claim.isNotBlank()) { "服务观点为空" }
            AudioClaimInsight(claim, evidence, source.id, row.optString("relation", "出处未知").take(120),
                row.optString("novelty", "暂无法判断").take(120), historical?.id, oldQuote)
        }
        return AudioInsightReport(json.getString("summary").take(1500),
            "文字服务 · ${json.optString("model", "已配置服务").take(100)} · 引文已校验", claims,
            usage = json.optJSONObject("usage")?.let { "输入 ${it.optInt("prompt_tokens")} / 输出 ${it.optInt("completion_tokens")} tokens" })
    }
}
