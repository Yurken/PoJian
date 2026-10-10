package com.emerge.app.monitoring

import android.content.Context
import com.emerge.app.EmergeApplication
import com.emerge.app.analysis.CognitiveAnalyzer
import com.emerge.app.analysis.ArgumentUnit
import com.emerge.app.audioanalysis.TextServiceConfig
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicLong

/** No raw audio or screenshots are persisted here. Findings are explicitly separate from unverified input. */
data class MonitorState(val findings: List<MonitorFinding> = emptyList(), val busy: Boolean = false,
    val notice: String = "等待所选应用的内容", val currentPackage: String = "", val contextToken: String = "",
    val screenText: String = "", val contextSince: Long = 0, val encounter: String = "", val skipped: Int = 0,
    val requestsToday: Int = 0, val framesToday: Int = 0)

private data class MonitorInput(val id: String, val packageName: String, val text: String, val at: Long,
    val kind: String, val token: String, val epoch: Long, val permission: Long, val deletion: Long,
    val encounter: String, val image: String = "", val snapshots: List<BrowseEvent> = emptyList()) {
    val key: String get() = MonitorPolicy.hash(packageName + "|" + kind + "|" + text + "|" + image)
}

class MonitorRuntime(private val app: EmergeApplication) {
    private val prefs = app.getSharedPreferences("live_monitor", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val epoch = AtomicLong()
    private val queue = Channel<MonitorInput>(6)
    private val mutableConfig = MutableStateFlow(readConfig())
    val config = mutableConfig.asStateFlow()
    private val mutableState = MutableStateFlow(MonitorState(findings = readFindings()))
    val state = mutableState.asStateFlow()
    private val arguments = readArguments().toMutableList()
    private val cache = linkedMapOf<String, Pair<Long, JSONObject>>()
    private var previousEvents: Map<Long, BrowseEvent>? = null
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var manualHandler: (() -> Unit)? = null
    private var lastLocalAnalysis = 0L
    private val startedAt = System.currentTimeMillis()

    init {
        scope.launch {
            combine(config, app.captureSettings.enabled, app.captureSettings.allowedPackages,
                app.audioCoordinator.settings.config) { a, b, c, d -> listOf(a, b, c, d) }.collect {
                epoch.incrementAndGet()
                synchronized(cache) { cache.clear() }
                connection?.disconnect()
                mutableState.update { s -> s.copy(busy = false) }
            }
        }
        scope.launch { for (input in queue) process(input) }
        scope.launch {
            combine(app.repository.allEvents, app.repository.semantics) { rows, semantics -> rows to semantics }
                .collect { (rows, semantics) ->
                val events = rows.associateBy { it.id }
                val previous = previousEvents
                previousEvents = events
                // Revisions and deletions invalidate derived comparisons, including in-flight requests.
                if (previous != null && previous.any { (id, old) ->
                    val updated = events[id]
                    updated == null || (updated.visibleText != old.visibleText && !MonitorInputs.isAppend(old, updated))
                }) invalidate(clearSemantics = false)
                val added = rows.filter { row -> previous != null && row.visibleText != previous[row.id]?.visibleText &&
                    MonitorInputs.observationTime(row) >= startedAt - 120_000 && row.sourceType != "visual_model" }
                    .sortedBy { it.timestamp }
                if (config.value.realtime && app.captureSettings.enabled.value) {
                    // A transcript batch is submitted with nearby stable context, not once per tiny segment.
                    added.groupBy { it.audioSessionId ?: "event:${it.id}" }.values.forEach { batch ->
                        val last = batch.last()
                        if (last.packageName !in app.captureSettings.allowedPackages.value) return@forEach
                        val snapshots = if (last.audioSessionId != null) rows.filter {
                            it.audioSessionId == last.audioSessionId && it.timestamp <= last.timestamp &&
                                last.timestamp - it.timestamp <= 30_000
                        }.sortedBy { it.timestamp }.takeLast(4) else listOf(last)
                        val text = if (last.audioSessionId != null) snapshots.joinToString("\n") { it.visibleText }.takeLast(6000)
                        else last.visibleText.take(6000)
                        val screen = state.value
                        val token = if (last.packageName == screen.currentPackage &&
                            (if (last.audioSessionId != null) last.timestamp >= screen.contextSince
                            else MonitorPolicy.normalized(text.take(4000)) == MonitorPolicy.normalized(screen.screenText.take(4000))))
                            screen.contextToken else ""
                        submit("event:${last.id}", last.packageName, text, MonitorInputs.observationTime(last),
                            if (last.audioSessionId != null) "audio" else "text", token,
                            last.audioSessionId?.let { "audio:$it" } ?: screen.encounter.takeIf { screen.currentPackage == last.packageName }.orEmpty().ifBlank { "event:${last.id}" },
                            snapshots = snapshots)
                    }
                }
                if (config.value.cocoon && app.captureSettings.enabled.value && rows.isNotEmpty() &&
                    System.currentTimeMillis() - lastLocalAnalysis >= 30_000) {
                    lastLocalAnalysis = System.currentTimeMillis()
                    val analysisGeneration = app.repository.captureGeneration
                    val report = CognitiveAnalyzer().analyze(rows, StatisticsPeriod.WEEK, lastLocalAnalysis,
                        checkCancellation = { scope.coroutineContext.ensureActive() }, semantics = semantics)
                    val repeated = report.findings.firstOrNull { it.title.startsWith("观察到持续低增量重复") }
                    if (repeated != null && analysisGeneration == app.repository.captureGeneration && config.value.cocoon && app.captureSettings.enabled.value) {
                        val members = report.groups.firstOrNull { it.topic == repeated.topic }?.observations.orEmpty()
                        val distinctEncounters = members.map { claim -> rows.firstOrNull { it.id == claim.eventId }
                            ?.let { it.audioSessionId ?: "event:${it.id}" } }.filterNotNull().distinct()
                        // One long recording must never manufacture eight independent encounters.
                        if (distinctEncounters.size >= 8) {
                            val latest = members.maxByOrNull { it.timestamp }!!
                            publish(MonitorFinding("local:" + MonitorPolicy.hash(repeated.topic), "event:${latest.eventId}",
                                latest.packageName, latest.timestamp, "cocoon", "repetition", latest.quote,
                                repeated.explanation, topic = repeated.topic, contextToken =
                                state.value.contextToken.takeIf { state.value.currentPackage == latest.packageName &&
                                    state.value.screenText.contains(latest.quote) }.orEmpty()))
                        }
                    }
                }
            }
        }
    }

    fun bindManual(handler: (() -> Unit)?) { manualHandler = handler }
    fun checkCurrent() { manualHandler?.invoke() ?: notice("请先启用辅助功能并打开所选应用") }
    fun setScreen(packageName: String, text: String, token: String) {
        mutableState.update { it.copy(currentPackage = packageName, screenText = text.take(6000), contextToken = token,
            contextSince = if (it.contextToken == token) it.contextSince else System.currentTimeMillis(),
            encounter = if (packageName.isBlank()) "" else if (it.currentPackage == packageName && it.encounter.isNotBlank()) it.encounter
                else "visit:$packageName:${System.currentTimeMillis()}") }
    }
    fun clearScreen() = setScreen("", "", "")
    fun revokeScreen() { mutableState.update { it.copy(contextToken = "", screenText = "", contextSince = System.currentTimeMillis()) } }
    fun notice(value: String) { mutableState.update { it.copy(notice = value) } }
    fun submitFrame(packageName: String, text: String, image: String, token: String, at: Long, manual: Boolean = false) {
        submit(if (manual) "manual-frame:$at" else "frame:$at", packageName, text.take(6000), at, "frame", token, state.value.encounter.ifBlank { "screen:$at" }, image)
    }
    fun submitCurrentText() {
        val current = state.value
        if (current.screenText.isBlank()) { notice("当前无可见文字，请开启关键画面分析"); return }
        val now = System.currentTimeMillis()
        submit("manual:$now", current.currentPackage, current.screenText, now, "text", current.contextToken, current.encounter.ifBlank { "screen:$now" })
    }
    private fun submit(id: String, packageName: String, text: String, at: Long, kind: String,
        token: String, encounter: String, image: String = "", snapshots: List<BrowseEvent>? = null) {
        val permission = app.captureSettings.permissionToken(packageName) ?: return
        if (text.length < 12 && image.isEmpty()) return
        val inputEpoch = epoch.get()
        val generation = app.repository.captureGeneration
        scope.launch {
            val targets = snapshots ?: listOfNotNull(MonitorInputs.screenSnapshot(
                app.repository.allEvents.first(), packageName, text, at))
            val input = MonitorInput(id, packageName, text, at, kind, token, inputEpoch, permission,
                generation, encounter, image, targets)
            if (current(input) && !queue.trySend(input).isSuccess) {
                mutableState.update { it.copy(skipped = it.skipped + 1, notice = "处理队列已满，部分内容未核查") }
            }
        }
    }
    private fun current(input: MonitorInput) = input.epoch == epoch.get() &&
        input.deletion == app.repository.captureGeneration && app.captureSettings.isPermissionCurrent(input.packageName, input.permission)

    private suspend fun process(input: MonitorInput) {
        if (!current(input)) return
        if (System.currentTimeMillis() - input.at > 120_000) { unchecked(input, "处理队列中的材料已经过时，本次未核查"); return }
        val service = app.audioCoordinator.settings.config.value
        val policy = config.value
        if (!service.enabled || service.endpoint.isBlank()) { unchecked(input, "尚未启用并配置云端服务；本地重复观察不等于事实核查"); return }
        if (input.kind == "frame" && !policy.visual) return
        mutableState.update { it.copy(busy = true, notice = "正在分析已采样内容并查找依据") }
        try {
            val now = System.currentTimeMillis()
            val checkFacts = policy.realtime || input.id.startsWith("manual")
            val cacheKey = input.key + ":" + checkFacts + ":" + MonitorPolicy.hash(service.endpoint + "|" + service.token)
            val cached = synchronized(cache) { cache[cacheKey]?.takeIf { now >= it.first && now - it.first <= 300_000 }?.second }
            val json = if (cached != null) JSONObject(cached.toString()).put("id", input.id) else {
                if (input.kind == "frame" && frameCount() >= policy.dailyFrames) { unchecked(input, "今日关键画面额度已用完，此画面未核查"); return }
                if (!current(input)) return
                if (!app.audioCoordinator.settings.reserveRequest()) { unchecked(input, "今日云端请求额度已用完；未处理内容不能视为真实"); return }
                if (input.kind == "frame") reserveFrame()
                mutableState.update { it.copy(requestsToday = app.audioCoordinator.settings.requestsToday(), framesToday = frameCount()) }
                request(input, service.endpoint, service.token, checkFacts).also { value ->
                    synchronized(cache) {
                        if (current(input) && app.audioCoordinator.settings.config.value == service) {
                            cache[cacheKey] = now to value
                            while (cache.size > 32) cache.remove(cache.keys.first())
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (!current(input) || app.audioCoordinator.settings.config.value != service) return
            require(json.getString("id") == input.id) { "核查结果与当前请求不一致" }
            val observed = json.optString("observed_text", "").take(6000)
            val claims = json.getJSONArray("claims")
            require(claims.length() <= 3) { "核查返回的声明数量超限" }
            val units = (0 until claims.length()).map { index ->
                val row = claims.getJSONObject(index)
                fun string(name: String, required: Boolean = false): String {
                    if (!required && !row.has(name)) return ""
                    return row.get(name) as? String ?: error("观点字段类型无效")
                }
                val uncertain = if (!row.has("uncertain")) true else row.get("uncertain") as? Boolean
                    ?: error("观点归属标记无效")
                ArgumentUnit(string("quote", true), string("topic"), string("subject"),
                    string("dimension"), string("stance"), string("evidence"),
                    string("conditions"), uncertain)
            }
            val available = input.text + if (input.kind == "frame") "\n$observed" else ""
            val validationEvent = BrowseEvent(id = 1, packageName = input.packageName, timestamp = input.at,
                eventType = 0, visibleText = available)
            require(com.emerge.app.analysis.EventSemantics(1, available, System.currentTimeMillis(), units, input.kind)
                .validFor(validationEvent)) { "观点结构或依据与本次采样不一致" }
            val outputs = (0 until claims.length()).map { index ->
                val row = claims.getJSONObject(index)
                val quote = row.getString("quote")
                require(quote.isNotBlank() && quote.length <= 500 &&
                    (if (input.kind == "frame") observed.contains(quote) || input.text.contains(quote) else input.text.contains(quote))) {
                    "声明引文与输入不一致"
                }
                val sourceRows = row.optJSONArray("sources") ?: JSONArray()
                require(sourceRows.length() <= 4)
                val sources = (0 until sourceRows.length()).map { j -> sourceRows.getJSONObject(j).let {
                    WebEvidence(it.getString("url"), it.getString("title").take(200), it.getString("quote"), it.getString("excerpt"))
                } }
                require(sources.all { it.valid() }) { "外部证据引文或链接无效" }
                val rawVerdict = row.getString("verdict")
                val verdict = if (row.optBoolean("uncertain", true) && rawVerdict in setOf("supported", "contradicted", "conflicting")) "insufficient" else rawVerdict
                require(verdict in setOf("supported", "contradicted", "conflicting", "insufficient", "not_checked", "opinion"))
                require(verdict !in setOf("supported", "contradicted", "conflicting") || sources.isNotEmpty()) { "服务未提供外部证据" }
                MonitorFinding(input.id + ":$index", input.key, input.packageName, input.at, "fact", verdict,
                    quote, row.getString("explanation").take(1000), sources, row.optString("topic", "").take(100), input.token, input.kind)
            }
            if (!current(input)) return
            val targets = input.snapshots.toMutableList()
            if (input.kind == "frame" && observed.isNotBlank()) {
                // A visual-model transcription is explicitly marked; it adds no measured viewing duration.
                val visual = BrowseEvent(packageName = input.packageName, timestamp = input.at,
                    eventType = 0, visibleText = observed, sourceType = "visual_model")
                val id = app.repository.saveCapture(visual, false, input.deletion) {
                    current(input) && app.audioCoordinator.settings.config.value == service
                }
                if (id != null) targets += visual.copy(id = id)
            }
            // Store extracted semantics separately from external fact-check sources.
            MonitorInputs.allocate(targets, units, System.currentTimeMillis(), input.kind, available).forEach { semantics ->
                val snapshot = targets.first { it.id == semantics.eventId }
                app.repository.saveSemantics(snapshot, semantics, input.deletion) {
                    current(input) && app.audioCoordinator.settings.config.value == service
                }
            }
            if (!current(input) || app.audioCoordinator.settings.config.value != service) return
            commit(input, outputs, claims, observed, policy, service)
            notice(if (outputs.isEmpty()) "当前采样未提取到可核查声明，不代表内容已被证明真实" else
                "已处理 ${outputs.size} 个候选；未显示、未采样和超出候选上限的部分不在核查范围内")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (current(input)) unchecked(input, "核查未完成：${error.message?.take(140) ?: "服务暂不可用"}")
        } finally { connection = null; mutableState.update { it.copy(busy = false) } }
    }

    @Synchronized private fun commit(input: MonitorInput, outputs: List<MonitorFinding>, claims: JSONArray, observed: String,
        policy: MonitorConfig, service: TextServiceConfig) {
        if (!current(input) || app.audioCoordinator.settings.config.value != service) return
        outputs.forEach(::publish)
        val repeated = synchronized(arguments) {
                (0 until claims.length()).forEach { index ->
                    val row = claims.getJSONObject(index)
                    val evidence = row.optString("evidence", "")
                    if (evidence.isNotBlank() && (input.text.contains(evidence) || observed.contains(evidence))) {
                        arguments += ObservedArgument(input.key, input.packageName, input.at,
                            row.optString("topic", ""), row.optString("subject", ""), row.optString("dimension", ""),
                            row.optString("stance", ""), evidence, row.optString("conditions", ""),
                            row.optBoolean("uncertain", true) || listOf("topic", "subject", "dimension", "stance")
                                .any { row.optString(it).isBlank() }, input.encounter)
                    }
                }
                while (arguments.size > 600) arguments.removeAt(0)
                prefs.edit().putString("arguments", encodeArguments(arguments)).apply()
                if (policy.cocoon) MonitorPolicy.repetition(arguments.toList(), System.currentTimeMillis()) else null
            }
            repeated?.let { a ->
                    publish(MonitorFinding("repeat:" + MonitorPolicy.hash(a.topic + a.subject + a.dimension), input.key,
                        input.packageName, input.at, "cocoon", "repetition", a.stance,
                        "已观察到同议题、对象和条件下的持续观点与论据重复；仅代表采样内容，不证明你的认同或平台推荐意图。",
                        topic = a.topic + " · " + a.subject + " · " + a.dimension, contextToken = input.token, inputKind = input.kind))
            }
    }

    private suspend fun request(input: MonitorInput, endpoint: String, token: String, checkFacts: Boolean): JSONObject {
        val payload = JSONObject().put("version", 1).put("id", input.id).put("text", input.text)
            .put("kind", input.kind).put("captured_at", input.at).put("check_facts", checkFacts)
        if (input.image.isNotEmpty()) payload.put("image", input.image)
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 512 * 1024) { "当前画面过大，请缩小采样尺寸" }
        val http = (URL(endpoint.trimEnd('/') + "/monitor").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 75000; instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
            setFixedLengthStreamingMode(bytes.size)
        }
        connection = http
        try {
            if (!current(input)) throw CancellationException("Monitoring scope changed")
            http.outputStream.use { it.write(bytes) }
            check(http.responseCode == 200) { "服务返回 ${http.responseCode}，请使用支持 /monitor 的新版网关" }
            val response = http.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = stream.read(buffer); if (n < 0) break
                    out.write(buffer, 0, n); require(out.size() <= 256 * 1024) { "服务响应超限" }
                }; out.toByteArray()
            }
            return JSONObject(String(response, Charsets.UTF_8))
        } finally { http.disconnect() }
    }
    @Synchronized private fun unchecked(input: MonitorInput, message: String) {
        if (!current(input)) return
        publish(MonitorFinding(input.id + ":unchecked", input.key, input.packageName, input.at, "fact", "not_checked",
            input.text.take(160).ifBlank { "关键画面采样" }, message, contextToken = input.token, inputKind = input.kind))
        mutableState.update { it.copy(skipped = it.skipped + 1, notice = message) }
    }
    @Synchronized private fun publish(finding: MonitorFinding) {
        mutableState.update { it.copy(findings = (listOf(finding) + it.findings.filterNot { old -> old.id == finding.id }).take(80)) }
        prefs.edit().putString("findings", JSONArray().apply { state.value.findings.forEach { put(encode(it)) } }.toString()).apply()
    }
    @Synchronized fun invalidate(clearSemantics: Boolean = true) {
        epoch.incrementAndGet(); connection?.disconnect()
        synchronized(cache) { cache.clear() }; synchronized(arguments) { arguments.clear() }
        mutableState.update { it.copy(findings = emptyList(), busy = false, notice = "材料已变更，旧核查与比较结果已清除") }
        prefs.edit().remove("findings").remove("arguments").apply()
        if (clearSemantics) scope.launch { app.repository.clearSemantics() }
    }
    fun saveConfig(value: MonitorConfig) {
        val clean = value.bounded()
        prefs.edit().putString("config", JSONObject().apply {
            put("bubble", clean.bubble); put("cocoon", clean.cocoon); put("realtime", clean.realtime); put("visual", clean.visual)
            put("cooldown", clean.cooldownMinutes); put("alerts", clean.dailyAlerts); put("seconds", clean.displaySeconds)
            put("interval", clean.frameIntervalSeconds); put("frames", clean.dailyFrames); put("quiet", clean.quiet)
            put("start", clean.quietStart); put("end", clean.quietEnd); put("research", clean.researchMode)
        }.toString()).apply()
        mutableConfig.value = clean
    }
    private fun readConfig() = runCatching {
        val j = JSONObject(prefs.getString("config", "{}").orEmpty())
        MonitorConfig(j.optBoolean("bubble"), j.optBoolean("cocoon", true), j.optBoolean("realtime"), j.optBoolean("visual"),
            j.optInt("cooldown", 30), j.optInt("alerts", 6), j.optInt("seconds", 3), j.optInt("interval", 30),
            j.optInt("frames", 20), j.optBoolean("quiet"), j.optInt("start", 22), j.optInt("end", 8), j.optBoolean("research")).bounded()
    }.getOrDefault(MonitorConfig())
    @Synchronized fun consumeAlert(finding: MonitorFinding): Boolean {
        if (!app.captureSettings.enabled.value || finding.packageName !in app.captureSettings.allowedPackages.value) return false
        val today = LocalDate.now().toString(); val count = if (prefs.getString("alert_day", "") == today) prefs.getInt("alerts_used", 0) else 0
        val key = "last:" + MonitorPolicy.alertKey(finding)
        val last = prefs.getLong(key, -1).takeIf { it >= 0 }
        val now = System.currentTimeMillis()
        if (!MonitorPolicy.mayAlert(finding, config.value, state.value.contextToken, now, LocalTime.now().hour, count, last)) return false
        return prefs.edit().putString("alert_day", today).putInt("alerts_used", count + 1).putLong(key, now).commit()
    }
    fun frameCount() = if (prefs.getString("frame_day", "") == LocalDate.now().toString()) prefs.getInt("frames_used", 0) else 0
    private fun reserveFrame() { prefs.edit().putString("frame_day", LocalDate.now().toString()).putInt("frames_used", frameCount() + 1).commit() }
    private fun encode(f: MonitorFinding) = JSONObject().put("id", f.id).put("key", f.contentKey).put("package", f.packageName)
        .put("at", f.capturedAt).put("kind", f.kind).put("verdict", f.verdict).put("claim", f.claim).put("explanation", f.explanation)
        .put("topic", f.topic).put("input", f.inputKind).put("sources", JSONArray().apply { f.sources.forEach {
            put(JSONObject().put("url", it.url).put("title", it.title).put("quote", it.quote).put("excerpt", it.excerpt))
        } })
    private fun encodeArguments(rows: List<ObservedArgument>) = JSONArray().apply { rows.forEach { a ->
        put(JSONObject().put("key", a.contentKey).put("package", a.packageName).put("at", a.capturedAt)
            .put("topic", a.topic).put("subject", a.subject).put("dimension", a.dimension).put("stance", a.stance)
            .put("evidence", a.evidence).put("conditions", a.conditions).put("uncertain", a.uncertain).put("encounter", a.encounterKey))
    } }.toString()
    private fun readArguments(): List<ObservedArgument> = runCatching {
        val rows = JSONArray(prefs.getString("arguments", "[]"))
        (0 until minOf(600, rows.length())).map { i -> rows.getJSONObject(i).let { j ->
            ObservedArgument(j.getString("key"), j.getString("package"), j.getLong("at"), j.getString("topic"),
                j.getString("subject"), j.getString("dimension"), j.getString("stance"), j.getString("evidence"),
                j.getString("conditions"), j.getBoolean("uncertain"), j.getString("encounter"))
        } }
    }.getOrDefault(emptyList())
    private fun readFindings(): List<MonitorFinding> = runCatching {
        val rows = JSONArray(prefs.getString("findings", "[]"))
        (0 until minOf(rows.length(), 80)).map { i -> rows.getJSONObject(i).let { j ->
            val s = j.getJSONArray("sources")
            MonitorFinding(j.getString("id"), j.getString("key"), j.getString("package"), j.getLong("at"),
                j.getString("kind"), j.getString("verdict"), j.getString("claim"), j.getString("explanation"),
                (0 until minOf(s.length(), 4)).map { n -> s.getJSONObject(n).let { WebEvidence(it.getString("url"), it.getString("title"), it.getString("quote"), it.getString("excerpt")) } },
                topic = j.optString("topic", ""), inputKind = j.optString("input", "text"))
        } }
    }.getOrDefault(emptyList())
}
