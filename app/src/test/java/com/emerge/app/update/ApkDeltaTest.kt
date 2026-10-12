package com.emerge.app.update

import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ApkDeltaTest {
    @Test fun copyAndLiteralReconstructExactBytesAndRejectTrailingData() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val base = File(directory, "base").apply { writeText("0123456789") }
            val patch = File(directory, "patch")
            val output = File(directory, "output")
            DataOutputStream(patch.outputStream()).use {
                it.writeBytes("PJDIFF01"); it.writeLong(6)
                it.writeByte(1); it.writeLong(2); it.writeLong(3)
                it.writeByte(2); it.writeLong(3); it.writeBytes("abc"); it.writeByte(0)
            }
            ApkDelta.apply(base, patch, output)
            assertEquals("234abc", output.readText())
            patch.appendBytes(byteArrayOf(1))
            assertThrows(IllegalArgumentException::class.java) { ApkDelta.apply(base, patch, output) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun rejectsCopyOutsideBaseAndOversizedOutput() {
        val directory = kotlin.io.path.createTempDirectory().toFile()
        try {
            val base = File(directory, "base").apply { writeText("old") }
            val patch = File(directory, "patch")
            for (size in listOf(4L, UpdatePolicy.MAX_APK_BYTES + 1)) {
                DataOutputStream(patch.outputStream()).use {
                    it.writeBytes("PJDIFF01"); it.writeLong(size)
                    it.writeByte(1); it.writeLong(0); it.writeLong(4); it.writeByte(0)
                }
                assertThrows(IllegalArgumentException::class.java) { ApkDelta.apply(base, patch, File(directory, "out")) }
            }
        } finally { directory.deleteRecursively() }
    }
}
