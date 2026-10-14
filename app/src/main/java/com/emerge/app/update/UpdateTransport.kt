package com.emerge.app.update

import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal fun interface UpdateTransport {
    suspend fun read(url: String, maxBytes: Long, onChunk: (ByteArray, Int, Long) -> Unit)
}

internal class HttpsUpdateTransport(
    private val connect: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection }
) : UpdateTransport {
    override suspend fun read(url: String, maxBytes: Long, onChunk: (ByteArray, Int, Long) -> Unit) = withContext(Dispatchers.IO) {
        var target = UpdatePolicy.httpsUrl(url)
        for (redirect in 0..5) {
            currentCoroutineContext().ensureActive()
            val connection = connect(target)
            try {
                connection.apply {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("User-Agent", "PoJian-Updater/1.0")
                    setRequestProperty("Accept-Encoding", "identity")
                }
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    check(redirect < 5) { "Too many update redirects" }
                    val location = connection.getHeaderField("Location") ?: error("Missing redirect")
                    target = UpdatePolicy.httpsUrl(URI(target).resolve(location).toString())
                    continue
                }
                check(status == HttpURLConnection.HTTP_OK) { "Update HTTP $status" }
                val length = connection.contentLengthLong
                check(length <= maxBytes) { "Update response too large" }
                var received = 0L
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        currentCoroutineContext().ensureActive()
                        received += count
                        check(received <= maxBytes && (length < 0 || received <= length)) { "Update response too large" }
                        onChunk(buffer, count, length)
                    }
                }
                check(length < 0 || received == length) { "Incomplete update response" }
                currentCoroutineContext().ensureActive()
                return@withContext
            } finally {
                connection.disconnect()
            }
        }
    }
}
