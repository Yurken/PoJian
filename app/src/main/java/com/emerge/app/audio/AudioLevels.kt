package com.emerge.app.audio

import kotlin.math.log10
import kotlin.math.sqrt

internal object AudioLevels {
    /** Five equal time bins of PCM RMS, shown on a -60..0 dBFS scale (not calibrated sound pressure). */
    fun waveform(samples: FloatArray): List<Float> = List(5) { bin ->
        val start = samples.size * bin / 5
        val end = samples.size * (bin + 1) / 5
        var energy = 0.0
        for (index in start until end) {
            val value = samples[index].let { if (it.isFinite()) it.coerceIn(-1f, 1f).toDouble() else 0.0 }
            energy += value * value
        }
        if (end == start || energy == 0.0) 0f
        else ((20 * log10(sqrt(energy / (end - start))) + 60) / 60).toFloat().coerceIn(0f, 1f)
    }
}

internal fun AudioRuntimeState.withAudioSamples(id: String, samples: FloatArray): AudioRuntimeState =
    if (sessionId != id) this else copy(audioLevels = if (isCapturing && !isPaused) AudioLevels.waveform(samples) else emptyList())
