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
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.selects.onTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/** No raw audio or screenshots are persisted here. Findings are explicitly separate from unverified input. */
data class MonitorState(val findings: List<MonitorFinding> = emptyList(), val busy: Boolean = false,
    val notice: String = "等待所选应用的内容", val currentPackage: String = "", val contextToken: String = "",
    val screenText: String = "", val contextSince: Long = 0, val encounter: String = "", val skipped: Int = 0,
    val requestsToday: Int = 0, val framesToday: Int = 0,
    val pending: Int = 0, val retrying: Int = 0, val failed: Int = 0)

private data class MonitorInput(val id: String, val packageName: String, val text: String, val at: Long,
    val kind: String, val token: String, val epoch: Long, val permission: Long, val deletion: Long,
    val encounter: String, val image: String = "", val snapshots: List<BrowseEvent> = emptyList(),
    val checkFacts: Boolean = false, val lease: MonitorTaskLease? = null) {
    val key: String by lazy { MonitorPolicy.hash(packageName + "|" + kind + "|" + text + "|" + image) }
}

private data class MonitorFrameWork(val input: MonitorInput, val completed: (Boolean) -> Unit)

/** Revoking one input leaves the consumer available for later authorized inputs. */
internal class MonitorInputInvalidated : Exception("Monitoring input invalidated")

internal fun requireCurrentMonitorInput(current: Boolean) {
    if (!current) throw MonitorInputInvalidated()
}

internal suspend fun <T> handleMonitorInput(cancelled: T, work: suspend () -> T): T = try {
    currentCoroutineContext().ensureActive()
    work()
} catch (_: MonitorInputInvalidated) {
    // A worker cancelled at the same time must still propagate its structured cancellation.
    currentCoroutineContext().ensureActive()
    cancelled
}

