package com.emerge.app.data

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.GZIPOutputStream

class ResearchAssetInstallerTest {
    private val directory = Files.createTempDirectory(File("build").toPath(), "research-install-test-").toFile()
    @After fun cleanup() { directory.walkBottomUp().forEach { check(it.delete()) } }
    private val bytes = "SQLite test fixture without personal records".toByteArray()
    private fun metadata(content: ByteArray = bytes) = JSONObject().apply {
        put("format_version", 1); put("synthetic", true); put("real_participants", 0)
        put("dataset_id", "research-controlled-v2"); put("database_bytes", content.size)
        put("database_sha256", MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) })
    }
    private fun zipped(content: ByteArray = bytes): ByteArrayInputStream {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(content) }
        return ByteArrayInputStream(output.toByteArray())
    }

    @Test fun verifiedInstallIsReusedWithoutRewritingAndPersonalDatabaseUntouched() {
        val personal = File(directory, "zhiheng.db").apply { writeText("personal sentinel") }
        val installer = ResearchAssetInstaller()
        val file = installer.install(directory, metadata(), { zipped() })
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(file, installer.install(directory, metadata(), { error("Must not reopen compressed asset") }))
        assertEquals("personal sentinel", personal.readText())
        assertFalse(directory.listFiles()!!.any { it.extension == "part" })
    }

    @Test fun checksumMismatchAndOversizeLeaveNoPartialDatabase() {
        for (content in listOf("wrong".toByteArray(), bytes + bytes)) {
            assertThrows(IllegalStateException::class.java) { ResearchAssetInstaller().install(directory, metadata(), { zipped(content) }) }
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun cancellationRemovesPartialAndRetrySucceeds() {
        assertThrows(CancellationException::class.java) {
            ResearchAssetInstaller().install(directory, metadata(), { zipped() }, { throw CancellationException() })
        }
        assertTrue(directory.listFiles()!!.isEmpty())
        assertArrayEquals(bytes, ResearchAssetInstaller().install(directory, metadata(), { zipped() }).readBytes())
    }

    @Test fun corruptedInstalledCopyIsRecoveredOnNextProcessAndOnlyOldResearchCopyEvicted() {
        val first = ResearchAssetInstaller().install(directory, metadata(), { zipped() })
        first.writeBytes(ByteArray(bytes.size))
        assertArrayEquals(bytes, ResearchAssetInstaller().install(directory, metadata(), { zipped() }).readBytes())
        val changed = bytes + "v2".toByteArray()
        val next = ResearchAssetInstaller().install(directory, metadata(changed), { zipped(changed) })
        assertFalse(first.exists())
        assertArrayEquals(changed, next.readBytes())
    }
}
