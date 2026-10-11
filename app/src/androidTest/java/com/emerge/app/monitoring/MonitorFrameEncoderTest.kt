package com.emerge.app.monitoring

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class MonitorFrameEncoderTest {
    @Test fun exactPixelsSkipEncodingButOnePixelChangeDoesNot() = runBlocking(Dispatchers.Default) {
        val bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val first = MonitorFrameEncoder.encode(bitmap, null, null)
            assertNotNull(first.image)
            assertNull(MonitorFrameEncoder.encode(bitmap, null, first.fingerprint).image)
            bitmap.setPixel(150, 100, Color.BLACK)
            val changed = MonitorFrameEncoder.encode(bitmap, null, first.fingerprint)
            assertNotEquals(first.fingerprint, changed.fingerprint)
            assertNotNull(changed.image)
            // Manual sampling supplies no previous fingerprint.
            assertNotNull(MonitorFrameEncoder.encode(bitmap, null, null).image)
            assertFalse(bitmap.isRecycled)
        } finally { bitmap.recycle() }
    }

    @Test fun cropsOnlyTheTargetWindowAndDoesNotUpscale() = runBlocking(Dispatchers.Default) {
        val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.RED)
            val pixels = IntArray(200 * 100) { Color.BLUE }
            bitmap.setPixels(pixels, 0, 200, 200, 150, 200, 100)
            val encoded = MonitorFrameEncoder.encode(bitmap, Rect(200, 150, 400, 250), null)
            val bytes = Base64.decode(encoded.image, Base64.NO_WRAP)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try {
                assertEquals(200, decoded.width); assertEquals(100, decoded.height)
                assertTrue(Color.blue(decoded.getPixel(0, 0)) > 200)
                assertTrue(Color.red(decoded.getPixel(199, 99)) < 30)
            } finally { decoded.recycle() }
        } finally { bitmap.recycle() }
    }

    @Test fun landscapeAndPortraitStayWithinBounds() = runBlocking(Dispatchers.Default) {
        for ((width, height) in listOf(1920 to 1080, 1080 to 1920)) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                val encoded = MonitorFrameEncoder.encode(bitmap, null, null)
                val bytes = Base64.decode(encoded.image, Base64.NO_WRAP)
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                try {
                    assertEquals(960, maxOf(decoded.width, decoded.height))
                    assertEquals(540, minOf(decoded.width, decoded.height))
                    assertTrue(bytes.size <= MonitorFrameEncoder.MAX_BYTES)
                } finally { decoded.recycle() }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun detailedNoiseFallsBackToADecodableImageWithinByteBudget() = runBlocking(Dispatchers.Default) {
        val bitmap = Bitmap.createBitmap(960, 960, Bitmap.Config.ARGB_8888)
        try {
            val random = Random(1729)
            bitmap.setPixels(IntArray(960 * 960) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) },
                0, 960, 0, 0, 960, 960)
            val original = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 45, original)
            assertTrue("Fixture must exercise the overflow fallback", original.size() > MonitorFrameEncoder.MAX_BYTES)
            val encoded = MonitorFrameEncoder.encode(bitmap, null, null)
            val bytes = Base64.decode(encoded.image, Base64.NO_WRAP)
            assertTrue(bytes.size <= MonitorFrameEncoder.MAX_BYTES)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            try { assertTrue(decoded.width in 576..960); assertEquals(decoded.width, decoded.height) }
            finally { decoded.recycle() }
        } finally { bitmap.recycle() }
    }

    @Test fun invalidCropIsRejectedWithoutRecyclingCallerBitmap() = runBlocking(Dispatchers.Default) {
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        try {
            try {
                MonitorFrameEncoder.encode(bitmap, Rect(20, 20, 30, 30), null)
                fail("Non-intersecting crop must be rejected")
            } catch (_: IllegalArgumentException) { assertFalse(bitmap.isRecycled) }
        } finally { bitmap.recycle() }
    }
}
