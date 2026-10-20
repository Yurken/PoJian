package com.emerge.app.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class DemoSnapshotInstallerTest {
    private fun archive(vararg files: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().also { buffer ->
            ZipOutputStream(buffer).use { zip ->
                files.forEach { (name, content) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun inDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("demo-install-test").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun freshInstallRestoresDataAndNeverReseedsExistingDatabase() = inDirectory { root ->
        val bytes = archive("databases/zhiheng.db" to "snapshot",
            "shared_prefs/report_preferences.xml" to "<map/>")
        assertTrue(DemoSnapshotInstaller.install(root) { ByteArrayInputStream(bytes) })
        val database = File(root, "databases/zhiheng.db")
        assertEquals("snapshot", database.readText())
        assertEquals("<map/>", File(root, "shared_prefs/report_preferences.xml").readText())
        database.writeText("user changes")
        assertFalse(DemoSnapshotInstaller.install(root) { error("Must not reopen archive") })
        assertEquals("user changes", database.readText())
    }

    @Test fun invalidOrIncompleteArchiveDoesNotPublishDatabase() = inDirectory { root ->
        listOf(
            archive("databases/zhiheng.db" to "snapshot", "../escape" to "bad"),
            archive("shared_prefs/report_preferences.xml" to "<map/>"),
            archive("databases/zhiheng.db" to "snapshot", "files/speech-models/../secret" to "bad"),
            archive("databases/zhiheng.db-wal" to "unsafe")
        ).forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) {
                DemoSnapshotInstaller.install(root) { ByteArrayInputStream(bytes) }
            }
            assertFalse(File(root, "databases/zhiheng.db").exists())
            assertFalse(File(root, "demo-snapshot-staging").exists())
        }
        assertTrue(DemoSnapshotInstaller.install(root) {
            ByteArrayInputStream(archive("databases/zhiheng.db" to "recovered"))
        })
    }

    @Test fun preservesPreferencesAlreadyPresentBeforeDatabaseCreation() = inDirectory { root ->
        val preferences = File(root, "shared_prefs/capture.xml")
        preferences.parentFile!!.mkdirs()
        preferences.writeText("user preferences")
        DemoSnapshotInstaller.install(root) { ByteArrayInputStream(archive(
            "databases/zhiheng.db" to "snapshot", "shared_prefs/capture.xml" to "seed preferences")) }
        assertEquals("user preferences", preferences.readText())
    }
}
