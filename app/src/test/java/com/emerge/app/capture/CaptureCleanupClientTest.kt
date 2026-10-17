package com.emerge.app.capture

import com.emerge.app.audioanalysis.TextServiceConfig
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CaptureCleanupClientTest {
    private val config = TextServiceConfig(true, "https://example.org", "token")
    private open class Connection : HttpURLConnection(URL("https://example.org")) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode() = 200
        override fun getOutputStream() = sent
        override fun getInputStream(): InputStream = ByteArrayInputStream("{}".toByteArray())
    }

    @Test fun requestUsesOnlyAuthorizedGatewayWithoutRedirects() = runBlocking {
        val connection = Connection()
        CaptureCleanupClient.request("原文", config, false, { true }) { url ->
            assertEquals("https://example.org/capture-cleanup", url.toString()); connection
        }
        assertEquals("Bearer token", connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.sent.toString("UTF-8").contains("原文"))
        assertTrue(connection.disconnected)
    }

    @Test fun unknownAndDeclaredOversizeResponsesAreBounded() = runBlocking {
        for (declared in listOf(true, false)) {
            val connection = object : Connection() {
                override fun getContentLengthLong() = if (declared) 32769L else -1L
                override fun getInputStream(): InputStream {
                    check(!declared)
                    return ByteArrayInputStream(ByteArray(32769) { 32 })
                }
            }
            try { CaptureCleanupClient.request("原文", config, false, { true }) { connection }; fail("must reject") }
            catch (_: IOException) { }
            assertTrue(connection.disconnected)
        }
    }

    @Test fun cancellationDisconnectsBlockingRead() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val connection = object : Connection() {
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int {
                    started.countDown()
                    check(released.await(3, TimeUnit.SECONDS))
                    return -1
                }
            }
            override fun disconnect() { super.disconnect(); released.countDown() }
        }
        val job = launch { CaptureCleanupClient.request("原文", config, false, { true }) { connection } }
        yield()
        assertTrue(started.await(3, TimeUnit.SECONDS))
        withTimeout(2000) { job.cancelAndJoin() }
        assertTrue(connection.disconnected)
    }

    @Test fun revocationWhileOpeningConnectionPreventsUpload() = runBlocking {
        var current = true
        val connection = object : Connection() {
            override fun getOutputStream(): ByteArrayOutputStream {
                current = false
                return sent
            }
        }
        try { CaptureCleanupClient.request("不得上传的原文", config, false, { current }) { connection }; fail("must reject") }
        catch (_: IOException) { }
        assertEquals(0, connection.sent.size())
        assertTrue(connection.disconnected)
    }
}
