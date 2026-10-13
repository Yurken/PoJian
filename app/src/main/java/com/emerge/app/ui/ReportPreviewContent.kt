package com.emerge.app.ui

import java.io.InputStream
import java.io.OutputStream
import java.io.FilterOutputStream
import java.io.IOException

internal class ReportPreviewTooLarge : IOException()

/** Bounded preview IO and lossless text pagination, independent of Android rendering. */
internal object ReportPreviewContent {
    const val MAX_BYTES = 32L * 1024 * 1024
    fun boundedOutput(output: OutputStream, limit: Long = MAX_BYTES, checkCancellation: () -> Unit = {}): OutputStream =
        object : FilterOutputStream(output) {
            private var total = 0L
            private fun reserve(count: Int) {
                checkCancellation()
                if (count.toLong() > limit - total) throw ReportPreviewTooLarge()
                total += count
            }
            override fun write(value: Int) { reserve(1); out.write(value) }
            override fun write(bytes: ByteArray, offset: Int, count: Int) {
                require(offset >= 0 && count >= 0 && offset <= bytes.size - count)
                reserve(count)
                out.write(bytes, offset, count)
            }
        }

    fun copy(input: InputStream, output: OutputStream, limit: Long = MAX_BYTES, checkCancellation: () -> Unit = {}): Long {
        val buffer = ByteArray(16_384)
        var total = 0L
        while (true) {
            checkCancellation()
            val count = input.read(buffer)
            if (count < 0) return total
            total += count
            if (total > limit) throw ReportPreviewTooLarge()
            output.write(buffer, 0, count)
        }
    }

    fun textPages(text: String, pageSize: Int = 6000): List<String> {
        require(pageSize >= 2)
        if (text.isEmpty()) return listOf("")
        val pages = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + pageSize, text.length)
            if (end < text.length) {
                val lineEnd = text.lastIndexOf('\n', end - 1)
                if (lineEnd >= start + pageSize / 2) end = lineEnd + 1
                if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
            }
            pages += text.substring(start, end)
            start = end
        }
        return pages
    }
}
