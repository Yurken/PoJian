package com.emerge.app.ui

import android.app.Application
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.emerge.app.EmergeApplication
import com.emerge.app.analysis.*
import com.emerge.app.data.BrowseEvent
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CaptureApp(val packageName: String, val label: String)
data class ExportedReport(val uri: Uri, val pdf: Boolean)
sealed interface ReportPreviewSource {
    data class SavedFile(val file: ExportedReport) : ReportPreviewSource
    data class Generated(val report: CognitiveReport) : ReportPreviewSource
}

class BrowseViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EmergeApplication
    private val preferences = app.getSharedPreferences("report_preferences", Application.MODE_PRIVATE)
    val events = app.repository.recentEvents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val count = app.repository.count.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val allEvents = app.observedEvents.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val semanticsLoaded = MutableStateFlow(false)
    private val semanticsState = MutableStateFlow<List<EventSemantics>>(emptyList())
    val semantics = semanticsState.asStateFlow()
    val analysisStatuses = app.monitorTasks.observeInputStates()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val captureEnabled = app.captureSettings.enabled
    val allowedPackages = app.captureSettings.allowedPackages
    private val serviceState = MutableStateFlow(false)
    val diagnostics = app.captureDiagnostics.status
    val serviceEnabled = combine(serviceState, diagnostics) { enabled, status -> enabled || status.connected }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val messageState = MutableStateFlow<String?>(null)
    val message = messageState.asStateFlow()
    private val appsState = MutableStateFlow<List<CaptureApp>>(emptyList())
    val availableApps = appsState.asStateFlow()
    private val appsLoadingState = MutableStateFlow(true)
    val appsLoading = appsLoadingState.asStateFlow()
    private val analysisState = MutableStateFlow(AnalysisState(purpose = preferences.getString("purpose", "").orEmpty()))
    val analysis = analysisState.asStateFlow()
    private var lastExport: Pair<CognitiveReport, ExportedReport>? = null
    private val reportPreviewState = MutableStateFlow<ReportPreviewSource?>(null)
    val reportPreview = reportPreviewState.asStateFlow()
    fun previewReport(report: CognitiveReport) {
        val state = analysisState.value
        if (state.running || state.exporting || state.stale || state.report !== report) return
        reportPreviewState.value = lastExport?.takeIf { it.first === report }?.let {
            ReportPreviewSource.SavedFile(it.second)
        } ?: ReportPreviewSource.Generated(report)
    }
    fun dismissReportPreview() { reportPreviewState.value = null }
    private var period = StatisticsPeriod.DAY
    private var reportDay = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault()).toLocalDate()
    private var reportZone = ZoneId.systemDefault().id
    private var reportMinute = 0L
    private var generation = 0L
    private var analysisJob: Job? = null
    private var reportProgress: ReportAnalysisProgress? = null
    private var pendingAnalysis = false
    private var exportJob: Job? = null
    private var pendingMutations = 0
    private val mutationMutex = Mutex()
    private var exportGeneration = -1L
    var exportSnapshot: CognitiveReport? = null
        private set

    init {
        viewModelScope.launch {
            app.observedSemantics.collect {
                semanticsState.value = it
                semanticsLoaded.value = true
            }
        }
        viewModelScope.launch {
            combine(allEvents, semantics, semanticsLoaded) { events, extracted, loaded ->
                if (loaded) events to extracted else null
            }.filterNotNull().collect {
                invalidate("记录已更新，正在整理回顾", cancelAnalysis = false, clearReport = false)
                scheduleAnalysis()
            }
        }
        viewModelScope.launch {
            analysisStatuses.collect { statuses ->
                val state = analysisState.value
                val report = state.report
                if (report != null && !state.running && !state.stale) {
                    val updated = reportProgress?.applyTo(report, statuses) ?: report
                    if (updated !== report) {
                        generation++
                        invalidateExport()
                        analysisState.update { it.copy(report = updated, exporting = false, exportMessage = null) }
                    }
                }
            }
        }
        viewModelScope.launch {
            val recordedApps = allEvents.map { rows -> rows.asSequence().filter { it.sourceType != "audio_import" }
                .map(BrowseEvent::packageName).toSet() }.distinctUntilChanged().flowOn(Dispatchers.Default)
            combine(recordedApps, allowedPackages) { recorded, allowed -> recorded + allowed }
                .collectLatest { known ->
                    appsLoadingState.value = true
                    try {
                    appsState.value = withContext(Dispatchers.IO) {
                        val manager = app.packageManager
                        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        val launchable = manager.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }
                        (launchable + known).toSet().filter { it != app.packageName }.map { name ->
                            val label = runCatching { manager.getApplicationLabel(manager.getApplicationInfo(name, 0)).toString() }.getOrDefault(name)
                            CaptureApp(name, label)
                        }.sortedWith(compareBy<CaptureApp> { it.label.lowercase() }.thenBy { it.packageName })
                    }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        appsState.value = known.map { CaptureApp(it, it) }.sortedBy { it.packageName }
                        messageState.value = "应用列表加载失败，可继续管理已选应用和浏览记录"
                    } finally {
                        appsLoadingState.value = false
                    }
                }
        }
    }

    fun setPeriod(value: StatisticsPeriod) {
        if (period == value) return
        period = value
        invalidate("正在整理所选时段的回顾")
        scheduleAnalysis()
    }

    fun updatePurpose(value: String) {
        val purpose = value.take(120)
        if (analysisState.value.purpose == purpose) return
        preferences.edit().putString("purpose", purpose).apply()
        analysisState.update { it.copy(purpose = purpose) }
        invalidate("浏览目的已保存，正在更新回顾")
        scheduleAnalysis()
    }

    fun refreshReportWindow(now: Long) {
        val zone = ZoneId.systemDefault()
        val day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val minute = now / 60_000
        if (day != reportDay || zone.id != reportZone || (period.rollingHours > 0 && minute != reportMinute)) {
            reportMinute = minute
            reportDay = day
            reportZone = zone.id
            invalidate("正在更新当前时段的回顾")
            scheduleAnalysis()
        }
    }

    fun analyze(value: StatisticsPeriod) {
        period = value
        analysisState.update { it.copy(active = true) }
        invalidate("正在整理浏览回顾")
        scheduleAnalysis(immediate = true)
    }

    private fun invalidate(status: String, cancelAnalysis: Boolean = true, clearReport: Boolean = true) {
        generation++
        reportProgress = null
        if (cancelAnalysis) {
            analysisJob?.cancel()
            analysisJob = null
            pendingAnalysis = false
        }
        invalidateExport()
        analysisState.update { it.copy(report = if (clearReport) null else it.report,
            running = if (cancelAnalysis) false else it.running, exporting = false, error = null,
            stale = !clearReport && it.report != null,
            exportMessage = null, status = if (it.active) status else null) }
    }

    private fun invalidateExport() {
        if (reportPreviewState.value is ReportPreviewSource.Generated) reportPreviewState.value = null
        exportJob?.cancel()
        exportSnapshot = null
        exportGeneration = -1
        lastExport = null
    }

    private fun scheduleAnalysis(immediate: Boolean = false) {
        if (!analysisState.value.active || pendingMutations > 0 || !semanticsLoaded.value) return
        pendingAnalysis = true
        if (analysisJob?.isActive == true) return
        analysisJob = viewModelScope.launch {
            if (!immediate) delay(700)
            while (pendingAnalysis) {
            pendingAnalysis = false
            val version = generation
            val selectedPeriod = period
            val purpose = analysisState.value.purpose
            val snapshot = allEvents.value.toList()
            val semanticSnapshot = semantics.value.toList()
            val statusSnapshot = analysisStatuses.value.toMap()
            if (snapshot.isEmpty()) {
                analysisState.update { it.copy(running = false, report = null, status = "暂无浏览记录，开始采集后会自动更新") }
                return@launch
            }
            analysisState.update { it.copy(running = true, status = "正在整理浏览回顾", error = null) }
            try {
                val (content, progress) = withContext(Dispatchers.Default) {
                    val computationContext = currentCoroutineContext()
                    val report = CognitiveAnalyzer().analyze(snapshot, selectedPeriod, System.currentTimeMillis(),
                        purpose = purpose.trim().ifBlank { "未填写" }, checkCancellation = { computationContext.ensureActive() },
                        semantics = semanticSnapshot, analysisStatuses = statusSnapshot)
                    report to ReportAnalysisProgress(snapshot, report)
                }
                ensureActive()
                // Progress may have changed while the content was being analyzed.
                val result = progress.applyTo(content, analysisStatuses.value)
                reportProgress = if (generation == version) progress else null
                analysisState.update { it.copy(running = false, report = result, stale = generation != version,
                    status = if (generation == version) "回顾已更新" else "发现新记录，正在更新回顾") }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (generation == version) analysisState.update { it.copy(running = false, error = "生成回顾失败，请重试", status = "回顾未完成") }
            }
            }
        }
    }

    fun prepareExport(report: CognitiveReport): Boolean {
        val state = analysisState.value
        if (state.running || state.exporting || state.stale || state.report !== report) return false
        reportPreviewState.value = null
        exportSnapshot = report
        exportGeneration = generation
        analysisState.update { it.copy(exporting = true, exportMessage = "请选择保存位置") }
        return true
    }

    fun cancelExport() {
        exportSnapshot = null
        exportGeneration = -1
        analysisState.update { it.copy(exporting = false, exportMessage = null) }
    }

    fun exportReport(uri: Uri, pdf: Boolean) {
        val report = exportSnapshot ?: run {
            analysisState.update { it.copy(exporting = false, exportMessage = "导出已失效，请重新导出最新回顾") }
            return
        }
        val version = exportGeneration
        if (version != generation || analysisState.value.report !== report) { cancelExport(); return }
        exportJob = viewModelScope.launch {
            analysisState.update { it.copy(exporting = true, exportMessage = "正在生成导出文件") }
            try {
                // Finish generation before opening the target; invalidation can stop a pending export.
                val bytes = withContext(Dispatchers.Default) {
                    val computationContext = currentCoroutineContext()
                    if (pdf) ByteArrayOutputStream().use { output ->
                        ReportExporter.writePdf(report, output) { computationContext.ensureActive() }
                        output.toByteArray()
                    }
                    else ReportExporter.text(report).toByteArray(Charsets.UTF_8)
                }
                ensureActive()
                if (version != generation) return@launch
                withContext(Dispatchers.IO) {
                    ensureActive()
                    app.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        ensureActive()
                        var offset = 0
                        while (offset < bytes.size) {
                            ensureActive()
                            val length = minOf(16_384, bytes.size - offset)
                            output.write(bytes, offset, length)
                            offset += length
                        }
                    } ?: error("无法打开保存位置")
                }
                if (version == generation) {
                    analysisState.update { it.copy(exporting = false, exportMessage = "回顾已保存到所选位置") }
                    // Publish only after the destination stream has closed successfully. Preview reads that file.
                    val file = ExportedReport(uri, pdf)
                    lastExport = report to file
                    reportPreviewState.value = ReportPreviewSource.SavedFile(file)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (version == generation) analysisState.update { it.copy(exporting = false, exportMessage = "保存失败，请重试") }
            } finally {
                if (version == generation) {
                    exportSnapshot = null
                    exportGeneration = -1
                }
            }
        }
    }

    fun refreshPermission() {
        val manager = app.getSystemService(AccessibilityManager::class.java)
        val expected = ComponentName(app.packageName, app.packageName + ".capture.BrowseAccessibilityService")
        val listed = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == expected }
        val configured = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == expected } == true
        serviceState.value = listed || configured
    }

    fun setCapture(enabled: Boolean) {
        if (enabled && pendingMutations > 0) {
            messageState.value = "正在更新浏览记录，完成后可重新开启采集"
            return
        }
        if (enabled && allowedPackages.value.isEmpty()) {
            messageState.value = "请先选择至少一个要记录的应用"
            return
        }
        app.captureSettings.setEnabled(enabled)
        messageState.value = if (enabled) "已开启，只记录所选应用" else "采集已暂停"
    }

    fun setAllowedPackages(packages: Set<String>) {
        if (packages == allowedPackages.value) return
        app.audioCoordinator.stopAll()
        setCapture(false)
        invalidate("应用范围已更新，正在整理回顾")
        app.captureSettings.setAllowedPackages(packages)
        messageState.value = "应用范围已保存，采集已暂停"
        scheduleAnalysis()
    }

    fun deleteEvent(id: Long) {
        allEvents.value.firstOrNull { it.id == id }?.audioSessionId?.let(app.audioCoordinator::stopSession)
        mutateHistory("记录已删除", { records -> records.none { it.id == id } }) { app.repository.deleteEvent(id) }
    }
    fun deletePackage(packageName: String) {
        app.audioCoordinator.stopAll()
        setCapture(false)
        mutateHistory("该应用的记录已删除，采集已暂停", { records -> records.none { it.packageName == packageName } }) { app.repository.deletePackage(packageName) }
    }
    fun clearHistory() {
        app.audioCoordinator.stopAll()
        setCapture(false)
        mutateHistory("记录已清空，采集已暂停", { it.isEmpty() }) { app.repository.clear() }
    }

    private fun mutateHistory(success: String, removed: (List<BrowseEvent>) -> Boolean, action: suspend () -> Unit) {
        pendingMutations++
        invalidate("记录正在删除")
        viewModelScope.launch {
            try {
                mutationMutex.withLock {
                action()
                allEvents.first { removed(it) }
                messageState.value = success
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                messageState.value = "删除失败，请重试"
            } finally {
                pendingMutations--
                // Repository emissions invalidate any previous computation; use its latest retained data.
                scheduleAnalysis()
            }
        }
    }
}
