package com.emerge.app.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.emerge.app.EmergeApplication
import com.emerge.app.audioanalysis.AudioInsightCodec
import com.emerge.app.audioanalysis.TextServiceConfig
import com.emerge.app.data.AudioSource
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
    val sessions = combine(app.audioRepository.sessions, app.audioRepository.allTranscripts, coordinator.analyzing) { sessions, transcripts, analyzing ->
        val grouped = transcripts.groupBy { it.sessionId }
        val transcriptById = transcripts.associateBy { it.id }
        val sessionById = sessions.associateBy { it.id }
        sessions.map { session ->
            val report = session.analysisJson?.let(AudioInsightCodec::decode)
            AudioSessionUi(session.id, session.title,
                if (session.source == AudioSource.IMPORT) "导入音频" else "应用声音",
                session.startedAt, session.durationMillis,
                grouped[session.id].orEmpty().map { AudioSegmentUi(it.id, it.offsetStartMillis, it.offsetEndMillis, it.originalText, it.correctedText) },
                when (session.status) {
                    "created" -> "正在准备"; "recording" -> "正在记录"; "transcribing" -> "正在转写"
                    "completed" -> "已完成"; "failed" -> "处理失败"; else -> "已停止"
                }, session.errorMessage, report?.method, report?.summary,
                report?.claims.orEmpty().map { claim ->
                    val source = transcriptById[claim.segmentId]
                    val previous = claim.historySegmentId?.let(transcriptById::get)
                    val previousSession = previous?.sessionId?.let(sessionById::get)
                    AudioClaimUi(claim.claim, claim.evidence, claim.relation, claim.novelty,
                        claim.historyQuote?.let { quote ->
                            val time = previous?.offsetStartMillis?.div(1000)?.let { "%02d:%02d".format(it / 60, it % 60) }.orEmpty()
                            "历史原文 · ${previousSession?.title ?: "先前片段"} $time\n$quote"
                        }, source?.offsetStartMillis, source?.offsetEndMillis)
                }, session.id in analyzing)
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val runtimeUi = combine(runtime.state, coordinator.preparing, coordinator.lastText) { state, preparing, text ->
        val model = AudioModelUiState(when {
            state.isDownloading -> AudioModelStatus.DOWNLOADING
            state.modelInstalled -> AudioModelStatus.READY
            state.error != null -> AudioModelStatus.ERROR
            else -> AudioModelStatus.MISSING
        }, if (state.isDownloading) state.modelDownloadProgress else null,
            if (state.isDownloading) "中文语音模型约 31 MB，下载后离线使用" else state.error ?: "中文语音模型约 31 MB，下载后在本机转写")
        val task = when {
            preparing -> AudioTaskUiState(AudioTaskPhase.PREPARING, "正在准备")
            state.isCapturing -> AudioTaskUiState(AudioTaskPhase.CAPTURING, state.status, partialText = text, elapsedMillis = state.elapsedMillis)
            state.isImporting -> AudioTaskUiState(AudioTaskPhase.TRANSCRIBING, state.status, partialText = text, elapsedMillis = state.elapsedMillis)
            else -> null
        }
        AudioUiState(model, task, message = state.error ?: state.status)
    }
    val ui = combine(runtimeUi, sessions, config, coordinator.message, localMessage) { state, sessions, config, message, local ->
        state.copy(sessionCount = sessions.size, message = local ?: message ?: state.message,
            cloudLabel = if (config.enabled) "已启用 · 每天最多 ${config.dailyLimit} 次 · 只在你手动分析时发送文字" else "未启用 · 使用本地规则分析")
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AudioUiState())

    fun notify(value: String) { localMessage.value = value }
    private fun action(block: suspend () -> Unit) {
        localMessage.value = null; coordinator.clearMessage()
        viewModelScope.launch(Dispatchers.IO) {
            try { block() } catch (error: Exception) {
                if (error is CancellationException) throw error
                localMessage.value = error.message?.take(200) ?: "操作没有完成，请重试"
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
        check(app.audioRepository.editTranscript(id, text)) { "这条转写已删除" }
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
