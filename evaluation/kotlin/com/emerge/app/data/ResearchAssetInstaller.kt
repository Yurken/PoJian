package com.emerge.app.data

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** Verified, bounded, atomic installation of a bundled fictional corpus. No personal DB paths. */
class ResearchAssetInstaller {
    private var verified: Triple<String, Long, Long>? = null

    fun install(directory: File, metadata: JSONObject, input: () -> InputStream, checkCancellation: () -> Unit = {}): File {
        require(metadata.getInt("format_version") == 1 && metadata.getBoolean("synthetic") &&
            metadata.getInt("real_participants") == 0 && metadata.getString("dataset_id") == "research-controlled-v2")
        val bytes = metadata.getLong("database_bytes")
        val hash = metadata.getString("database_sha256")
        require(bytes in 1..256L * 1024 * 1024 && hash.matches(Regex("[a-f0-9]{64}")))
        check(directory.isDirectory || directory.mkdirs())
        val destination = File(directory, "research-v2-${hash.take(16)}.sqlite")
        fun signature() = Triple(destination.absolutePath, destination.length(), destination.lastModified())
        if (destination.isFile && destination.length() == bytes &&
            (verified == signature() || digest(destination, checkCancellation) == hash)) {
            verified = signature()
            return destination
        }
        // This optional corpus requires already-free space; do not evict other apps' caches to install it.
        @Suppress("UsableSpace")
        val availableBytes = directory.usableSpace
        check(availableBytes >= bytes + 8L * 1024 * 1024) { "Insufficient storage for research database" }
        val temporary = File(directory, destination.name + ".part")
        try {
            val checksum = MessageDigest.getInstance("SHA-256")
            var written = 0L
            GZIPInputStream(input()).use { zipped ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkCancellation()
                        val count = zipped.read(buffer)
                        if (count < 0) break
                        written += count
                        check(written <= bytes) { "Oversized research database" }
                        output.write(buffer, 0, count)
                        checksum.update(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(written == bytes && checksum.digest().hex() == hash) { "Research database checksum mismatch" }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            verified = signature()
            // Only obsolete copies owned by this installer; never zhiheng.db or the old demo DB.
            directory.listFiles()?.filter { it != destination && it.name.matches(Regex("research-v2-[a-f0-9]{16}\\.sqlite")) }
                ?.forEach { it.delete() }
            return destination
        } finally { temporary.delete() }
    }

    private fun digest(file: File, checkCancellation: () -> Unit): String {
        val checksum = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkCancellation()
                val count = stream.read(buffer)
                if (count < 0) break
                checksum.update(buffer, 0, count)
            }
        }
        return checksum.digest().hex()
    }
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}
