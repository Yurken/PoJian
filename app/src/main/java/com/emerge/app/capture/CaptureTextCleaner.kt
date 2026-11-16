package com.emerge.app.capture

import com.emerge.app.audioanalysis.EndpointPolicy
import com.emerge.app.audioanalysis.TextServiceConfig
import com.emerge.app.monitoring.MonitorPolicy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONObject

/** The model can delete lines or known UI suffixes, never supply replacement prose. */
internal object CaptureCleanupProtocol {
    fun decode(text: String, json: JSONObject): String {
        require(json.keys().asSequence().toSet() == setOf("version", "input_hash", "drop_lines", "trim_suffixes"))
        require(json.opt("version") == 1 && json.opt("input_hash") == MonitorPolicy.hash(text))
        val lines = text.split('\n').toMutableList()
        val drops = json.getJSONArray("drop_lines")
        val trims = json.getJSONArray("trim_suffixes")
        require(drops.length() <= lines.size && trims.length() <= lines.size)
        val removed = mutableSetOf<Int>()
        var previous = -1
        repeat(drops.length()) {
            val index = drops.get(it)
            require(index is Int && index > previous && index in lines.indices)
            removed.add(index)
            previous = index
        }
        val seen = removed.toMutableSet()
        repeat(trims.length()) {
            val row = trims.getJSONObject(it)
            require(row.keys().asSequence().toSet() == setOf("line", "suffix"))
            val index = row.get("line")
            val suffix = row.get("suffix")
            require(index is Int && index in lines.indices && seen.add(index) && suffix is String &&
                PlatformTextCleaner.controlSuffix.matches(suffix) && lines[index].endsWith(suffix) &&
                suffix.length < lines[index].length)
            lines[index] = lines[index].removeSuffix(suffix).trimEnd()
        }
        return lines.filterIndexed { index, line -> index !in removed && line.isNotBlank() }.joinToString("\n")
    }
}

internal class CaptureTextCleaner(
    private val timeoutMillis: Long = 3000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val request: suspend (String, TextServiceConfig, () -> Boolean) -> JSONObject
) {
    private data class Entry(val text: String, val until: Long)
    private val cache = object : LinkedHashMap<String, Entry>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > 32
    }
    private val cooldowns = object : LinkedHashMap<String, Long>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > 16
    }
    private val slots = Semaphore(2)

    suspend fun clean(raw: String, config: TextServiceConfig, authorization: String, isCurrent: () -> Boolean): String {
        val local = PlatformTextCleaner.clean(raw)
        currentCoroutineContext().ensureActive()
        // Secure semantic mode performs local cleanup only: do not leak a plaintext preflight.
        if (com.emerge.app.monitoring.SecureChannelConfig.applies(config)) return local
        if (!config.enabled || config.token.isBlank() || local.isBlank() || local.length > 16000 ||
            local.count { it == '\n' } >= 256 || !isCurrent()) return local
        // Keep the local original intact. Avoid remote deletion suggestions over redacted text,
        // whose hash/line offsets would no longer describe that original.
        if (com.emerge.app.monitoring.CloudPrivacyStats.sanitize(local) != local) return local
        val scope = MonitorPolicy.hash("$authorization|${config.endpoint}|${config.token}")
        val key = MonitorPolicy.hash("$scope|$local")
        synchronized(cache) {
            cache[key]?.takeIf { clock() < it.until }?.let { return it.text }
            if (clock() < (cooldowns[scope] ?: 0)) return local
        }
        // Concurrent OCR and accessibility must not build an unbounded network queue.
        if (!slots.tryAcquire()) return local
        try {
            val cleaned = try {
                withTimeoutOrNull(timeoutMillis) {
                    if (!isCurrent()) return@withTimeoutOrNull null
                    CaptureCleanupProtocol.decode(local, request(local, config, isCurrent))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                null
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) return local
            synchronized(cache) {
                if (cleaned == null) cooldowns[scope] = clock() + 10_000
                else cache[key] = Entry(cleaned, clock() + 300_000)
            }
            return cleaned ?: local
        } finally { slots.release() }
    }
}

internal object CaptureCleanupClient {
    suspend fun request(text: String, config: TextServiceConfig, allowLocalHttp: Boolean,
        isCurrent: () -> Boolean,
        openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }): JSONObject =
        withContext(Dispatchers.IO) {
            require(config.enabled && config.token.isNotEmpty() && config.token.length <= 512 &&
                config.token.all { it.code in 33..126 })
            val endpoint = EndpointPolicy.validate(config.endpoint, allowLocalHttp)
            val bytes = JSONObject().put("version", 1).put("text", text).toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= 128 * 1024)
            currentCoroutineContext().ensureActive()
            if (!isCurrent()) throw IOException("Cleanup authorization changed")
            val connection = openConnection(URL("$endpoint/capture-cleanup")).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 1000
                readTimeout = 2800
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Authorization", "Bearer ${config.token}")
                setFixedLengthStreamingMode(bytes.size)
            }
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { connection.disconnect() }
                try {
                    continuation.context.ensureActive()
                    if (!isCurrent()) throw IOException("Cleanup authorization changed")
                    connection.outputStream.use { output ->
                        // Connecting/TLS may suspend the thread after the earlier consent check.
                        var offset = 0
                        while (offset < bytes.size) {
                            continuation.context.ensureActive()
                            if (!isCurrent()) throw IOException("Cleanup authorization changed")
                            val count = minOf(4096, bytes.size - offset)
                            output.write(bytes, offset, count)
                            offset += count
                        }
                    }
                    continuation.context.ensureActive()
                    if (!isCurrent()) throw IOException("Cleanup authorization changed")
                    if (connection.responseCode != 200 || connection.contentLengthLong > 32 * 1024)
                        throw IOException("Cleanup unavailable")
                    val output = connection.inputStream.use { input ->
                        val result = ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) {
                            continuation.context.ensureActive()
                            if (!isCurrent()) throw IOException("Cleanup authorization changed")
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (result.size() + count > 32 * 1024) throw IOException("Cleanup response too large")
                            result.write(buffer, 0, count)
                        }
                        result.toByteArray()
                    }
                    continuation.resume(JSONObject(output.toString(Charsets.UTF_8)))
                } catch (error: Exception) {
                    continuation.resumeWithException(error)
                } finally { connection.disconnect() }
            }
        }
}
