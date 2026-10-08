package com.emerge.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioPcmTest {
    @Test fun stereoDownmixPreservesTimingAndCancelsOppositeChannels() {
        val bytes = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(16384).putShort(16384)
            .putShort(16384).putShort(-16384)
            .putShort(-32768).putShort(-32768)
        bytes.flip()
        assertArrayEquals(floatArrayOf(0.5f, 0f, -1f), AudioPcm.mono(bytes, 2, AudioPcm.PCM_16_BIT), 0.00001f)
    }

    @Test fun floatInputReplacesNonFiniteValuesAndClampsPeaks() {
        val bytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(Float.NaN).putFloat(Float.POSITIVE_INFINITY).putFloat(2f).putFloat(-0.25f)
        bytes.flip()
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, -0.25f), AudioPcm.mono(bytes, 1, AudioPcm.PCM_FLOAT), 0.00001f)
    }

    @Test fun unsignedEightBitSilenceIsZero() {
        assertArrayEquals(floatArrayOf(-1f, 0f, 127f / 128), AudioPcm.mono(ByteBuffer.wrap(byteArrayOf(0, -128, -1)), 1, AudioPcm.PCM_8_BIT), 0.00001f)
    }

    @Test fun fiveMinuteLimitCutsTheActualAudioSampleCount() {
        assertEquals(8000, AudioPcm.limitedSamples(16000, 16000, 299500, 300000))
        assertEquals(0, AudioPcm.limitedSamples(16000, 16000, 300001, 300000))
        assertEquals(16000, AudioPcm.limitedSamples(16000, 16000, 0, 300000))
    }

    @Test fun codecOffsetAndLimitAreRespected() {
        val bytes = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(32767).putShort(16384).putShort(0).putShort(-32768)
        bytes.position(2); bytes.limit(6)
        assertArrayEquals(floatArrayOf(0.5f, 0f), AudioPcm.mono(bytes, 1, AudioPcm.PCM_16_BIT), 0.00001f)
    }
}