@OptIn(ExperimentalCoroutinesApi::class)
class MonitorRuntime(private val app: EmergeApplication) {
    private val prefs = app.getSharedPreferences("live_monitor", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val epoch = AtomicLong()
    // Images stay in memory. Text/audio requests are encrypted in Room before submission.
    private val frames = Channel<MonitorFrameWork>(1)
    private val frameOutstanding = AtomicBoolean()
    internal val samplingRevision: Long get() = epoch.get()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val textWake = Channel<Unit>(Channel.CONFLATED)
    private val settingsReady = CompletableDeferred<Unit>()
    private val tasks get() = app.monitorTasks
    @Volatile private var authorizationRevision = prefs.getLong("authorization_revision", 0L)
    private val mutableConfig = MutableStateFlow(readConfig())
    val config = mutableConfig.asStateFlow()
    private val mutableState = MutableStateFlow(MonitorState(findings = readFindings()))
    val state = mutableState.asStateFlow()
    private val cache = linkedMapOf<String, Pair<Long, JSONObject>>()
    private var previousEvents: Map<Long, BrowseEvent>? = null
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var manualHandler: (() -> Unit)? = null
    private var exposureJob: Job? = null

    init {
        scope.launch {
            combine(config, app.captureSettings.enabled, app.captureSettings.allowedPackages,
                app.audioCoordinator.settings.config) { a, b, c, d -> listOf(a, b, c, d) }.collect {
                epoch.incrementAndGet()
                synchronized(cache) { cache.clear() }
                connection?.disconnect()
                mutableState.update { s -> s.copy(busy = false) }
                settingsReady.complete(Unit)
                wake.trySend(Unit)
                textWake.trySend(Unit)
            }
        }
        scope.launch {
            tasks.counts.collect { counts ->
                mutableState.update { it.copy(pending = counts.queued + counts.running,
                    retrying = counts.retrying, failed = counts.failed) }
                wake.trySend(Unit)
                textWake.trySend(Unit)
            }
        }
        scope.launch {
            settingsReady.await()
            tasks.recover(System.currentTimeMillis())
            var preferFrame = true
            while (isActive) {
                // Alternate when both sources are ready; slow visual requests cannot starve transcripts.
                val frame = if (preferFrame) frames.tryReceive().getOrNull() else null
                if (frame != null) { processFrame(frame); preferFrame = false; continue }
                val lease = tasks.claim(System.currentTimeMillis(), ::authorized)
                if (lease != null) {
                    preferFrame = true
                    val r = lease.request
                    val permission = app.captureSettings.permissionToken(r.packageName)
                    if (permission == null) { tasks.cancel(lease); continue }
                    val input = MonitorInput(lease.task.id, r.packageName, r.text, r.capturedAt, r.kind,
                        r.contextToken, epoch.get(), permission, app.repository.captureGeneration,
                        r.encounter, snapshots = r.snapshots, checkFacts = r.checkFacts, lease = lease)
                    when (val result = process(input)) {
                        ProcessResult.DONE -> tasks.finish(lease, System.currentTimeMillis()) { current(input) && authorized(r) }
                        ProcessResult.CANCELLED -> tasks.cancel(lease)
                        is ProcessResult.RETRY -> {
                            val retried = tasks.retry(lease, result.reason, System.currentTimeMillis()) { current(input) && authorized(r) }
                            if (retried) notice("网络或服务暂不可用，材料已保存，等待重试")
                            else unchecked(input, "核查未完成，请稍后重试")
                        }
                        is ProcessResult.FAILED -> {
                            tasks.fail(lease, result.reason, System.currentTimeMillis()) { current(input) && authorized(r) }
                        }
                    }
                    continue
                }
                val waitingFrame = frames.tryReceive().getOrNull()
                if (waitingFrame != null) { processFrame(waitingFrame); preferFrame = false; continue }
                val due = tasks.nextReadyAt()
                val waitMillis = if (due == null) 30_000L else (due - System.currentTimeMillis()).coerceIn(1, 30_000)
                select<Unit> {
                    frames.onReceive { processFrame(it); preferFrame = false }
                    wake.onReceive { }
                    onTimeout(waitMillis) { }
                }
            }
        }
        scope.launch {
            settingsReady.await()
            while (isActive) {
                enqueueTextAnalysis()
                select<Unit> {
                    textWake.onReceive { }
                    onTimeout(30_000) { }
                }
            }
        }
        scope.launch {
            combine(app.repository.allEvents, app.repository.semantics) { rows, semantics -> rows to semantics }
                .collect { (rows, semantics) ->
                val events = rows.associateBy { it.id }
                val previous = previousEvents
                previousEvents = events
                // Revisions and deletions invalidate derived comparisons, including in-flight requests.
                if (previous != null && previous.any { (id, old) ->
                    val updated = events[id]
                    updated == null || updated.workKey != old.workKey || updated.parentEventId != old.parentEventId || (updated.visibleText != old.visibleText && !MonitorInputs.isAppend(old, updated))
                }) invalidate(clearSemantics = false)
                textWake.trySend(Unit)
                exposureJob?.cancel()
                if (config.value.cocoon && app.captureSettings.enabled.value && rows.isNotEmpty()) {
                    exposureJob = scope.launch {
                        delay(500)
                        val analysisGeneration = app.repository.captureGeneration
                        val analysisEpoch = epoch.get()
                        val report = CognitiveAnalyzer().analyze(rows, StatisticsPeriod.WEEK, System.currentTimeMillis(),
                            checkCancellation = { coroutineContext.ensureActive() }, semantics = semantics)
                        coroutineContext.ensureActive()
                        if (analysisEpoch == epoch.get() && analysisGeneration == app.repository.captureGeneration && config.value.cocoon && app.captureSettings.enabled.value) {
                            val exposure = report.exposure ?: return@launch
                            val findings = exposure.viewpoints.zip(exposure.findings()).toMap()
                            val signals = exposure.signals
                            val ids = signals.map { "exposure:" + MonitorPolicy.hash(it.key) }.toSet()
                            mutableState.update { state -> state.copy(findings = state.findings.filter {
                                it.kind != "cocoon" || it.id in ids
                            }) }
                            signals.forEach { signal ->
                                val latest = signal.latest
                                publish(MonitorFinding("exposure:" + MonitorPolicy.hash(signal.key), "event:${latest.eventId}",
                                    latest.packageName, latest.timestamp, "cocoon", "concentration", latest.quote,
                                    findings.getValue(signal).explanation, topic = "${signal.topic} · ${signal.subject} · ${signal.dimension}", contextToken =
                                    state.value.contextToken.takeIf { state.value.currentPackage == latest.packageName &&
                                        state.value.screenText.contains(latest.quote) }.orEmpty()))
                            }
                            prefs.edit().putString("findings", JSONArray().apply { state.value.findings.forEach { put(encode(it)) } }.toString()).apply()
                        }
                    }
                }
            }
        }
    }

    /** Refill the bounded durable queue on capture, opt-in, restart and completion. */
    private suspend fun enqueueTextAnalysis() {
        val service = app.audioCoordinator.settings.config.value
        if (!service.enabled || service.endpoint.isBlank() || !app.captureSettings.enabled.value) return
        val inputEpoch = epoch.get()
        val generation = app.repository.captureGeneration
        val pending = tasks.observeInputStates().first().filterValues { it == "queued" || it == "running" }.keys
        val batches = MonitorInputs.pendingTextBatches(app.repository.allEvents.first().filter { it.id !in pending },
            app.repository.semantics.first(), app.captureSettings.allowedPackages.value, System.currentTimeMillis())
        for (snapshots in batches) {
            if (inputEpoch != epoch.get() || service != app.audioCoordinator.settings.config.value) return
            val last = snapshots.last()
            val permission = app.captureSettings.permissionToken(last.packageName) ?: continue
            val text = snapshots.joinToString("\n") { it.visibleText }.take(6000)
            if (text.length < 12) continue
            val screen = state.value
            val token = if (last.packageName == screen.currentPackage &&
                (if (last.audioSessionId != null) last.timestamp >= screen.contextSince
                else MonitorPolicy.normalized(text.take(4000)) == MonitorPolicy.normalized(screen.screenText.take(4000))))
                screen.contextToken else ""
            val input = MonitorInput("event:${last.id}", last.packageName, text, MonitorInputs.observationTime(last),
                if (last.audioSessionId != null) "audio" else "text", token, inputEpoch, permission, generation,
                last.audioSessionId?.let { "audio:$it" } ?: screen.encounter.takeIf {
                    screen.currentPackage == last.packageName && last.timestamp >= screen.contextSince
                }.orEmpty().ifBlank { "event:${last.id}" }, snapshots = snapshots,
                checkFacts = config.value.realtime)
            val request = MonitorTaskRequest(input.id, input.packageName, input.kind, input.text, input.at,
                token, input.encounter, snapshots, serviceFingerprint(), authorizationKey(), input.checkFacts)
            if (tasks.enqueue(request, System.currentTimeMillis()) { current(input) && authorized(request) } == EnqueueResult.FULL) return
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
    internal fun canSampleFrame(manual: Boolean): Boolean = !frameOutstanding.get() &&
        (manual || state.value.pending < MonitorTaskStore.CAPACITY)

    internal fun submitFrame(packageName: String, text: String, image: String, token: String, at: Long,
        manual: Boolean = false, material: BrowseEvent? = null, boundParent: BrowseEvent? = null, completed: (Boolean) -> Unit = {}): Boolean {
        val permission = app.captureSettings.permissionToken(packageName) ?: return false
        if (!frameOutstanding.compareAndSet(false, true)) return false
        val inputEpoch = epoch.get()
        val generation = app.repository.captureGeneration
        val screen = state.value
        val checkFacts = config.value.realtime || manual
        scope.launch {
            var enqueued = false
            try {
                if (material != null && (material.sourceType != ScreenTextMaterial.SOURCE || material.packageName != packageName ||
                    app.repository.findEvent(material.id) != material)) return@launch
                val parent = boundParent ?: material?.parentEventId?.let { app.repository.findEvent(it) }
                if (parent != null) {
                    val retained = app.repository.findEvent(parent.id) ?: return@launch
                    if (retained.sourceType != "accessibility" || retained.packageName != packageName ||
                        retained.workKey != parent.workKey || retained != parent && !MonitorInputs.isAppend(parent, retained)) return@launch
                }
                val targets = listOfNotNull(parent, material)
                val input = MonitorInput(if (manual) "manual-frame:$at" else "frame:$at", packageName, ScreenTextMaterial.inputText(text, material), at,
                    "frame", token, inputEpoch, permission, generation, screen.encounter.ifBlank { "screen:$at" },
                    image, targets, checkFacts)
                if (current(input)) enqueued = frames.trySend(MonitorFrameWork(input, completed)).isSuccess
            } finally {
                if (!enqueued) { frameOutstanding.set(false); completed(false) }
            }
        }
        return true
    }

    private suspend fun processFrame(work: MonitorFrameWork) {
        var success = false
        try {
            val result = process(work.input)
            success = result == ProcessResult.DONE
            if (result is ProcessResult.FAILED) {
                enqueueScreenTextFallback(work.input)
            }
        }
        finally { frameOutstanding.set(false); work.completed(success) }
    }

    private suspend fun enqueueScreenTextFallback(input: MonitorInput) {
        val material = input.snapshots.firstOrNull { it.sourceType == ScreenTextMaterial.SOURCE } ?: return
        if (!current(input)) return
        val request = MonitorTaskRequest("ocr:${material.id}:${input.checkFacts}", input.packageName, "text",
            material.visibleText, material.timestamp, input.token, input.encounter, listOf(material),
            serviceFingerprint(), authorizationKey(), input.checkFacts)
        when (tasks.enqueue(request, System.currentTimeMillis()) { current(input) && authorized(request) }) {
            EnqueueResult.ENQUEUED -> { wake.trySend(Unit); notice("网络或服务暂不可用，材料已保存，等待重试") }
            EnqueueResult.FULL -> notice("待处理内容已满，本次未核查")
            else -> Unit
        }
    }
    fun submitCurrentText() {
        val current = state.value
        if (current.screenText.isBlank()) { notice("未获取到页面文字，可开启关键画面分析"); return }
        val now = System.currentTimeMillis()
        submit("manual:$now", current.currentPackage, current.screenText, now, "text", current.contextToken, current.encounter.ifBlank { "screen:$now" })
    }
    private fun submit(id: String, packageName: String, text: String, at: Long, kind: String,
        token: String, encounter: String, snapshots: List<BrowseEvent>? = null) {
        val permission = app.captureSettings.permissionToken(packageName) ?: return
        if (text.length < 12) return
        val inputEpoch = epoch.get()
        val generation = app.repository.captureGeneration
        scope.launch {
            val targets = snapshots ?: listOfNotNull(MonitorInputs.screenSnapshot(
                app.repository.allEvents.first(), packageName, text, at))
            val input = MonitorInput(id, packageName, text, at, kind, token, inputEpoch, permission,
                generation, encounter, snapshots = targets, checkFacts = config.value.realtime || id.startsWith("manual"))
            if (!current(input)) return@launch
            val request = MonitorTaskRequest(id, packageName, kind, text, at, token, encounter, targets,
                serviceFingerprint(), authorizationKey(), input.checkFacts)
            if (!authorized(request)) { unchecked(input, "云端分析未开启或服务未配置，本次未核查"); return@launch }
            when (tasks.enqueue(request, System.currentTimeMillis()) { current(input) && authorized(request) }) {
                EnqueueResult.ENQUEUED -> wake.trySend(Unit)
                EnqueueResult.DUPLICATE -> notice("此内容已提交分析")
                EnqueueResult.FULL -> mutableState.update { it.copy(skipped = it.skipped + 1, notice = "待处理内容已满，本次未核查") }
                EnqueueResult.INVALID -> unchecked(input, "此内容未能提交分析，请稍后重试")
            }
        }
    }
    private fun current(input: MonitorInput) = input.epoch == epoch.get() &&
        input.deletion == app.repository.captureGeneration && app.captureSettings.isPermissionCurrent(input.packageName, input.permission) &&
        (input.lease == null || authorized(input.lease.request))

    private fun serviceFingerprint(): String {
        val service = app.audioCoordinator.settings.config.value
        return MonitorPolicy.hash(service.endpoint + "|" + service.token)
    }
    private fun authorizationKey() = MonitorPolicy.hash("${app.captureSettings.authorizationRevision}|" +
        "${app.audioCoordinator.settings.authorizationRevision}|$authorizationRevision")
    private fun authorized(request: MonitorTaskRequest): Boolean =
        app.captureSettings.permissionToken(request.packageName) != null &&
            app.audioCoordinator.settings.config.value.let { it.enabled && it.endpoint.isNotBlank() } &&
            request.authorizationKey == authorizationKey() && request.serviceFingerprint == serviceFingerprint()

    private sealed interface ProcessResult {
        data object DONE : ProcessResult
        data object CANCELLED : ProcessResult
        data class RETRY(val reason: String) : ProcessResult
        data class FAILED(val reason: String) : ProcessResult
    }
    private class HttpFailure(val status: Int) : java.io.IOException("服务返回 $status")

    private suspend fun process(input: MonitorInput): ProcessResult =
        handleMonitorInput(ProcessResult.CANCELLED) { processCurrent(input) }

    private suspend fun processCurrent(input: MonitorInput): ProcessResult {
        if (!current(input)) return ProcessResult.CANCELLED
        val maxAge = if (input.lease == null) 120_000L else 24 * 60 * 60 * 1000L
        if (System.currentTimeMillis() - input.at !in 0..maxAge) {
            unchecked(input, "采样内容已过期，本次未核查")
            return ProcessResult.FAILED("expired")
        }
        input.lease?.let { if (!tasks.isLeaseCurrent(it, System.currentTimeMillis()) { current(input) }) return ProcessResult.CANCELLED }
        val service = app.audioCoordinator.settings.config.value
        val policy = config.value
        if (!service.enabled || service.endpoint.isBlank()) { unchecked(input, "云端分析未开启或服务未配置，本次未核查"); return ProcessResult.CANCELLED }
        if (input.kind == "frame" && !policy.visual) return ProcessResult.CANCELLED
        mutableState.update { it.copy(busy = true, notice = "正在云端分析采样内容") }
        try {
            val now = System.currentTimeMillis()
            val checkFacts = input.checkFacts
            val cacheKey = input.key + ":" + checkFacts + ":" + MonitorPolicy.hash(service.endpoint + "|" + service.token)
            val cached = synchronized(cache) { cache[cacheKey]?.takeIf { now >= it.first && now - it.first <= 300_000 }?.second }
            val json = if (cached != null) JSONObject(cached.toString()).put("id", input.id) else {
                if (!current(input)) return ProcessResult.CANCELLED
                app.audioCoordinator.settings.recordRequest()
                if (input.kind == "frame") reserveFrame()
                mutableState.update { it.copy(requestsToday = app.audioCoordinator.settings.requestsToday(), framesToday = frameCount()) }
                request(input, service.endpoint, service.token, checkFacts)
            }
            currentCoroutineContext().ensureActive()
            if (!current(input) || app.audioCoordinator.settings.config.value != service) return ProcessResult.CANCELLED
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
                fun anchors(name: String): List<String> {
                    if (!row.has(name)) return emptyList()
                    val values = row.get(name) as? JSONArray ?: error("论据身份字段类型无效")
                    require(values.length() <= 4) { "论据身份字段数量超限" }
                    return (0 until values.length()).map { item -> values.get(item) as? String
                        ?: error("论据身份字段类型无效") }
                }
                ArgumentUnit(string("quote", true), string("topic"), string("subject"),
                    string("dimension"), string("stance"), string("evidence"),
                    string("conditions"), uncertain, anchors("evidence_actors"), anchors("evidence_origins"),
                    if (row.has("presentation")) com.emerge.app.analysis.ClaimPresentationCodec.decode(row.getJSONObject("presentation")) else null)
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
            if (!current(input)) return ProcessResult.CANCELLED
            val targets = input.snapshots.toMutableList()
            val observedContent = com.emerge.app.capture.PlatformTextCleaner.clean(observed)
            if (input.kind == "frame" && observedContent.isNotBlank() && targets.none { observedContent in it.visibleText }) {
                // A visual-model transcription is explicitly marked; it adds no measured viewing duration.
                val parent = targets.singleOrNull { it.sourceType == "accessibility" && it.packageName == input.packageName }
                val visual = BrowseEvent(packageName = input.packageName, timestamp = input.at,
                    eventType = 0, visibleText = observedContent, sourceType = "visual_model", parentEventId = parent?.id,
                    workKey = parent?.workKey, identityMethod = parent?.identityMethod ?: "unknown")
                val id = app.repository.saveCapture(visual, false, input.deletion) {
                    current(input) && app.audioCoordinator.settings.config.value == service
                }
                if (id != null) targets += visual.copy(id = id)
            }
            // Store extracted semantics separately from external fact-check sources.
            MonitorInputs.allocate(targets, units, System.currentTimeMillis(), input.kind, available).forEach { semantics ->
                val snapshot = targets.first { it.id == semantics.eventId }
                app.repository.saveSemantics(snapshot, semantics, input.deletion, transactionGuard = {
                    input.lease?.let { tasks.isLeaseCurrent(it, System.currentTimeMillis()) {
                        current(input) && app.audioCoordinator.settings.config.value == service
                    } } ?: true
                }) {
                    current(input) && app.audioCoordinator.settings.config.value == service
                }
            }
            if (!current(input) || app.audioCoordinator.settings.config.value != service) return ProcessResult.CANCELLED
            input.lease?.let { if (!tasks.isLeaseCurrent(it, System.currentTimeMillis()) { current(input) }) return ProcessResult.CANCELLED }
            commit(input, outputs, service)
            val retryableFactFailure = checkFacts && (0 until claims.length()).any { index ->
                val claim = claims.getJSONObject(index)
                claim.optString("verdict") == "not_checked" && claim.optJSONObject("check_error")?.optBoolean("retryable", false) == true
            }
            // Keep the extracted viewpoints, but let the existing bounded queue retry
            // transient retrieval/model failures instead of permanently completing them.
            if (retryableFactFailure && input.lease != null) return ProcessResult.RETRY("fact_check_upstream")
            // Invalid or failed responses must never prevent a fresh network attempt.
            if (cached == null && (!checkFacts || outputs.none { it.verdict == "not_checked" })) synchronized(cache) {
                if (current(input) && app.audioCoordinator.settings.config.value == service) {
                    cache[cacheKey] = System.currentTimeMillis() to json
                    while (cache.size > 32) cache.remove(cache.keys.first())
                }
            }
            notice(if (outputs.isEmpty()) "未提取到可核查陈述，不代表内容真实" else
                "已分析 ${outputs.size} 个观点候选，结果仅覆盖已提取的采样内容")
            return ProcessResult.DONE
        } catch (error: Exception) {
            if (error is CancellationException || error is MonitorInputInvalidated) throw error
            if (!current(input)) return ProcessResult.CANCELLED
            val retryable = if (error is HttpFailure) error.status in setOf(408, 429) || error.status in 500..599
                else error is java.io.IOException
            if (input.lease != null && retryable) return ProcessResult.RETRY(if (error is HttpFailure) "http_${error.status}" else "network")
            unchecked(input, if (retryable) "网络或服务暂不可用，核查未完成，请稍后重试" else "云端结果无法使用，核查未完成，请稍后重试")
            return ProcessResult.FAILED(if (error is HttpFailure) "http_${error.status}" else "invalid_response")
        } finally { connection = null; mutableState.update { it.copy(busy = false) } }
    }

    @Synchronized private fun commit(input: MonitorInput, outputs: List<MonitorFinding>, service: TextServiceConfig) {
        if (!current(input) || app.audioCoordinator.settings.config.value != service) return
        outputs.forEach(::publish)
        // Longitudinal findings are rebuilt from retained Room records by the exposure observer.
    }

    private suspend fun request(input: MonitorInput, endpoint: String, token: String, checkFacts: Boolean): JSONObject {
        val payload = JSONObject().put("version", 1).put("id", input.id).put("text", input.text)
            .put("kind", input.kind).put("captured_at", input.at).put("check_facts", checkFacts)
        if (input.image.isNotEmpty()) payload.put("image", input.image)
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 512 * 1024) { "当前画面过大，请缩小采样尺寸" }
        val http = (URL(endpoint.trimEnd('/') + "/monitor").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 15000; readTimeout = 135_000; instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
            setFixedLengthStreamingMode(bytes.size)
        }
        connection = http
        try {
            requireCurrentMonitorInput(current(input))
            http.outputStream.use { it.write(bytes) }
            if (http.responseCode != 200) throw HttpFailure(http.responseCode)
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
        synchronized(cache) { cache.clear() }; exposureJob?.cancel()
        mutableState.update { it.copy(findings = emptyList(), busy = false, notice = "材料已更新，旧核查与比对结果已清除") }
        prefs.edit().remove("findings").remove("arguments").apply()
        if (clearSemantics) {
            val next = Math.addExact(authorizationRevision, 1L)
            check(prefs.edit().putLong("authorization_revision", next).commit())
            authorizationRevision = next
            scope.launch { tasks.cancelAll(); app.repository.clearSemantics() }
        }
    }
    fun saveConfig(value: MonitorConfig) {
        val clean = value.bounded()
        val next = if (clean == config.value) authorizationRevision else Math.addExact(authorizationRevision, 1L)
        check(prefs.edit().putLong("authorization_revision", next).putString("config", JSONObject().apply {
            put("bubble", clean.bubble); put("cocoon", clean.cocoon); put("realtime", clean.realtime); put("visual", clean.visual)
            put("cooldown", clean.cooldownMinutes); put("alerts", clean.dailyAlerts); put("seconds", clean.displaySeconds)
            put("interval", clean.frameIntervalSeconds); put("quiet", clean.quiet)
            put("start", clean.quietStart); put("end", clean.quietEnd); put("research", clean.researchMode)
        }.toString()).commit())
        authorizationRevision = next
        mutableConfig.value = clean
    }
    private fun readConfig() = runCatching {
        val j = JSONObject(prefs.getString("config", "{}").orEmpty())
        MonitorConfig(j.optBoolean("bubble"), j.optBoolean("cocoon", true), j.optBoolean("realtime"), j.optBoolean("visual"),
            j.optInt("cooldown", 30), j.optInt("alerts", 6), j.optInt("seconds", 3), j.optInt("interval", 30),
            j.optBoolean("quiet"), j.optInt("start", 22), j.optInt("end", 8), j.optBoolean("research")).bounded()
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
    private fun readFindings(): List<MonitorFinding> = runCatching {
        val rows = JSONArray(prefs.getString("findings", "[]"))
        (0 until minOf(rows.length(), 80)).map { i -> rows.getJSONObject(i).let { j ->
            val s = j.getJSONArray("sources")
            MonitorFinding(j.getString("id"), j.getString("key"), j.getString("package"), j.getLong("at"),
                j.getString("kind"), j.getString("verdict"), j.getString("claim"), j.getString("explanation"),
                (0 until minOf(s.length(), 4)).map { n -> s.getJSONObject(n).let { WebEvidence(it.getString("url"), it.getString("title"), it.getString("quote"), it.getString("excerpt")) } },
                topic = j.optString("topic", ""), inputKind = j.optString("input", "text"))
        } }
    }.getOrDefault(emptyList()).filter { it.kind != "cocoon" }
}
