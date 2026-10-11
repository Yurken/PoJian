package com.emerge.app.audio

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class AudioPipelineInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Explicit network smoke test; run only on a disposable QA device. */
    @Test fun hostedModelDownloadsAndVerifies() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("downloadSpeechModel") == "true")
        val models = SpeechModelStore(context)
        var lastProgress = 0f
        models.download { progress ->
            assertTrue(progress in lastProgress..1f)
            lastProgress = progress
        }
        assertEquals(1f, lastProgress, 0f)
        assertTrue(models.isInstalled)
        models.verifyInstalled()
        assertFalse(File(context.filesDir, "speech-models/${SpeechModelStore.MODEL_ID}.part").exists())
    }

    @Test fun wavTrackDecodesInBoundedBuffersWithSourceOffsets() = runBlocking {
        val rate = 16000
        val samples = 8000
        val wav = File(context.cacheDir, "pcm-decoder-test.wav")
        val bytes = ByteBuffer.allocate(44 + samples * 4).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(bytes.capacity() - 8).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
            .put("data".toByteArray()).putInt(samples * 4)
        repeat(samples) { bytes.putShort(16384).putShort(16384) }
        wav.writeBytes(bytes.array())
        try {
            var totalSamples = 0
            var lastOffset = -1L
            val duration = MediaAudioDecoder(context).decode(Uri.fromFile(wav)) { mono, sampleRate, offset ->
                assertEquals(rate, sampleRate)
                assertTrue(offset >= lastOffset)
                assertTrue(mono.size <= 256 * 1024)
                assertTrue(mono.all { kotlin.math.abs(it - 0.5f) < 0.0001f })
                totalSamples += mono.size
                lastOffset = offset
            }
            assertEquals(samples, totalSamples)
            assertEquals(500L, duration)
        } finally { wav.delete() }
    }

    /** Run with public fixtures as documented in docs/ASR-VALIDATION.md. No network or mic. */
    @Test fun publicChineseSampleProducesFinalTextAndMediaOffsets() = runBlocking {
        val models = SpeechModelStore(context)
        val sample = File(context.filesDir, "audio-test-sample.wav")
        assumeTrue("Install the public model/sample fixtures to run native ASR verification", models.isInstalled && sample.isFile)
        models.verifyInstalled()
        val segments = mutableListOf<AudioTranscriptSegment>()
        LocalSpeechTranscriber(models.directory) { segments += it }.use { transcriber ->
            val duration = MediaAudioDecoder(context).decode(Uri.fromFile(sample)) { mono, rate, offset ->
                transcriber.accept(mono, rate, offset)
            }
            transcriber.flush()
            assertTrue(duration in 5000..6000)
            assertFalse(segments.isEmpty())
            val transcript = segments.joinToString("") { it.text }
            assertTrue(transcript.contains("研究感兴趣"))
            assertTrue(segments.all { it.startMillis >= 0 && it.endMillis > it.startMillis && it.endMillis <= duration })
        }
    }

    @Test fun aacTrackReachesEndOfStreamAndProducesSpeech() = runBlocking {
        val models = SpeechModelStore(context)
        val sample = File(context.filesDir, "audio-test-sample.m4a")
        assumeTrue("Install the public AAC/model fixtures to run codec ASR verification", models.isInstalled && sample.isFile)
        val segments = mutableListOf<AudioTranscriptSegment>()
        LocalSpeechTranscriber(models.directory) { segments += it }.use { transcriber ->
            val duration = MediaAudioDecoder(context).decode(Uri.fromFile(sample)) { mono, rate, offset ->
                transcriber.accept(mono, rate, offset)
            }
            transcriber.flush()
            assertTrue(duration in 5000..6500)
            assertTrue(segments.joinToString("") { it.text }.contains("研究感兴趣"))
        }
    }
}
