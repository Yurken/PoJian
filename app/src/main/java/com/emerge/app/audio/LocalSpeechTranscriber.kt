package com.emerge.app.audio

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** All native calls belong to one consumer coroutine; no stream is shared across threads. */
internal class LocalSpeechTranscriber(
    directory: File,
    private val onSegment: suspend (AudioTranscriptSegment) -> Unit,
) : AutoCloseable {
    private val recognizer = OnlineRecognizer(config = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = File(directory, "encoder-epoch-99-avg-1.int8.onnx").absolutePath,
                decoder = File(directory, "decoder-epoch-99-avg-1.onnx").absolutePath,
                joiner = File(directory, "joiner-epoch-99-avg-1.int8.onnx").absolutePath,
            ),
            tokens = File(directory, "tokens.txt").absolutePath,
            numThreads = 2,
            modelType = "zipformer",
            provider = "cpu",
        ),
        enableEndpoint = true,
        endpointConfig = EndpointConfig(
            rule1 = EndpointRule(false, 2.4f, 0f),
            rule2 = EndpointRule(true, 1.2f, 0f),
            rule3 = EndpointRule(false, 0f, 20f),
        ),
    ))
    private var stream: OnlineStream = recognizer.createStream()
    private var startMillis: Long? = null
    private var endMillis: Long = 0L

    suspend fun accept(samples: FloatArray, sampleRate: Int, offsetMillis: Long) {
        currentCoroutineContext().ensureActive()
        if (samples.isEmpty()) return
        // A pause or discontinuity closes the old utterance before accepting new audio.
        if (startMillis != null && offsetMillis - endMillis > 500L) flush()
        if (startMillis == null) startMillis = offsetMillis
        endMillis = offsetMillis + samples.size * 1000L / sampleRate
        stream.acceptWaveform(samples, sampleRate)
        decodeReady()
        if (recognizer.isEndpoint(stream) || endMillis - (startMillis ?: endMillis) >= 20_000L) emitAndReset()
    }

    suspend fun flush() {
        if (startMillis == null) return
        stream.acceptWaveform(FloatArray(8000), 16000)
        stream.inputFinished()
        decodeReady()
        emitAndReset()
    }

    private suspend fun decodeReady() {
        while (recognizer.isReady(stream)) {
            currentCoroutineContext().ensureActive()
            recognizer.decode(stream)
        }
    }

    private suspend fun emitAndReset() {
        val result = recognizer.getResult(stream)
        val text = result.text.trim()
        val start = startMillis ?: endMillis
        if (text.isNotBlank()) {
            val firstToken = result.timestamps.firstOrNull()?.let { (it * 1000).toLong() } ?: 0L
            val lastToken = result.timestamps.lastOrNull()?.let { (it * 1000).toLong() + 250L }
            val speechStart = (start + firstToken).coerceIn(start, endMillis)
            val speechEnd = lastToken?.let { (start + it).coerceIn(speechStart, endMillis) } ?: endMillis
            onSegment(AudioTranscriptSegment(speechStart, speechEnd.coerceAtLeast(speechStart + 1L), text))
        }
        stream.release()
        stream = recognizer.createStream()
        startMillis = null
    }

    override fun close() {
        stream.release()
        recognizer.release()
    }
}
