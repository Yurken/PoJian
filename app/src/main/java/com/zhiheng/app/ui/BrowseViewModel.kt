package com.zhiheng.app.ui

import android.app.Application
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zhiheng.app.ZhiHengApplication
import com.zhiheng.app.capture.BrowseAccessibilityService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import android.net.Uri
import com.zhiheng.app.analysis.*
import com.zhiheng.app.data.BrowseEvent

class BrowseViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ZhiHengApplication
    val events = app.repository.recentEvents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val count = app.repository.count.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val allEvents = app.repository.allEvents.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val captureEnabled = app.captureSettings.enabled
    private val serviceState = MutableStateFlow(false)
    val diagnostics = app.captureDiagnostics.status
    val serviceEnabled = combine(serviceState, diagnostics) { enabled, status -> enabled || status.connected }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    private val messageState = MutableStateFlow<String?>(null)
    val message = messageState.asStateFlow()
    private val analysisState = MutableStateFlow(AnalysisState())
    val analysis = analysisState.asStateFlow()
    private var analysisJob: Job? = null
    var exportSnapshot: CognitiveReport? = null
    fun analyze(snapshot: List<BrowseEvent>, period: StatisticsPeriod, purpose: String) {
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            analysisState.value = AnalysisState(running = true)
            try {
                val result = withContext(Dispatchers.Default) {
                    CognitiveAnalyzer().analyze(snapshot.toList(), period, System.currentTimeMillis(), purpose = purpose.trim().ifBlank { "未填写" })
                }
                analysisState.value = AnalysisState(report = result)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                analysisState.value = AnalysisState(error = "分析失败，请重试：${error.javaClass.simpleName}")
            }
        }
    }
    fun exportReport(uri: Uri, report: CognitiveReport, pdf: Boolean) {
        viewModelScope.launch {
            analysisState.update { it.copy(exporting = true, exportMessage = null) }
            try {
                withContext(Dispatchers.IO) {
                    app.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        if (pdf) ReportExporter.writePdf(report, output)
                        else output.write(ReportExporter.text(report).toByteArray(Charsets.UTF_8))
                    } ?: error("无法打开保存位置")
                }
                analysisState.update { it.copy(exporting = false, exportMessage = "报告已保存到你选择的位置") }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                analysisState.update { it.copy(exporting = false, exportMessage = "保存失败，请重试：${error.javaClass.simpleName}") }
            }
        }
    }
    fun refreshPermission() {
        val manager = app.getSystemService(AccessibilityManager::class.java)
        val expected = ComponentName(app, BrowseAccessibilityService::class.java)
        val listed = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { ComponentName(it.resolveInfo.serviceInfo.packageName, it.resolveInfo.serviceInfo.name) == expected }
        val configured = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == expected } == true
        serviceState.value = listed || configured
    }
    fun setCapture(enabled: Boolean) = app.captureSettings.setEnabled(enabled)
    fun clearHistory() {
        setCapture(false)
        analysisJob?.cancel()
        analysisState.value = AnalysisState()
        exportSnapshot = null
        viewModelScope.launch {
            runCatching { app.repository.clear() }
                .onSuccess { messageState.value = "记录已清空，采集已暂停" }
                .onFailure { messageState.value = "清空失败，请重试" }
        }
    }
}
