package com.emerge.app.monitoring

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the bundled native Chinese model with generated pixels, without downloading a model. */
@RunWith(AndroidJUnit4::class)
class ScreenTextRecognizerTest {
    @Test fun chineseSubtitlesAndNegationBecomeQueryableText() = runBlocking(Dispatchers.Default) {
        val bitmap = image(listOf("招聘岗位正在减少", "因为岗位同比减少20%", "这不代表所有岗位都减少"))
        val recognizer = ScreenTextRecognizer()
        try {
            val text = recognizer.recognize(bitmap, null).filterNot(Char::isWhitespace)
            assertTrue(text, text.contains("招聘岗位正在减少"))
            assertTrue(text, text.contains("20%"))
            // Assert the negative qualifier independently from the final verb: native OCR
            // can misread individual glyphs, so this integration check is not an accuracy benchmark.
            assertTrue(text, text.contains("这不代表所有岗位"))
            assertFalse(bitmap.isRecycled)
        } finally { recognizer.close(); bitmap.recycle() }
    }

    @Test fun cropExcludesTextOutsideTheSelectedWindow() = runBlocking(Dispatchers.Default) {
        val bitmap = image(listOf("OUTSIDE WINDOW", "只识别当前窗口文字"))
        val recognizer = ScreenTextRecognizer()
        try {
            val text = recognizer.recognize(bitmap, Rect(0, 130, bitmap.width, 280)).filterNot(Char::isWhitespace)
            assertTrue(text, text.contains("只识别当前窗口文字"))
            assertFalse(text.contains("OUTSIDE"))
        } finally { recognizer.close(); bitmap.recycle() }
    }

    @Test fun emptyFrameDoesNotInventText() = runBlocking(Dispatchers.Default) {
        val bitmap = image(emptyList())
        val recognizer = ScreenTextRecognizer()
        try { assertEquals("", recognizer.recognize(bitmap, null)) }
        finally { recognizer.close(); bitmap.recycle() }
    }

    private fun image(lines: List<String>): Bitmap = Bitmap.createBitmap(1000, 500, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 54f }
        val canvas = Canvas(this)
        lines.forEachIndexed { index, line -> canvas.drawText(line, 40f, 100f + index * 120, paint) }
    }
}
