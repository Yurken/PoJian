package com.emerge.app.audioanalysis

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.emerge.app.EmergeApplication
import com.emerge.app.audio.AudioRuntime
import com.emerge.app.audio.AudioRuntimeEvent
import com.emerge.app.data.AudioSessionStatus
import com.emerge.app.data.AudioSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Application-scoped collection keeps final segments durable while the Activity is backgrounded. */
class AudioCoordinator(private val app: EmergeApplication) {
    val runtime = AudioRuntime.get(app)
    val settings = AudioAnalysisSettings(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository get() = app.audioRepository
    private val generations = ConcurrentHashMap<String, Long>()
    private val writeMutex = Mutex()
    private val startMutex = Mutex()
    private val lifecycleLock = Any()
    private val startGeneration = AtomicLong()
    private val preparingState = MutableStateFlow(false)
    val preparing = preparingState.asStateFlow()
    private val messageState = MutableStateFlow<String?>(null)
    val message = messageState.asStateFlow()
    private val analyzingState = MutableStateFlow<Set<String>>(emptySet())
    val analyzing = analyzingState.asStateFlow()
    private val analysisJobs = ConcurrentHashMap<String, Job>()
    private val lastTextState = MutableStateFlow("")
    val lastText = lastTextState.asStateFlow()

    private val recovered = scope.async {
        repository.sessions.first().filter { it.status in setOf(AudioSessionStatus.CREATED, AudioSessionStatus.RECORDING, AudioSessionStatus.TRANSCRIBING) }
            .forEach { repository.updateSessionStatus(it.id, it.generation, AudioSessionStatus.FAILED,
                "上次处理已中断，已保存的转写仍可查看") }
    }
    init {
        scope.launch {
            runtime.events.collect { event ->
                writeMutex.withLock { when (event) {
                    is AudioRuntimeEvent.Transcript -> {
                        val batch = event.batch
                        val generation = generations[batch.sessionId] ?: return@withLock
                        batch.segments.forEach { segment ->
                            repository.appendTranscript(batch.sessionId, generation, segment.startMillis, segment.endMillis, segment.text)
                        }
                        lastTextState.value = batch.segments.lastOrNull()?.text.orEmpty()
                    }
                    is AudioRuntimeEvent.Completion -> {
                        val result = event.completion
                        val generation = generations[result.sessionId] ?: return@withLock
                        repository.updateSessionStatus(result.sessionId, generation, result.status, result.error, result.durationMillis)
                    }
                } }
            }
        }
    }

    private suspend fun prepareStart(block: suspend () -> Unit) {
        check(startMutex.tryLock()) { "正在准备上一段音频，请稍候" }
        preparingState.value = true
        try { block() } finally { preparingState.value = false; startMutex.unlock() }
    }
    private suspend fun ownedStart(block: suspend () -> Unit) = scope.async { prepareStart(block) }.await()

    suspend fun importAudio(uri: Uri) = ownedStart {
        val startVersion = startGeneration.get()
        recovered.await()
        checkReady()
        run {
            val title = runCatching { app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } }.getOrNull()?.take(150) ?: "导入音频"
            val session = repository.createSession("音频导入", AudioSource.IMPORT, title)
            generations[session.id] = session.generation
            lastTextState.value = ""
            repository.updateSessionStatus(session.id, session.generation, AudioSessionStatus.TRANSCRIBING)
            try { synchronized(lifecycleLock) {
                check(startGeneration.get() == startVersion) { "开始操作已取消" }
                runtime.importMedia(session.id, uri, title)
            } }
            catch (error: Exception) {
                if (startGeneration.get() != startVersion) repository.deleteSession(session.id)
                else repository.updateSessionStatus(session.id, session.generation, AudioSessionStatus.FAILED, "无法开始转写，请重试")
                throw error
            }
        }
    }

