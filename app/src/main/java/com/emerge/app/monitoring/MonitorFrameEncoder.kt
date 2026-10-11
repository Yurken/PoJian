package com.emerge.app.monitoring

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.roundToInt

internal data class EncodedMonitorFrame(val fingerprint: String, val image: String?)

/** Call off the main thread. The caller owns source; every intermediate bitmap stays here. */
internal object MonitorFrameEncoder {
    const val MAX_BYTES = 180 * 1024
    const val MAX_SIDE = 960

    suspend fun encode(source: Bitmap, bounds: Rect?, previousFingerprint: String?): EncodedMonitorFrame {
        currentCoroutineContext().ensureActive()
        val small = crop(source, bounds, MAX_SIDE)
        try {
            val fingerprint = fingerprint(small)
            if (fingerprint == previousFingerprint) return EncodedMonitorFrame(fingerprint, null)
            // Bounded fallback for detailed images; never upload an oversized JPEG.
            for (side in intArrayOf(MAX_SIDE, 768, 576)) {
                currentCoroutineContext().ensureActive()
                val candidate = if (side == MAX_SIDE) small else
                    scaledRegion(small, Rect(0, 0, small.width, small.height), side)
                try {
                    val out = ByteArrayOutputStream()
                    for (quality in intArrayOf(45, 35)) {
                        currentCoroutineContext().ensureActive()
                        out.reset()
                        check(candidate.compress(Bitmap.CompressFormat.JPEG, quality, out))
                        if (out.size() <= MAX_BYTES) {
                            return EncodedMonitorFrame(fingerprint, Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
                        }
                    }
                } finally { if (candidate !== small) candidate.recycle() }
            }
            error("画面尺寸超限，本次未上传")
        } finally { small.recycle() }
    }

    internal fun crop(source: Bitmap, bounds: Rect?, maxSide: Int): Bitmap {
        val area = bounds?.let(::Rect) ?: Rect(0, 0, source.width, source.height)
        require(maxSide > 0 && area.intersect(0, 0, source.width, source.height) && !area.isEmpty)
        return scaledRegion(source, area, maxSide)
    }

    private fun scaledRegion(source: Bitmap, area: Rect, maxSide: Int): Bitmap {
        val scale = minOf(1f, maxSide.toFloat() / maxOf(area.width(), area.height()))
        val result = Bitmap.createBitmap((area.width() * scale).roundToInt().coerceAtLeast(1),
            (area.height() * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            result.eraseColor(Color.BLACK)
            Canvas(result).drawBitmap(source, area, Rect(0, 0, result.width, result.height), Paint(Paint.FILTER_BITMAP_FLAG))
            return result
        } catch (error: Throwable) { result.recycle(); throw error }
    }

    private suspend fun fingerprint(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(ByteBuffer.allocate(8).putInt(bitmap.width).putInt(bitmap.height).array())
        // One row of scratch memory, rather than another full-size pixel buffer.
        val pixels = IntArray(bitmap.width)
        val bytes = ByteBuffer.allocate(bitmap.width * 4)
        val ints = bytes.asIntBuffer()
        for (y in 0 until bitmap.height) {
            if (y % 64 == 0) currentCoroutineContext().ensureActive()
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            ints.clear(); ints.put(pixels)
            digest.update(bytes.array())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
