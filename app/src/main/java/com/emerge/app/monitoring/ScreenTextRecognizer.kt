package com.emerge.app.monitoring

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor

/** Bundled Chinese model. Invoke off the main thread; the caller retains source ownership. */
internal class ScreenTextRecognizer : AutoCloseable {
    private var recognizer: TextRecognizer? = null
    private var running = 0
    private var closed = false

    suspend fun recognize(source: Bitmap, bounds: Rect?): String = withTimeout(15_000) {
        suspendCancellableCoroutine { continuation ->
            val image = MonitorFrameEncoder.crop(source, bounds, 1600)
            val client = try { acquire() } catch (error: Throwable) {
                image.recycle()
                throw error
            }
            val task = try { client.process(InputImage.fromBitmap(image, 0)) } catch (error: Throwable) {
                image.recycle()
                release()
                throw error
            }
            // Cancellation cannot stop a native Task. Retain its pixels until completion and
            // reject another request while it runs, even after the coroutine's timeout.
            task.addOnCompleteListener(Executor { it.run() }) { completed ->
                val result = runCatching {
                    check(completed.isSuccessful) { completed.exception?.message ?: "画面文字识别未完成" }
                    completed.result.textBlocks.flatMap { it.lines }.map { it.text.trim() }
                        .filter(String::isNotEmpty).distinct().joinToString("\n").take(6000)
                }
                image.recycle()
                release()
                continuation.resumeWith(result)
            }
        }
    }

    @Synchronized private fun acquire(): TextRecognizer {
        check(!closed && running == 0)
        val client = recognizer ?: TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            .also { recognizer = it }
        running++
        return client
    }

    @Synchronized private fun release() {
        running--
        if (closed && running == 0) { recognizer?.close(); recognizer = null }
    }

    @Synchronized override fun close() {
        closed = true
        if (running == 0) { recognizer?.close(); recognizer = null }
    }
}
