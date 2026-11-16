package com.emerge.app.monitoring

import com.emerge.app.audioanalysis.TextServiceConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class InformationSupplementTest {
    private val request = SupplementRequest("report:1", "职业教育", "职业培训", "就业效果", "2025年毕业生",
        listOf(SupplementKnownArgument("职业培训可以帮助就业", "受访者认为技能练习有帮助")))
    private val config = TextServiceConfig(true, "https://example.org/gateway", "fixture-token")
    private fun response() = JSONObject().put("version", 1).put("scope_key", request.scopeKey)
        .put("topic", request.topic).put("source_kind", "external").put("status", "ready")
        .put("sources", JSONArray().put(JSONObject().put("url", "https://example.org/study")
            .put("title", "就业调查").put("quote", "结果仅适用于被调查地区。")
            .put("excerpt", "研究覆盖2025年毕业生。结果仅适用于被调查地区。")))

    @Test fun acceptsOnlySourcesBoundToSelectedReportTopicAndScope() {
        assertEquals(1, InformationSupplementProtocol.decode(response(), request).sources.size)
        for ((key, value) in listOf("scope_key" to "another-report", "topic" to "其他话题",
                "source_kind" to "observed")) {
            assertThrows(IllegalArgumentException::class.java) {
                InformationSupplementProtocol.decode(response().put(key, value), request)
            }
        }
    }

    @Test fun rejectsInventedQuotesAndPrivateSources() {
        for ((key, value) in listOf("quote" to "这是模型编造的结论", "url" to "http://example.org/study",
                "url" to "https://localhost/private", "url" to "https://192.168.1.1/private",
                "url" to "https://user:secret@example.org/study", "url" to "https://localhost./private",
                "url" to "https://127.1/private", "url" to "https://service.internal./private")) {
            val data = response()
            data.getJSONArray("sources").getJSONObject(0).put(key, value)
            assertThrows(IllegalArgumentException::class.java) { InformationSupplementProtocol.decode(data, request) }
        }
    }

    @Test fun rejectsDuplicateSourcesAndInvalidStatus() {
        val duplicate = response()
        duplicate.getJSONArray("sources").put(duplicate.getJSONArray("sources").getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { InformationSupplementProtocol.decode(duplicate, request) }
        assertThrows(IllegalArgumentException::class.java) {
            InformationSupplementProtocol.decode(response().put("status", "empty"), request)
        }
    }

    @Test fun rejectsNonCanonicalIpv4AndReservedAddressRanges() {
        for (host in listOf("0177.0.0.1", "192.0.0.1", "198.18.0.1", "198.19.0.1", "192.0.2.1",
                "198.51.100.1", "203.0.113.1", "[::ffff:127.0.0.1]")) {
            assertNull(host, InformationSupplementProtocol.sourceKey("https://$host/page"))
        }
        assertNotNull(InformationSupplementProtocol.sourceKey("https://93.184.216.34/page"))
    }

    @Test fun boundedRequestNeverSilentlyTruncatesConditionsOrQuote() {
        assertEquals("2025年毕业生", InformationSupplementProtocol.encode(request).getString("conditions"))
        assertThrows(IllegalArgumentException::class.java) {
            InformationSupplementProtocol.encode(request.copy(conditions = "条".repeat(201)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            InformationSupplementProtocol.encode(request.copy(known = List(7) { request.known.first() }))
        }
    }

    @Test fun authorizationAndSourceChangesInvalidateResultsPermanently() {
        val guard = SupplementScopeGuard(request, config, "capture:1:cloud:1")
        assertTrue(guard.isCurrent(request, config, "capture:1:cloud:1"))
        assertFalse(guard.isCurrent(request, config.copy(enabled = false), "capture:1:cloud:2"))
        assertFalse(guard.isCurrent(request, config, "capture:1:cloud:1"))
        val deleted = SupplementScopeGuard(request, config, "capture:1:cloud:1")
        assertFalse(deleted.isCurrent(request.copy(scopeKey = "report:changed"), config, "capture:1:cloud:1"))
        assertFalse(deleted.isCurrent(request, config, "capture:1:cloud:1"))
    }

    @Test fun cancellationRejectsAnOtherwiseMatchingReply() {
        val guard = SupplementScopeGuard(request, config, "authorization:1")
        guard.cancel()
        assertFalse(guard.isCurrent(request, config, "authorization:1"))
    }

    @Test fun revokedRequestDoesNotOpenConnectionOrSpendRequestCount() = runBlocking {
        var opened = false
        var counted = false
        try {
            InformationSupplementClient.retrieve(request, config, false, { false }, { counted = true },
                openConnection = { opened = true; FakeConnection(response().toString()) })
            fail("Revoked request should cancel")
        } catch (_: CancellationException) { }
        assertFalse(opened)
        assertFalse(counted)
    }

    @Test fun transportUsesConfiguredGatewayAuthAndClosesConnection() = runBlocking {
        val http = FakeConnection(response().toString())
        var target = ""
        var count = 0
        val result = InformationSupplementClient.retrieve(request, config, false, { true }, { count++ },
            openConnection = { target = it.toString(); http })
        assertEquals("https://example.org/gateway/information-supplement", target)
        assertEquals("Bearer fixture-token", http.getRequestProperty("Authorization"))
        assertFalse(http.instanceFollowRedirects)
        assertEquals(1, result.sources.size)
        assertEquals(1, count)
        assertTrue(http.disconnected)
        assertEquals(request.scopeKey, JSONObject(http.sent.toString("UTF-8")).getString("scope_key"))
    }

    @Test fun secureModeRejectsLegacyRetrievalBeforePreparingAnyPayload() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            InformationSupplementClient.prepareLegacyRequest(request, secureConfigured = true)
        }
        assertEquals("请使用脱敏检索补充资料", error.message)
    }

    @Test fun outgoingLegacyTextIsMaskedAndResponseMatchesSanitizedScope() = runBlocking<Unit> {
        val phone = "13800138000"
        val email = "person@example.org"
        val nationalId = "11010519900101123X"
        val privateRequest = request.copy(scopeKey = "report:$phone", topic = "联系$email",
            subject = phone, dimension = email, conditions = nationalId,
            known = listOf(SupplementKnownArgument("电话$phone", "邮箱$email；证件$nationalId")))
        val safe = InformationSupplementClient.prepareLegacyRequest(privateRequest, secureConfigured = false)
        val reply = response().put("scope_key", safe.scopeKey).put("topic", safe.topic)
        val http = FakeConnection(reply.toString())
        assertEquals(1, InformationSupplementClient.retrieve(privateRequest, config, false, { true },
            openConnection = { http }).sources.size)
        val sent = http.sent.toString("UTF-8")
        listOf(phone, email, nationalId).forEach { assertFalse(sent.contains(it)) }
        assertTrue(sent.contains("[端侧已脱敏]"))
        assertTrue(safe.scopeKey.startsWith("private-scope:"))
        assertThrows(IllegalArgumentException::class.java) { InformationSupplementProtocol.decode(reply, privateRequest) }
    }

    @Test fun scopeChangeDuringReadRejectsReplyAndClosesConnection() = runBlocking {
        var current = true
        val http = FakeConnection(response().toString()).apply { onRead = { current = false } }
        try {
            InformationSupplementClient.retrieve(request, config, false, { current }, openConnection = { http })
            fail("Changed scope should cancel")
        } catch (_: CancellationException) { }
        assertTrue(http.disconnected)
    }

    @Test fun explicitCancellationDisconnectsBlockedRead() = runBlocking {
        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val http = object : FakeConnection(response().toString()) {
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int {
                    started.countDown()
                    check(released.await(3, TimeUnit.SECONDS)) { "Transport did not disconnect on cancellation" }
                    return -1
                }
            }
            override fun disconnect() { super.disconnect(); released.countDown() }
        }
        var returned = false
        val job = launch {
            InformationSupplementClient.retrieve(request, config, false, { true }, openConnection = { http })
            returned = true
        }
        // Yield to the launched request before waiting for its IO read.
        kotlinx.coroutines.yield()
        assertTrue(started.await(3, TimeUnit.SECONDS))
        withTimeout(2000) { job.cancelAndJoin() }
        assertTrue(http.disconnected)
        assertFalse(returned)
    }

    @Test fun responseSizeLimitFailsBeforeReadingUnboundedMaterial() = runBlocking {
        val http = object : FakeConnection(response().toString()) {
            override fun getContentLengthLong() = 129 * 1024L
            override fun getInputStream(): InputStream = error("Oversized body must not be read")
        }
        try {
            InformationSupplementClient.retrieve(request, config, false, { true }, openConnection = { http })
            fail("Oversized response should fail")
        } catch (error: Exception) {
            assertEquals("补充资料超限", InformationSupplementClient.errorMessage(error))
        }
        assertTrue(http.disconnected)
    }

    private open class FakeConnection(private val reply: String) : HttpURLConnection(URL("https://example.org")) {
        val sent = ByteArrayOutputStream()
        var disconnected = false
        var onRead: () -> Unit = {}
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode() = 200
        override fun getContentLengthLong() = reply.toByteArray(Charsets.UTF_8).size.toLong()
        override fun getOutputStream() = sent
        override fun getInputStream(): InputStream = object : ByteArrayInputStream(reply.toByteArray(Charsets.UTF_8)) {
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                val result = super.read(buffer, offset, count)
                onRead()
                return result
            }
        }
    }
}
