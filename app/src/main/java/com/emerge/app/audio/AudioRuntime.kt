package com.emerge.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Application-scoped runtime: activity recreation never owns the audio or native decoder. */
class AudioRuntime private constructor(context: Context) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val models = SpeechModelStore(this.context)
    private val mutableState = MutableStateFlow(AudioRuntimeState(modelInstalled = models.isInstalled,
        status = if (models.isInstalled) "语音模型已就绪" else "下载语音模型后，可导入音频或录制应用声音"))
    val state: StateFlow<AudioRuntimeState> = mutableState.asStateFlow()
    private val mutableTranscripts = MutableSharedFlow<AudioTranscriptBatch>(extraBufferCapacity = 32)
    val transcripts: SharedFlow<AudioTranscriptBatch> = mutableTranscripts.asSharedFlow()
    private val mutableCompletions = MutableSharedFlow<AudioRuntimeCompletion>(extraBufferCapacity = 32)
    val completions: SharedFlow<AudioRuntimeCompletion> = mutableCompletions.asSharedFlow()
    private val mutableEvents = MutableSharedFlow<AudioRuntimeEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<AudioRuntimeEvent> = mutableEvents.asSharedFlow()
    private var downloadJob: Job? = null
    private var importJob: Job? = null
    @Volatile private var importDecoder: MediaAudioDecoder? = null
    private var captureJob: Job? = null
    @Volatile private var record: AudioRecord? = null
    private val capturePaused = AtomicBoolean(false)
    private val captureStopRequested = AtomicBoolean(false)
    private val captureFailure = AtomicReference<String?>(null)
    @Volatile private var captureSession: String? = null

    fun downloadModel() {
        if (state.value.isDownloading || downloadJob?.let { !it.isCompleted } == true || state.value.isImporting || state.value.isCapturing) return
        mutableState.update { it.copy(isDownloading = true, modelDownloadProgress = 0f, error = null, status = "正在下载语音模型（约 31 MB）") }
        downloadJob = scope.launch {
            try {
                models.download { progress -> mutableState.update { it.copy(modelDownloadProgress = progress) } }
                mutableState.update { it.copy(modelInstalled = true, status = "语音模型已就绪") }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(status = "已取消模型下载") }
                throw cancelled
            } catch (error: Throwable) {
                if (!currentCoroutineContext().isActive) {
                    mutableState.update { it.copy(status = "已取消模型下载", error = null) }
                } else {
                    mutableState.update { it.copy(modelInstalled = models.isInstalled, error = friendly(error), status = "模型下载未完成") }
                }
            } finally {
                mutableState.update { it.copy(isDownloading = false) }
            }
        }
    }

    fun cancelDownload() { downloadJob?.cancel(); models.abortDownload() }

    fun importMedia(sessionId: String, uri: Uri, sourceName: String) {
        checkReady()
        check(!state.value.isImporting && !state.value.isCapturing) { "请先结束当前音频任务" }
        val startedAt = System.currentTimeMillis()
        mutableState.update { it.copy(isImporting = true, sessionId = sessionId, elapsedMillis = 0, error = null, status = "正在转写音频，仅处理前 5 分钟") }
        importJob = scope.launch {
            var duration = 0L
            var terminal = "completed"
            var failure: String? = null
            val decoder = MediaAudioDecoder(context)
            importDecoder = decoder
            try {
                models.verifyInstalled()
                LocalSpeechTranscriber(models.directory) { segment ->
                    emitTranscript(AudioTranscriptBatch(sessionId, sourceName, null, "import", startedAt, duration, listOf(segment)))
                }.use { transcriber ->
                    duration = decoder.decode(uri) { samples, sampleRate, offset ->
                        duration = offset + samples.size * 1000L / sampleRate
                        transcriber.accept(samples, sampleRate, offset)
                        mutableState.update { if (it.sessionId == sessionId) it.copy(elapsedMillis = duration) else it }
                    }
                    transcriber.flush()
                }
            } catch (cancelled: CancellationException) {
                terminal = "cancelled"
                throw cancelled
            } catch (error: Throwable) {
                terminal = "failed"
                failure = friendly(error)
            } finally {
                if (importDecoder === decoder) importDecoder = null
                withContext(NonCancellable) {
                    emitCompletion(AudioRuntimeCompletion(sessionId, terminal, duration, failure))
                    mutableState.update { if (it.sessionId == sessionId) it.copy(modelInstalled = models.isInstalled, isImporting = false, error = failure, status = terminalText(terminal)) else it }
                }
            }
        }
    }

    fun cancelImport() { importJob?.cancel(); importDecoder?.cancel() }

    /** Must be called immediately after the fresh system MediaProjection consent result. */
    fun startPlayback(sessionId: String, resultCode: Int, data: Intent, selectedPackageName: String) {
        checkReady()
        check(!state.value.isImporting && !state.value.isCapturing) { "请先结束当前音频任务" }
        require(selectedPackageName != context.packageName) { "请选择要录制的应用" }
        // Resolve the chosen package before requesting a foreground service. Never capture all UIDs.
        context.packageManager.getApplicationInfo(selectedPackageName, 0)
        captureSession = sessionId
        capturePaused.set(false)
        captureStopRequested.set(false)
        captureFailure.set(null)
        mutableState.update { it.copy(sessionId = sessionId, isCapturing = true, isPaused = false, elapsedMillis = 0, error = null, status = "正在准备录音") }
        try {
            context.startForegroundService(Intent(context, ProjectionAudioService::class.java).apply {
                action = ProjectionAudioService.ACTION_START
                putExtra(ProjectionAudioService.EXTRA_SESSION, sessionId)
                putExtra(ProjectionAudioService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ProjectionAudioService.EXTRA_RESULT_DATA, data)
                putExtra(ProjectionAudioService.EXTRA_PACKAGE, selectedPackageName)
            })
        } catch (error: Throwable) {
            failPlayback(sessionId, friendly(error))
        }
    }

    fun pausePlayback() {
        if (!state.value.isCapturing) return
        capturePaused.set(true)
        // stop() releases an in-flight blocking AudioRecord.read immediately.
        try { record?.stop() } catch (_: Exception) { }
        mutableState.update { it.copy(isPaused = true, status = "录音已暂停") }
        ProjectionAudioService.refreshNotification(context)
    }

    fun resumePlayback() {
        if (!state.value.isCapturing || !capturePaused.get()) return
        capturePaused.set(false)
        mutableState.update { it.copy(isPaused = false, status = "正在录音") }
        ProjectionAudioService.refreshNotification(context)
    }

    fun stopPlayback() {
        captureStopRequested.set(true)
        try { record?.stop() } catch (_: Exception) { }
        val id = captureSession
        if (captureJob == null) {
            if (id != null) failPlayback(id, null, "cancelled")
            context.stopService(Intent(context, ProjectionAudioService::class.java))
        }
    }

    /** Hard cancellation for deletion: queued/final words must not re-create deleted data. */
    fun stop() {
        cancelImport()
        captureStopRequested.set(true)
        captureJob?.cancel()
        try { record?.stop() } catch (_: Exception) { }
        val id = captureSession
        if (captureJob == null && id != null) failPlayback(id, null, "cancelled")
        context.stopService(Intent(context, ProjectionAudioService::class.java))
    }

    internal fun attachPlayback(sessionId: String, packageName: String, projection: MediaProjection, onFinished: () -> Unit) {
        if (captureSession != sessionId || !state.value.isCapturing || captureJob?.isActive == true) {
            onFinished(); return
        }
        captureJob = scope.launch {
            val startedAt = System.currentTimeMillis()
            val startedClock = SystemClock.elapsedRealtime()
            var duration = 0L
            var terminal = "completed"
            var failure: String? = null
            var localRecord: AudioRecord? = null
            try {
                models.verifyInstalled()
                currentCoroutineContext().ensureActive()
                val app = context.packageManager.getApplicationInfo(packageName, 0)
                val name = context.packageManager.getApplicationLabel(app).toString()
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUid(app.uid)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val sampleRate = 16000
                val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "此设备无法录制应用声音，可改用导入音频" }
                val audioRecord = AudioRecord.Builder()
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(maxOf(minimum * 2, 16_000))
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
                localRecord = audioRecord
                record = audioRecord
                check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "录音启动失败，请重试或导入音频" }
                mutableState.update { if (it.sessionId == sessionId) it.copy(status = "正在录音") else it }
                LocalSpeechTranscriber(models.directory) { segment ->
                    emitTranscript(AudioTranscriptBatch(sessionId, name, packageName, "playback", startedAt, duration, listOf(segment)))
                }.use { transcriber ->
                    coroutineScope {
                        // Two seconds of PCM maximum. A slow device fails visibly instead of silently losing speech.
                        val chunks = Channel<PcmChunk>(10)
                        val reader = launch(Dispatchers.IO) {
                            try {
                                val buffer = ShortArray(3200)
                                var running = false
                                var pendingOffset = 0L
                                while (!captureStopRequested.get() && SystemClock.elapsedRealtime() - startedClock < MediaAudioDecoder.MAX_DURATION_MILLIS) {
                                    currentCoroutineContext().ensureActive()
                                    if (capturePaused.get()) {
                                        if (running) { try { audioRecord.stop() } catch (_: Exception) { }; running = false }
                                        delay(100)
                                        continue
                                    }
                                    if (!running) {
                                        audioRecord.startRecording()
                                        running = true
                                        pendingOffset = SystemClock.elapsedRealtime() - startedClock
                                    }
                                    val count = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                                    currentCoroutineContext().ensureActive()
                                    if (count <= 0) {
                                        if (captureStopRequested.get()) break
                                        if (capturePaused.get()) { running = false; continue }
                                        error("录音中断，请重新授权后重试")
                                    }
                                    if (capturePaused.get()) { running = false; continue }
                                    val offset = pendingOffset
                                    pendingOffset += count * 1000L / sampleRate
                                    val limited = AudioPcm.limitedSamples(count, sampleRate, offset, MediaAudioDecoder.MAX_DURATION_MILLIS)
                                    if (limited > 0) {
                                        val samples = FloatArray(limited) { buffer[it] / 32768f }
                                        check(chunks.trySend(PcmChunk(samples, offset)).isSuccess) { "设备转写速度不足，录音已停止。请改用导入音频。" }
                                    }
                                    if (limited < count) break
                                }
                            } finally { chunks.close() }
                        }
                        try {
                            for (chunk in chunks) {
                                currentCoroutineContext().ensureActive()
                                duration = chunk.offset + chunk.samples.size * 1000L / sampleRate
                                transcriber.accept(chunk.samples, sampleRate, chunk.offset)
                                mutableState.update { if (it.sessionId == sessionId) it.copy(elapsedMillis = duration) else it }
                            }
                            transcriber.flush()
                        } finally { reader.cancel() }
                    }
                }
            } catch (cancelled: CancellationException) {
                terminal = "cancelled"
                throw cancelled
            } catch (error: Throwable) {
                terminal = "failed"
                failure = friendly(error)
            } finally {
                if (captureFailure.get() != null) {
                    terminal = "failed"
                    failure = captureFailure.get()
                }
                if (record === localRecord) record = null
                localRecord?.let { try { it.stop() } catch (_: Exception) { }; it.release() }
                withContext(NonCancellable) {
                    emitCompletion(AudioRuntimeCompletion(sessionId, terminal, duration, failure))
                    mutableState.update { if (it.sessionId == sessionId) it.copy(modelInstalled = models.isInstalled, isCapturing = false, isPaused = false, error = failure, status = terminalText(terminal)) else it }
                    if (captureSession == sessionId) {
                        captureSession = null
                        captureJob = null
                    }
                    onFinished()
                }
            }
        }
    }

    internal fun projectionEnded(sessionId: String) {
        if (captureSession != sessionId) return
        captureFailure.set("录音授权已结束，请重新开始")
        if (captureJob == null) failPlayback(sessionId, captureFailure.get())
        captureJob?.cancel()
        try { record?.stop() } catch (_: Exception) { }
    }

    internal fun serviceDestroyed(sessionId: String?) {
        if (sessionId != null && captureSession == sessionId) {
            captureJob?.cancel()
            try { record?.stop() } catch (_: Exception) { }
            if (captureJob == null) failPlayback(sessionId, null, "cancelled")
        }
    }

    internal fun failPlayback(sessionId: String, error: String?, terminal: String = "failed") {
        mutableState.update { if (it.sessionId == sessionId) it.copy(isCapturing = false, isPaused = false, error = error, status = terminalText(terminal)) else it }
        if (captureJob == null) {
            val duration = if (state.value.sessionId == sessionId) state.value.elapsedMillis else 0L
            captureSession = null
            scope.launch { emitCompletion(AudioRuntimeCompletion(sessionId, terminal, duration, error)) }
        }
    }

    private fun checkReady() {
        check(models.isInstalled) { "请先下载中文语音模型" }
        check(!state.value.isDownloading) { "请等待模型下载完成" }
    }

    private suspend fun emitTranscript(batch: AudioTranscriptBatch) {
        mutableEvents.emit(AudioRuntimeEvent.Transcript(batch))
        mutableTranscripts.emit(batch)
    }

    private suspend fun emitCompletion(completion: AudioRuntimeCompletion) {
        mutableEvents.emit(AudioRuntimeEvent.Completion(completion))
        mutableCompletions.emit(completion)
    }

    private data class PcmChunk(val samples: FloatArray, val offset: Long)
    companion object {
        // Constructor immediately stores applicationContext; no Activity or Service is retained.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: AudioRuntime? = null
        fun get(context: Context): AudioRuntime = instance ?: synchronized(this) { instance ?: AudioRuntime(context).also { instance = it } }
        private fun friendly(error: Throwable): String = when (error) {
            is SecurityException -> "录音授权不可用，请重新授权；来源应用也可能禁止录制"
            is PackageManager.NameNotFoundException -> "所选应用已不可用，请重新选择"
            is UnsatisfiedLinkError -> "此设备不支持本机转写"
            else -> error.message?.take(200) ?: "处理失败，请重试"
        }
        private fun terminalText(status: String) = when (status) {
            "completed" -> "转写完成；未识别到人声时不生成文字"
            "cancelled" -> "已停止转写"
            else -> "转写未完成"
        }
    }
}
