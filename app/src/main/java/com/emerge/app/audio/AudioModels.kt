package com.emerge.app.audio

/** Offsets are positions in the source media, in milliseconds, never decoding wall time. */
data class AudioTranscriptSegment(val startMillis: Long, val endMillis: Long, val text: String)

data class AudioTranscriptBatch(
    val sessionId: String,
    val sourceName: String,
    val sourcePackage: String?,
    val inputKind: String,
    val startEpochMillis: Long,
    val durationMillis: Long,
    val segments: List<AudioTranscriptSegment>,
)

data class AudioRuntimeState(
    val modelInstalled: Boolean = false,
    val modelDownloadProgress: Float = 0f,
    val isDownloading: Boolean = false,
    val isImporting: Boolean = false,
    val isCapturing: Boolean = false,
    val isPaused: Boolean = false,
    val sessionId: String? = null,
    val elapsedMillis: Long = 0L,
    val status: String = "下载中文语音模型后，可导入音频或转写应用声音",
    val error: String? = null,
    val audioLevels: List<Float> = emptyList(),
)

/** Terminal events let the database finish even a session that contained no speech. */
data class AudioRuntimeCompletion(val sessionId: String, val status: String, val durationMillis: Long, val error: String? = null)

/** A single ordered stream prevents a terminal state from overtaking its final transcript. */
sealed interface AudioRuntimeEvent {
    data class Transcript(val batch: AudioTranscriptBatch) : AudioRuntimeEvent
    data class Completion(val completion: AudioRuntimeCompletion) : AudioRuntimeEvent
}
