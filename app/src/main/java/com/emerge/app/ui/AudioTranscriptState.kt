package com.emerge.app.ui

import com.emerge.app.data.AudioSessionStatus

/** Success describes durable text, not merely completion of the recorder/decoder. */
internal fun audioTranscriptStatus(status: String, savedSegments: Int): String = when (status) {
    AudioSessionStatus.CREATED -> "正在准备"
    AudioSessionStatus.RECORDING -> "正在录音"
    AudioSessionStatus.TRANSCRIBING -> "正在转写"
    AudioSessionStatus.COMPLETED -> if (savedSegments > 0) "转写成功" else "未识别到文字"
    AudioSessionStatus.FAILED -> if (savedSegments > 0) "转写中断" else "转写失败"
    AudioSessionStatus.CANCELLED -> "已停止"
    else -> "状态未知"
}

internal val AudioSessionUi.savedSegmentCount: Int
    get() = segments.count { (it.correctedText ?: it.originalText).isNotBlank() }

internal fun audioTranscriptPreview(session: AudioSessionUi): String = buildString {
    for (segment in session.segments.sortedWith(compareBy({ it.startMillis }, { it.id }))) {
        val text = (segment.correctedText ?: segment.originalText).trim()
        if (text.isEmpty()) continue
        if (isNotEmpty()) append(' ')
        append(text.take(240 - length))
        if (length >= 240) break
    }
}
