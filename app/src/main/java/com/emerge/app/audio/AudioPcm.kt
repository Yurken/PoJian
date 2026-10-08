package com.emerge.app.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Stateless conversion keeps at most one codec output buffer in memory. */
internal object AudioPcm {
    const val PCM_16_BIT = 2
    const val PCM_8_BIT = 3
    const val PCM_FLOAT = 4

    fun mono(buffer: ByteBuffer, channelCount: Int, encoding: Int): FloatArray {
        require(channelCount in 1..32) { "不支持该音频声道数量" }
        val bytesPerSample = when (encoding) {
            PCM_16_BIT -> 2
            PCM_8_BIT -> 1
            PCM_FLOAT -> 4
            else -> error("不支持该音频采样格式")
        }
        val input = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        val frames = input.remaining() / (bytesPerSample * channelCount)
        return FloatArray(frames) {
            var sum = 0f
            repeat(channelCount) {
                sum += when (encoding) {
                    PCM_16_BIT -> input.short / 32768f
                    PCM_8_BIT -> ((input.get().toInt() and 0xff) - 128) / 128f
                    else -> input.float.let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
                }
            }
            (sum / channelCount).coerceIn(-1f, 1f)
        }
    }

    fun limitedSamples(sampleCount: Int, sampleRate: Int, offsetMillis: Long, maxMillis: Long): Int {
        require(sampleRate > 0)
        val available = ((maxMillis - offsetMillis).coerceAtLeast(0L) * sampleRate / 1000L)
        return minOf(sampleCount.toLong(), available).toInt()
    }
}
