package com.emerge.app.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.emerge.app.EmergeApplication
import com.emerge.app.audioanalysis.TextServiceConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class AudioViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as EmergeApplication
    private val coordinator = app.audioCoordinator
    val runtime = coordinator.runtime
    val config = coordinator.settings.config
    private val localMessage = MutableStateFlow<String?>(null)
    val alert = combine(localMessage, coordinator.message) { local, shared -> local ?: shared }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val sourceSessions = app.audioRepository.sessions.distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000, replayExpirationMillis = 0), replay = 1)
    private val storedSessions = flow {
        // The cache belongs to this subscription and is released when the content flow stops.
        val reports = AudioSessionReportCache()
        emitAll(combine(sourceSessions,
            app.audioRepository.allTranscripts.distinctUntilChanged()) { sessions, transcripts ->
            prepareAudioSessions(sessions, transcripts, reports.update(sessions))
        })
    }.flowOn(Dispatchers.Default)
    val sessions = combine(storedSessions, coordinator.analyzing,
        runtime.state.map { state -> state.sessionId.takeIf { state.isCapturing && state.isPaused } }
            .distinctUntilChanged()) { content, analyzing, pausedSessionId ->
        projectAudioSessions(content, analyzing, pausedSessionId)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val sessionCount = sourceSessions.map { it.size }.distinctUntilChanged()
    private val runtimeUi = combine(runtime.state, coordinator.preparing, coordinator.lastText) { state, preparing, text ->
        val model = AudioModelUiState(when {
            state.isDownloading -> AudioModelStatus.DOWNLOADING
            state.modelInstalled -> AudioModelStatus.READY
            state.error != null -> AudioModelStatus.ERROR
            else -> AudioModelStatus.MISSING
        }, if (state.isDownloading) state.modelDownloadProgress else null,
            if (state.isDownloading) "中文语音模型约 31 MB，下载后离线转写" else state.error ?: "中文语音模型约 31 MB，下载后离线转写")
        val task = when {
            preparing -> AudioTaskUiState(AudioTaskPhase.PREPARING, "正在准备")
            state.isCapturing -> AudioTaskUiState(AudioTaskPhase.CAPTURING, state.status, partialText = text,
                elapsedMillis = state.elapsedMillis, audioLevels = state.audioLevels)
            state.isImporting -> AudioTaskUiState(AudioTaskPhase.TRANSCRIBING, state.status, partialText = text, elapsedMillis = state.elapsedMillis)
            else -> null
        }
        AudioUiState(model, task, message = state.error ?: state.status)
    }
    val ui = combine(runtimeUi, sessionCount, config, coordinator.message, localMessage) { state, count, config, message, local ->
        state.copy(sessionCount = count, message = local ?: message ?: state.message,
            cloudLabel = if (config.enabled) "已启用" else "未启用 · 使用本地识别")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AudioUiState())

    fun notify(value: String) { localMessage.value = value }
    private fun action(block: suspend () -> Unit) {
        localMessage.value = null; coordinator.clearMessage()
        viewModelScope.launch(Dispatchers.IO) {
            try { block() } catch (error: Exception) {
                if (error is CancellationException) throw error
                localMessage.value = error.message?.take(200) ?: "操作未完成，请重试"
            }
        }
    }
    fun downloadModel() { localMessage.value = null; runtime.downloadModel() }
    fun importAudio(uri: Uri) = action { coordinator.importAudio(uri) }
    fun startPlayback(packageName: String, resultCode: Int, data: Intent) = action { coordinator.startPlayback(packageName, resultCode, data) }
    fun stopTask() {
        coordinator.cancelPendingStart()
        if (runtime.state.value.isCapturing) runtime.stopPlayback() else runtime.cancelImport()
    }
    fun analyze(id: String) { localMessage.value = null; coordinator.clearMessage(); coordinator.analyze(id) }
    fun editSegment(id: Long, text: String) = action {
        val row = app.audioRepository.getTranscript(id) ?: return@action
        coordinator.cancelAnalysis(row.sessionId)
        check(app.audioRepository.editTranscript(id, text)) { "该转写不存在或已删除" }
    }
    fun deleteSession(id: String) = action { coordinator.deleteSession(id) }
    fun saveConfig(value: TextServiceConfig, onSaved: () -> Unit, onError: (String) -> Unit) {
        viewModelScope.launch {
            try {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    coordinator.settings.save(value, app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
                }
                coordinator.cancelAllAnalyses()
                onSaved()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                onError(error.message ?: "保存失败，请重试")
            }
        }
    }
}
