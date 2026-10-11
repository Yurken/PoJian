package com.emerge.app.analysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.EmergeApplication
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The real HTTP consumer, Room and report pipeline; all responses are local fixtures. */
@RunWith(AndroidJUnit4::class)
class MonitorSemanticsIntegrationTest {
    @Test fun sampledCloudResultsFlowIntoReportsChartsAndDeletionRebuildsHistory() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EmergeApplication
        val settings = app.audioCoordinator.settings
        val oldCloud = settings.config.value
        val oldMonitor = app.monitor.config.value
        val oldEnabled = app.captureSettings.enabled.value
        val oldPackages = app.captureSettings.allowedPackages.value
        val preferences = app.getSharedPreferences("audio_analysis", android.content.Context.MODE_PRIVATE)
        val oldDay = preferences.getString("day", null)
        val oldCount = preferences.getInt("count", 0)
        val ids = mutableListOf<Long>()
        val server = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10000 }
        val executor = Executors.newSingleThreadExecutor()
        val replies = executor.submit {
            var retriedId = ""
            repeat(3) { responseIndex -> server.accept().use { socket ->
                socket.soTimeout = 10000
                val input = socket.getInputStream()
                assertTrue(line(input).startsWith("POST /monitor "))
                var length = 0
                while (true) {
                    val header = line(input)
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
                }
                require(length in 1..65536)
                val body = ByteArray(length)
                var read = 0
                while (read < length) { val count = input.read(body, read, length - read); require(count > 0); read += count }
                val request = JSONObject(String(body, Charsets.UTF_8))
                if (responseIndex == 0) {
                    retriedId = request.getString("id")
                    socket.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    return@use
                }
                if (responseIndex == 1) assertEquals(retriedId, request.getString("id"))
                val index = responseIndex - 1
                val evidence = "甲公司《招聘报告》显示招聘数量同比减少${if (index == 0) 20 else 30}%"
                assertTrue(request.getString("text").contains(evidence))
                val claim = JSONObject().put("quote", "招聘岗位正在减少").put("topic", "就业")
                    .put("subject", "招聘岗位").put("dimension", "数量").put("stance", "减少")
                    .put("conditions", "今年").put("evidence", evidence).put("uncertain", false)
                    .put("evidence_actors", JSONArray().put("甲公司"))
                    .put("evidence_origins", JSONArray().put("《招聘报告》"))
                    .put("verdict", "not_checked").put("explanation", "本地测试响应，未做事实核查")
                    .put("sources", JSONArray())
                val response = JSONObject().put("id", request.getString("id")).put("claims", JSONArray().put(claim))
                    .toString().toByteArray(Charsets.UTF_8)
                socket.getOutputStream().use { out ->
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    out.write(response); out.flush()
                }
            } }
        }
        try {
            app.monitor.saveConfig(oldMonitor.copy(realtime = false, cocoon = false, visual = false))
            settings.save(oldCloud.copy(enabled = true, endpoint = "http://127.0.0.1:${server.localPort}", token = "", dailyLimit = 100), true)
            app.captureSettings.setAllowedPackages(oldPackages + "com.example.semanticfixture")
            app.captureSettings.setEnabled(true)
            delay(300) // Let the permission/config collector establish the request epoch.
            repeat(2) { index ->
                val at = System.currentTimeMillis()
                val row = BrowseEvent(packageName = "com.example.semanticfixture", timestamp = at - if (index == 0) 300000 else 0,
                    eventType = 32, visibleText = "今年招聘岗位正在减少，因为甲公司《招聘报告》显示招聘数量同比减少${if (index == 0) 20 else 30}%",
                    endTimestamp = at, durationMillis = if (index == 0) 5000 else 0,
                    sampleCount = 2, workKey = "semantic-fixture-$index-$at")
                val id = app.repository.record(row)
                ids += id
                delay(100)
                app.monitor.setScreen(row.packageName, row.visibleText, "fixture-$index")
                app.monitor.submitCurrentText()
                try {
                    withTimeout(15000) { app.repository.semantics.first { rows -> rows.any { it.eventId == id } } }
                } catch (error: Exception) { throw AssertionError(app.monitor.state.value.notice, error) }
            }
            replies.get(10, TimeUnit.SECONDS)
            withTimeout(5000) { app.monitorTasks.counts.first { it.queued == 0 && it.running == 0 } }
            val events = app.repository.allEvents.first().filter { it.id in ids }
            val semantics = app.repository.semantics.first().filter { it.eventId in ids }
            assertEquals(2, semantics.size)
            assertTrue(semantics.all { it.claims.single().evidenceActors == listOf("甲公司") })
            assertTrue(semantics.all { it.claims.single().evidenceOrigins == listOf("《招聘报告》") })
            val now = System.currentTimeMillis()
            val report = CognitiveAnalyzer().analyze(events, StatisticsPeriod.DAY, now, semantics = semantics)
            assertEquals(2, report.structuredAnalyzedRecords)
            assertEquals(Increment.NEW, report.claims.last().increment)
            assertEquals(ids.first(), report.claims.last().previousEventId)
            assertEquals(5000L, report.observedDurationMillis)
            assertEquals(2, report.workStatistics!!.total)
            assertTrue(ReportExporter.text(report).contains("语义覆盖：2/2"))
            val shares = ViewpointTimeline.groups(ViewpointTimeline.build(events, StatisticsPeriod.DAY, now,
                ZoneId.systemDefault(), report, semantics).flatMap { it.items })
            assertEquals(1, shares.size)
            assertEquals(2, shares.single().total)
            assertEquals("structured_model", shares.single().analysisSource)
            app.repository.deleteEvent(ids.first())
            val remaining = app.repository.allEvents.first().filter { it.id in ids }
            val retained = app.repository.semantics.first().filter { it.eventId in ids }
            assertEquals(1, retained.size)
            val rebuilt = CognitiveAnalyzer().analyze(remaining, StatisticsPeriod.DAY, now, semantics = retained)
            assertEquals(Increment.FIRST, rebuilt.claims.single().increment)
            assertNull(rebuilt.claims.single().previousEventId)
        } finally {
            app.captureSettings.setEnabled(false)
            ids.forEach { app.repository.deleteEvent(it) }
            app.monitor.invalidate(clearSemantics = false)
            app.monitor.clearScreen()
            settings.save(oldCloud, true)
            preferences.edit().apply { if (oldDay == null) remove("day") else putString("day", oldDay) }
                .putInt("count", oldCount).commit()
            app.captureSettings.setAllowedPackages(oldPackages)
            app.captureSettings.setEnabled(oldEnabled)
            app.monitor.saveConfig(oldMonitor)
            server.close(); replies.cancel(true); executor.shutdownNow()
        }
    }

    private fun line(input: InputStream): String = buildString {
        while (true) {
            val byte = input.read()
            require(byte >= 0 && length < 8192)
            if (byte == 10) break
            if (byte != 13) append(byte.toChar())
        }
    }
}
