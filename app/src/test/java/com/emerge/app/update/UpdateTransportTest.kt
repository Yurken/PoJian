package com.emerge.app.update

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class UpdateTransportTest {
    private class Response(private val code: Int = 200, private val data: ByteArray = "abc".toByteArray(),
        private val length: Long = data.size.toLong(), private val location: String? = null,
        private val stream: InputStream = ByteArrayInputStream(data)) : HttpURLConnection(URL("https://example.com/update")) {
        var closed = false
        override fun getResponseCode() = code
        override fun getContentLengthLong() = length
        override fun getHeaderField(name: String) = if (name == "Location") location else null
        override fun getInputStream() = stream
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() = Unit
    }

    @Test fun readsBoundedResponseAndAlwaysDisconnects() = runBlocking {
        val response = Response()
        var result = ""
        HttpsUpdateTransport { response }.read("https://example.com", 3) { bytes, count, length ->
            result += String(bytes, 0, count)
            assertEquals(3L, length)
        }
        assertEquals("abc", result)
        assertTrue(response.closed)
        assertFalse(response.instanceFollowRedirects)
    }

    @Test fun rejectsOversizedUnknownLengthTruncatedAndErrorResponses() {
        listOf(Response(), Response(length = -1), Response(length = 5), Response(code = 500), Response(code = 206)).forEach { response ->
            assertThrows(Exception::class.java) { runBlocking {
                HttpsUpdateTransport { response }.read("https://example.com", 2) { _, _, _ -> }
            } }
            assertTrue(response.closed)
        }
        val truncated = Response(length = 5)
        assertThrows(Exception::class.java) { runBlocking {
            HttpsUpdateTransport { truncated }.read("https://example.com", 10) { _, _, _ -> }
        } }
        assertTrue(truncated.closed)
    }

    @Test fun supportsRelativeHttpsRedirectButRejectsHttpDowngrade() = runBlocking {
        val redirect = Response(code = 302, location = "/apk")
        val final = Response()
        val urls = mutableListOf<String>()
        HttpsUpdateTransport { urls += it; if (urls.size == 1) redirect else final }
            .read("https://example.com/meta", 10) { _, _, _ -> }
        assertEquals(listOf("https://example.com/meta", "https://example.com/apk"), urls)
        assertTrue(redirect.closed && final.closed)
        val insecure = Response(code = 302, location = "http://example.com/apk")
        assertThrows(Exception::class.java) { runBlocking {
            HttpsUpdateTransport { insecure }.read("https://example.com/meta", 10) { _, _, _ -> }
        } }
        assertTrue(insecure.closed)
    }

    @Test fun limitsRedirectLoops() {
        var count = 0
        assertThrows(Exception::class.java) { runBlocking {
            HttpsUpdateTransport { count++; Response(code = 307, location = "/again") }
                .read("https://example.com", 10) { _, _, _ -> }
        } }
        assertEquals(6, count)
    }

    @Test fun cancellationDoesNotDeliverChunksOrLeaveConnectionOpen() {
        val job = Job()
        val input = object : ByteArrayInputStream("abc".toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                job.cancel()
                return super.read(bytes, offset, length)
            }
        }
        val response = Response(stream = input)
        var chunks = 0
        assertThrows(CancellationException::class.java) { runBlocking(job) {
            HttpsUpdateTransport { response }.read("https://example.com", 10) { _, _, _ -> chunks++ }
        } }
        assertEquals(0, chunks)
        assertTrue(response.closed)
    }
}
