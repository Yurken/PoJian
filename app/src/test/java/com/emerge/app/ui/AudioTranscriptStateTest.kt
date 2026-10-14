package com.emerge.app.ui

import com.emerge.app.data.AudioSessionStatus
import org.junit.Assert.*
import org.junit.Test

class AudioTranscriptStateTest {
    private fun session(segments: List<AudioSegmentUi>) = AudioSessionUi("audio-1", "录音", "应用录音", 1000, 5000, segments)

    @Test fun completedRecorderWithoutDurableTextDoesNotReportTranscriptionSuccess() {
        val empty = session(listOf(AudioSegmentUi(1, 0, 1000, " \n")))
        assertEquals(0, empty.savedSegmentCount)
        assertEquals("未识别到文字", audioTranscriptStatus(AudioSessionStatus.COMPLETED, empty.savedSegmentCount))
        assertTrue(audioTranscriptPreview(empty).isEmpty())
        val saved = empty.copy(segments = listOf(AudioSegmentUi(2, 1000, 2000, "真正保存的转写")))
        assertEquals("转写成功", audioTranscriptStatus(AudioSessionStatus.COMPLETED, saved.savedSegmentCount))
    }

    @Test fun partialTextDoesNotHideFailureOrPretendTheTaskCompleted() {
        val partial = session(listOf(AudioSegmentUi(1, 0, 1000, "中断前已保存的文字")))
        assertEquals("转写中断", audioTranscriptStatus(AudioSessionStatus.FAILED, partial.savedSegmentCount))
        assertEquals("转写失败", audioTranscriptStatus(AudioSessionStatus.FAILED, 0))
        assertEquals("正在录音", audioTranscriptStatus(AudioSessionStatus.RECORDING, partial.savedSegmentCount))
        assertEquals("正在转写", audioTranscriptStatus(AudioSessionStatus.TRANSCRIBING, partial.savedSegmentCount))
        assertEquals("已停止", audioTranscriptStatus(AudioSessionStatus.CANCELLED, partial.savedSegmentCount))
        assertEquals("中断前已保存的文字", audioTranscriptPreview(partial))
    }

    @Test fun persistedSegmentsArePreviewedInMediaOrderAndUseSavedCorrections() {
        val stored = session(listOf(AudioSegmentUi(2, 2000, 3000, "末段"),
            AudioSegmentUi(1, 0, 1000, "首段原始识别", "已修订首段")))
        assertEquals(2, stored.savedSegmentCount)
        assertEquals("已修订首段 末段", audioTranscriptPreview(stored))
        assertEquals("首段原始识别", stored.segments.last().originalText)
    }
}
