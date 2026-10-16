package com.emerge.app.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.emerge.app.analysis.CognitiveAnalyzer
import com.emerge.app.analysis.CognitiveReport
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.analysis.ReportExporter
import com.emerge.app.data.BrowseEvent
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*

internal data class SwissReportRequest(val events: List<BrowseEvent>, val semantics: List<EventSemantics>,
    val period: StatisticsPeriod, val now: Long, val purpose: String = "",
    val analysisStatuses: Map<Long, String> = emptyMap())

internal fun buildSwissReport(request: SwissReportRequest, checkCancellation: () -> Unit = {}): CognitiveReport =
    CognitiveAnalyzer().analyze(request.events, request.period, request.now, semantics = request.semantics,
        purpose = request.purpose.trim().ifBlank { "未填写" }, analysisStatuses = request.analysisStatuses,
        checkCancellation = checkCancellation)

internal data class PendingSwissExport(val request: SwissReportRequest, val report: CognitiveReport, val pdf: Boolean)

/** The picker and a write belong to the report session, including across Activity recreation. */
internal class SwissReportExportSession {
    private var request: SwissReportRequest? = null
    private var generation = 0L
    var pending by mutableStateOf<PendingSwissExport?>(null)
        private set
    var exporting by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set

    fun updateRequest(value: SwissReportRequest): Boolean {
        if (request == value) return false
        val interrupted = pending != null || exporting
        generation++
        request = value
        pending = null
        exporting = false
        status = if (interrupted) "内容已更新，请重新导出" else null
        return true
    }

    fun prepare(value: SwissReportRequest, report: CognitiveReport, pdf: Boolean): Boolean {
        if (request != value || pending != null || exporting) return false
        pending = PendingSwissExport(value, report, pdf)
        status = null
        return true
    }

    fun takePending(destinationSelected: Boolean = false): PendingSwissExport? = pending.also {
        pending = null
        if (it == null && destinationSelected && status == null) status = "导出已失效，请重新导出"
    }

    fun begin(snapshot: PendingSwissExport): Long? {
        if (request != snapshot.request) return null
        exporting = true
        return generation
    }

    fun isCurrent(snapshot: PendingSwissExport, version: Long): Boolean =
        generation == version && request == snapshot.request

    fun finish(version: Long, message: String? = null) {
        if (generation != version) return
        exporting = false
        if (message != null) status = message
    }

    fun close() {
        generation++
        request = null
        pending = null
        exporting = false
        status = null
    }
}

internal class SwissReportExportViewModel : ViewModel() {
    val session = SwissReportExportSession()
    private var exportJob: Job? = null

    fun updateRequest(request: SwissReportRequest) {
        if (session.updateRequest(request)) exportJob?.cancel()
    }

    fun export(uri: Uri?, resolver: ContentResolver) {
        val snapshot = session.takePending(destinationSelected = uri != null) ?: return
        if (uri == null) return
        val version = session.begin(snapshot) ?: return
        exportJob = viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) {
                    val task = currentCoroutineContext()
                    if (snapshot.pdf) ByteArrayOutputStream().use { output ->
                        ReportExporter.writePdf(snapshot.report, output) { task.ensureActive() }
                        output.toByteArray()
                    } else swissReportJson(snapshot.report).toByteArray(Charsets.UTF_8)
                }
                ensureActive()
                if (!session.isCurrent(snapshot, version)) return@launch
                withContext(Dispatchers.IO) {
                    ensureActive()
                    resolver.openOutputStream(uri, "wt")?.use { output ->
                        var offset = 0
                        while (offset < bytes.size) {
                            ensureActive()
                            val length = minOf(16_384, bytes.size - offset)
                            output.write(bytes, offset, length)
                            offset += length
                        }
                    } ?: error("No destination")
                }
                ensureActive()
                session.finish(version, "已保存")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { session.finish(version, "保存失败，请重试") }
            finally { session.finish(version) }
        }
    }

    fun close() {
        session.close()
        exportJob?.cancel()
        exportJob = null
    }
}

internal fun swissReportDuration(report: CognitiveReport): String =
    if (report.observedDurationMillis == 0L && report.unknownDurationRecords > 0) "时长未统计"
    else formatDuration(report.observedDurationMillis)
