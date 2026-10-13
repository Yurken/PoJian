package com.emerge.app.ui

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException

class ReportPreviewContentTest {
    @Test fun textPaginationPreservesEveryCharacterIncludingNewlinesAndEmoji() {
        val text = (1..900).joinToString("\r\n") { "观点 $it：岗位要求与个人处境 📖，保留原文。" }
        val pages = ReportPreviewContent.textPages(text, 127)
        assertTrue(pages.size > 1)
        assertEquals(text, pages.joinToString(""))
        assertTrue(pages.all { it.length <= 127 && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
    }

    @Test fun singleLongParagraphAndSurrogateAtBoundaryRemainReadable() {
        val text = "a".repeat(5999) + "📖" + "b".repeat(7001)
        val pages = ReportPreviewContent.textPages(text)
        assertEquals(text, pages.joinToString(""))
        assertEquals(5999, pages.first().length)
        assertTrue(pages[1].startsWith("📖"))
    }

    @Test fun documentCopyAcceptsExactLimitButRejectsOversizeBeforePublishing() {
        val bytes = ByteArray(16_385) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), ReportPreviewContent.copy(ByteArrayInputStream(bytes), output, bytes.size.toLong()))
        assertArrayEquals(bytes, output.toByteArray())
        assertThrows(ReportPreviewTooLarge::class.java) {
            ReportPreviewContent.copy(ByteArrayInputStream(bytes), ByteArrayOutputStream(), 16_384)
        }
    }

    @Test fun cancelledPreviewStopsCopyingBetweenChunks() {
        val output = ByteArrayOutputStream()
        var calls = 0
        assertThrows(CancellationException::class.java) {
            ReportPreviewContent.copy(ByteArrayInputStream(ByteArray(50_000)), output, checkCancellation = {
                if (++calls == 2) throw CancellationException()
            })
        }
        assertEquals(16_384, output.size())
    }

    @Test fun generatedFileCannotExceedLimitAcrossSingleAndBulkWrites() {
        val output = ByteArrayOutputStream()
        val bounded = ReportPreviewContent.boundedOutput(output, limit = 5)
        bounded.write(1)
        bounded.write(byteArrayOf(2, 3))
        bounded.write(byteArrayOf(0, 4, 5, 0), 1, 2)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), output.toByteArray())
        assertThrows(ReportPreviewTooLarge::class.java) { bounded.write(6) }
        assertThrows(ReportPreviewTooLarge::class.java) { bounded.write(byteArrayOf(6, 7)) }
        assertEquals(5, output.size())
    }

    @Test fun closingPreviewCancelsGeneratedFileBeforeNextWrite() {
        val output = ByteArrayOutputStream()
        var cancelled = false
        val bounded = ReportPreviewContent.boundedOutput(output, checkCancellation = {
            if (cancelled) throw CancellationException()
        })
        bounded.write(byteArrayOf(1, 2))
        cancelled = true
        assertThrows(CancellationException::class.java) { bounded.write(byteArrayOf(3, 4)) }
        assertArrayEquals(byteArrayOf(1, 2), output.toByteArray())
    }
}
