package com.emerge.app.data

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Installs an explicitly bundled local snapshot before Room or preferences are opened. */
internal object DemoSnapshotInstaller {
    private const val DATABASE = "databases/zhiheng.db"
    private val preferences = setOf("capture", "audio_analysis", "live_monitor", "report_preferences", "local_storage")

    @Synchronized
    fun install(dataDirectory: File, openSnapshot: () -> InputStream): Boolean {
        // Existing installs, including ones where the user deleted all records, always win.
        val database = File(dataDirectory, DATABASE)
        if (database.exists()) return false
        val staging = File(dataDirectory, "demo-snapshot-staging")
        check(!staging.exists() || staging.deleteRecursively())
        check(staging.mkdirs())
        try {
            val names = mutableSetOf<String>()
            var total = 0L
            ZipInputStream(openSnapshot()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    require(!entry.isDirectory && allowed(name) && names.add(name)) { "Invalid demo snapshot entry" }
                    require(names.size <= 50_000)
                    val target = File(staging, name)
                    check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                    target.outputStream().use { output ->
                        val buffer = ByteArray(65_536)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= 1_073_741_824L) { "Demo snapshot is too large" }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
            }
            require(DATABASE in names && File(staging, DATABASE).length() > 0) { "Missing demo database" }
            // Publish the database last: it is also the completion marker for an interrupted import.
            for (name in names.filter { it != DATABASE } + DATABASE) {
                val target = File(dataDirectory, name)
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                if (!target.exists()) check(File(staging, name).renameTo(target)) { "Demo import failed" }
            }
            return true
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun allowed(name: String): Boolean {
        if (name == DATABASE) return true
        if (preferences.any { name == "shared_prefs/$it.xml" }) return true
        return name.matches(Regex("no_backup/work-thumbnails/[a-f0-9-]{36}\\.jpg")) ||
            name.matches(Regex("files/speech-models/[a-zA-Z0-9_./-]+")) &&
            name.split('/').none { it == "." || it == ".." || it.isEmpty() }
    }
}
