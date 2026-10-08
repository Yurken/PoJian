package com.emerge.app.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.CancellationSignal
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.ByteBuffer

/** Decodes only the first audio track. Video frames are never requested or allocated. */
internal class MediaAudioDecoder(private val context: Context) {
    private val cancellation = CancellationSignal()
    fun cancel() { cancellation.cancel() }

    suspend fun decode(uri: Uri, onSamples: suspend (FloatArray, Int, Long) -> Unit): Long {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var descriptor: android.content.res.AssetFileDescriptor? = null
        var durationMillis = 0L
        try {
            descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r", cancellation)
                ?: error("无法打开所选音频文件")
            val opened = descriptor
            if (opened.declaredLength < 0) extractor.setDataSource(opened.fileDescriptor)
            else extractor.setDataSource(opened.fileDescriptor, opened.startOffset, opened.declaredLength)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("文件中没有可转写的音轨")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("无法识别音频格式")
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(sampleRate in 8_000..192_000) { "不支持该音频采样率" }
            var encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
            if (mime == "audio/raw") {
                val bytes = ByteBuffer.allocate(256 * 1024)
                var frames = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    bytes.clear()
                    val size = extractor.readSampleData(bytes, 0)
                    if (size < 0) break
                    check(size <= bytes.capacity()) { "音频数据块过大" }
                    bytes.position(0); bytes.limit(size)
                    val offset = frames * 1000 / sampleRate
                    val mono = AudioPcm.mono(bytes, channels, encoding)
                    val count = AudioPcm.limitedSamples(mono.size, sampleRate, offset, MAX_DURATION_MILLIS)
                    if (count > 0) onSamples(if (count == mono.size) mono else mono.copyOf(count), sampleRate, offset)
                    frames += count
                    durationMillis = frames * 1000 / sampleRate
                    if (count < mono.size || durationMillis >= MAX_DURATION_MILLIS || !extractor.advance()) break
                }
                return durationMillis
            }
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var firstPresentationMicros: Long? = null
            var framesAtFormat = 0L
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val input = decoder.getInputBuffer(index) ?: error("无法读取音频解码缓冲区")
                        input.clear()
                        val size = extractor.readSampleData(input, 0)
                        val presentation = extractor.sampleTime
                        if (size < 0 || presentation > MAX_DURATION_MILLIS * 1000) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            check(extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "无法转写受保护的音轨" }
                            decoder.queueInputBuffer(index, 0, size, presentation.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = decoder.outputFormat
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(sampleRate in 8_000..192_000) { "不支持该音频采样率" }
                        framesAtFormat = 0L
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val output = decoder.getOutputBuffer(index) ?: error("音频解码失败")
                                output.position(info.offset); output.limit(info.offset + info.size)
                                if (firstPresentationMicros == null) firstPresentationMicros = info.presentationTimeUs.coerceAtLeast(0L)
                                val offset = ((info.presentationTimeUs - (firstPresentationMicros ?: 0L)) / 1000).coerceAtLeast(durationMillis)
                                val mono = AudioPcm.mono(output, channels, encoding)
                                val count = AudioPcm.limitedSamples(mono.size, sampleRate, offset, MAX_DURATION_MILLIS)
                                if (count > 0) onSamples(if (count == mono.size) mono else mono.copyOf(count), sampleRate, offset)
                                framesAtFormat += count
                                durationMillis = offset + count * 1000L / sampleRate
                                if (count < mono.size || durationMillis >= MAX_DURATION_MILLIS) outputDone = true
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        } finally { decoder.releaseOutputBuffer(index, false) }
                    }
                }
            }
            return durationMillis
        } finally {
            codec?.let { try { it.stop() } catch (_: Exception) { }; it.release() }
            extractor.release()
            descriptor?.close()
        }
    }

    companion object { const val MAX_DURATION_MILLIS = 5 * 60 * 1000L }
}