    suspend fun startPlayback(packageName: String, resultCode: Int, data: Intent) = ownedStart {
        val startVersion = startGeneration.get()
        recovered.await()
        checkReady()
        check(packageName in app.captureSettings.allowedPackages.value) { "请先在设置中选择要记录的应用" }
        run {
            val name = runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)
            val session = repository.createSession(packageName, AudioSource.PLAYBACK, name)
            generations[session.id] = session.generation
            lastTextState.value = ""
            repository.updateSessionStatus(session.id, session.generation, AudioSessionStatus.RECORDING)
            try { synchronized(lifecycleLock) {
                check(startGeneration.get() == startVersion && packageName in app.captureSettings.allowedPackages.value) { "开始操作已取消" }
                runtime.startPlayback(session.id, resultCode, data, packageName)
            } }
            catch (error: Exception) {
                if (startGeneration.get() != startVersion) repository.deleteSession(session.id)
                else repository.updateSessionStatus(session.id, session.generation, AudioSessionStatus.FAILED, "无法开始记录，请重新授权")
                throw error
            }
        }
    }

    private fun checkReady() {
        val state = runtime.state.value
        check(state.modelInstalled) { "请先下载中文语音模型" }
        check(!state.isImporting && !state.isCapturing && !state.isDownloading) { "等当前任务完成后再开始下一段" }
    }
    fun stop() { runtime.stop() }
    fun cancelPendingStart() { synchronized(lifecycleLock) { startGeneration.incrementAndGet() } }
    fun stopSession(id: String) {
        synchronized(lifecycleLock) {
            startGeneration.incrementAndGet()
            if (runtime.state.value.sessionId == id) runtime.stop()
        }
        cancelAnalysis(id)
    }
    fun cancelAnalysis(id: String) {
        analysisJobs.remove(id)?.cancel()
        analyzingState.update { it - id }
    }
    fun stopAll() {
        synchronized(lifecycleLock) { startGeneration.incrementAndGet(); runtime.stop() }
        cancelAllAnalyses()
    }
    fun cancelAllAnalyses() {
        analysisJobs.keys.toList().forEach(::cancelAnalysis)
    }

    suspend fun deleteSession(id: String) {
        stopSession(id)
        writeMutex.withLock { repository.deleteSession(id); generations.remove(id) }
    }

    @Synchronized fun analyze(id: String) {
        if (analysisJobs[id]?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            analyzingState.update { it + id }
            try {
                val session = repository.getSession(id) ?: return@launch
                val segments = repository.getTranscripts(id)
                val sessions = repository.sessions.first().associateBy { it.id }
                fun evidence(row: com.emerge.app.data.AudioTranscript): TranscriptEvidence {
                    val parent = sessions[row.sessionId]
                    return TranscriptEvidence(row.id, row.sessionId, row.displayText,
                        if (parent?.source == AudioSource.PLAYBACK) parent.startedAt + row.offsetStartMillis else row.createdAt,
                        row.offsetStartMillis, row.offsetEndMillis)
                }
                val current = segments.map(::evidence)
                val history = repository.getHistory(session.startedAt, id).map(::evidence)
                val config = settings.config.value
                val result = if (config.enabled) {
                    check(settings.reserveRequest()) { "今天云端分析次数已用完，可以在设置中关闭云端，改用本地分析" }
                    RemoteAudioInsights.analyze(config, current, history)
                } else withContext(Dispatchers.Default) { LocalAudioInsights.analyze(current, history) }
                ensureActive()
                if (settings.config.value != config) { messageState.value = "分析设置有变化，请重新生成回顾"; return@launch }
                if (!repository.setSessionAnalysis(id, session.generation, AudioInsightCodec.encode(result), session.contentVersion))
                    messageState.value = "转写已更新，请重新生成最新回顾"
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                messageState.value = error.message?.take(200) ?: "分析失败，请重试"
            } finally {
                if (analysisJobs.remove(id, currentCoroutineContext()[Job])) analyzingState.update { it - id }
            }
        }
        analysisJobs[id] = job
        job.start()
    }
    fun clearMessage() { messageState.value = null }
}
