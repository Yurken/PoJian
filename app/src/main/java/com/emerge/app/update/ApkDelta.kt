package com.emerge.app.update

import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile

// Reconstructs the exact signed APK. Never modifies the installed application.
internal object ApkDelta {
    fun apply(base: File, patch: File, output: File, checkpoint: () -> Unit = {}) {
        DataInputStream(patch.inputStream().buffered()).use { delta ->
            val magic = ByteArray(8).also { delta.readFully(it) }
            require(magic.contentEquals("PJDIFF01".toByteArray(Charsets.US_ASCII)))
            val expected = delta.readLong()
            require(expected in 1..UpdatePolicy.MAX_APK_BYTES)
            RandomAccessFile(base, "r").use { previous ->
                output.outputStream().buffered().use { result ->
                    var written = 0L
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkpoint()
                        val opcode = delta.readUnsignedByte()
                        if (opcode == 0) {
                            require(written == expected && delta.read() == -1)
                            break
                        }
                        val offset = if (opcode == 1) delta.readLong() else 0L
                        require(opcode == 1 || opcode == 2)
                        var remaining = delta.readLong()
                        require(remaining > 0 && remaining <= expected - written)
                        if (opcode == 1) {
                            require(offset >= 0 && offset <= previous.length() && remaining <= previous.length() - offset)
                            previous.seek(offset)
                        }
                        while (remaining > 0) {
                            checkpoint()
                            val count = minOf(buffer.size.toLong(), remaining).toInt()
                            if (opcode == 1) previous.readFully(buffer, 0, count) else delta.readFully(buffer, 0, count)
                            result.write(buffer, 0, count)
                            remaining -= count
                            written += count
                        }
                    }
                }
            }
        }
    }
}
