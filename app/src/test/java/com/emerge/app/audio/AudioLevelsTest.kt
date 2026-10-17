package com.emerge.app.audio

import org.junit.Assert.*
import org.junit.Test

class AudioLevelsTest {
    @Test fun waveformTracksActualTimeBinsIncludingSilenceAndFullScale() {
        val samples = FloatArray(100) { if (it < 40) 0f else if (it < 60) 1f else -1f }
        assertEquals(listOf(0f, 0f, 1f, 1f, 1f), AudioLevels.waveform(samples))
        assertEquals(List(5) { 0f }, AudioLevels.waveform(FloatArray(100)))
        assertEquals(List(5) { 0f }, AudioLevels.waveform(floatArrayOf()))
    }

    @Test fun louderSamplesIncreaseLevelAndInvalidSamplesNeverEscapeBounds() {
        val quiet = AudioLevels.waveform(FloatArray(100) { .01f }).first()
        val loud = AudioLevels.waveform(FloatArray(100) { .5f }).first()
        assertTrue(quiet > 0f && loud > quiet && loud < 1f)
        assertTrue(AudioLevels.waveform(floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 3f, -3f, 0f))
            .all { it.isFinite() && it in 0f..1f })
    }

    @Test fun lateSamplesCannotReviveAPausedStoppedOrDifferentSession() {
        val state = AudioRuntimeState(sessionId = "current", isCapturing = true)
        val measured = state.withAudioSamples("current", FloatArray(100) { .5f })
        assertTrue(measured.audioLevels.all { it > 0f } && measured.audioLevels.size == 5)
        assertEquals(measured, measured.withAudioSamples("old", FloatArray(100)))
        assertTrue(measured.copy(isPaused = true).withAudioSamples("current", FloatArray(100) { 1f }).audioLevels.isEmpty())
        assertTrue(measured.copy(isCapturing = false).withAudioSamples("current", FloatArray(100) { 1f }).audioLevels.isEmpty())
    }
}
