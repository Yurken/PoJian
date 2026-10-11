package com.emerge.app.monitoring

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.EmergeApplication
import com.emerge.app.data.BrowseEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real HTTP worker and Room, using only a loopback server and synthetic input. */
@RunWith(AndroidJUnit4::class)
class MonitorFramePipelineTest {
    @Test fun cloudOptInDrainsBacklogBeyondCapacityWithoutFactChecksOrClientQuotas() = runBlocking {
        fixture { f ->
            val settings = f.app.audioCoordinator.settings
            settings.save(settings.config.value.copy(enabled = false), true)
            delay(300)
            repeat(9) { f.text(it) }
            delay(300)
            assertEquals(0, f.requestCount.get())
            f.app.getSharedPreferences("audio_analysis", Context.MODE_PRIVATE).edit()
                .putString("day", java.time.LocalDate.now().toString()).putInt("count", 120).putInt("limit", 1).commit()
            f.app.getSharedPreferences("live_monitor", Context.MODE_PRIVATE).edit()
                .putString("frame_day", java.time.LocalDate.now().toString()).putInt("frames_used", 120).commit()
            settings.save(settings.config.value.copy(enabled = true), true)
            withTimeout(15000) { f.app.repository.semantics.first { rows -> rows.count { it.eventId in f.ids } == 9 } }
            withTimeout(5000) { f.app.monitorTasks.counts.first { it.queued == 0 && it.running == 0 } }
            repeat(9) { assertFalse(f.received.receive().getBoolean("check_facts")) }
            assertEquals(9, f.requestCount.get())
            assertEquals(129, settings.requestsToday())
            assertTrue(f.frame("past-old-frame-limit").await())
            assertEquals("frame", f.received.receive().getString("kind"))
            f.app.monitor.saveConfig(f.app.monitor.config.value.copy(realtime = true))
            delay(300)
            f.text(10)
            assertTrue(f.received.receive().getBoolean("check_facts"))
            withTimeout(5000) { f.app.monitorTasks.counts.first { it.queued == 0 && it.running == 0 } }
            delay(500)
            assertEquals(11, f.requestCount.get())
        }
    }

    @Test fun failedVisionPreservesOcrAndCompletesItThroughThePersistentTextQueue() = runBlocking {
        fixture { f ->
            f.text(40)
            f.received.receive()
            withTimeout(5000) { f.app.monitor.state.first { it.pending == 0 } }
            val parent = requireNotNull(f.app.repository.findEvent(f.ids.last()))
            val at = System.currentTimeMillis()
            val material = ScreenTextMaterial.event(PACKAGE, "招聘岗位正在减少，因为岗位同比减少20%", at, parent)
            val saved = material.copy(id = requireNotNull(f.app.repository.saveCapture(material, false,
                f.app.repository.captureGeneration) { true }))
            f.ids += saved.id
            f.response = { _, request ->
                if (request.getString("kind") == "frame") reply(request).put("claims", JSONArray().apply {
                    repeat(4) { put(JSONObject()) }
                }) else reply(request).put("claims", JSONArray().put(JSONObject()
                    .put("quote", "招聘岗位正在减少").put("topic", "就业").put("subject", "招聘岗位")
                    .put("dimension", "数量").put("stance", "减少").put("evidence", "岗位同比减少20%")
                    .put("conditions", "").put("uncertain", false).put("verdict", "not_checked")
                    .put("explanation", "本地回归响应").put("sources", JSONArray())))
            }
            val completed = CompletableDeferred<Boolean>()
            assertTrue(f.app.monitor.submitFrame(PACKAGE, parent.visibleText, "test-image", "screen", at,
                material = saved) { completed.complete(it) })
            assertFalse(completed.await())
            assertEquals("frame", f.received.receive().getString("kind"))
            val fallback = f.received.receive()
            assertEquals("text", fallback.getString("kind"))
            assertEquals(saved.visibleText, fallback.getString("text"))
            assertFalse(fallback.has("image"))
            withTimeout(5000) { f.app.monitorTasks.observeInputStates().first { it[saved.id] == "completed" } }
            val semantics = f.app.repository.semantics.first().single { it.eventId == saved.id }
            assertTrue(semantics.validFor(saved))
            assertEquals(parent.id, f.app.repository.findEvent(saved.id)?.parentEventId)
        }
    }

    @Test fun invalidResponsesRetryAndManualChecksDoNotUseAnalysisOnlyCache() = runBlocking {
        fixture { f ->
            f.response = { index, request -> reply(request).apply {
                if (index == 0) put("claims", JSONArray().apply { repeat(4) { put(JSONObject()) } })
            } }
            assertFalse(f.frame("same-pixels").await())
            assertTrue(f.frame("same-pixels").await())
            assertEquals(2, f.requestCount.get())
            assertTrue(f.frame("same-pixels").await())
            assertEquals("Validated identical input should reuse cache", 2, f.requestCount.get())
            assertTrue(f.frame("same-pixels", manual = true).await())
            assertEquals(3, f.requestCount.get())
            val requests = List(3) { f.received.receive() }
            assertFalse(requests[0].getBoolean("check_facts"))
            assertTrue(requests.last().getBoolean("check_facts"))
            assertTrue(f.app.monitor.canSampleFrame(false))
        }
    }

    @Test fun queuedTextGetsATurnBetweenVisualRequests() = runBlocking {
        fixture { f ->
            val releaseFrame = CountDownLatch(1)
            val releaseText = CountDownLatch(1)
            f.response = { index, request ->
                if (index == 0) check(releaseFrame.await(10, TimeUnit.SECONDS))
                if (index == 1) check(releaseText.await(10, TimeUnit.SECONDS))
                reply(request)
            }
            val first = f.frame("frame-one")
            assertEquals("frame", f.received.receive().getString("kind"))
            assertFalse(f.app.monitor.canSampleFrame(true))
            assertFalse(f.app.monitor.submitFrame(PACKAGE, TEXT, "rejected", "screen", System.currentTimeMillis()))
            f.text(1); f.text(2)
            withTimeout(5000) { f.app.monitorTasks.counts.first { it.queued == 2 } }
            releaseFrame.countDown()
            assertTrue(first.await())
            val second = f.frame("frame-two")
            assertEquals("text", f.received.receive().getString("kind"))
            releaseText.countDown()
            assertTrue(second.await())
            assertEquals("frame", f.received.receive().getString("kind"))
            assertEquals("text", f.received.receive().getString("kind"))
            withTimeout(5000) { f.app.monitorTasks.counts.first { it.queued == 0 && it.running == 0 } }
        }
    }

    @Test fun fullPersistentQueueDefersAutomaticFramesAndRecoversAfterDrain() = runBlocking {
        fixture { f ->
            val releaseText = CountDownLatch(1)
            f.response = { index, request ->
                if (index == 0) check(releaseText.await(10, TimeUnit.SECONDS))
                reply(request)
            }
            f.text(0)
            assertEquals("text", f.received.receive().getString("kind"))
            repeat(5) { f.text(it + 1) }
            withTimeout(5000) { f.app.monitor.state.first { it.pending == MonitorTaskStore.CAPACITY } }
            assertFalse(f.app.monitor.canSampleFrame(false))
            assertTrue(f.app.monitor.canSampleFrame(true))
            releaseText.countDown()
            withTimeout(10000) { f.app.monitor.state.first { it.pending == 0 } }
            assertTrue(f.app.monitor.canSampleFrame(false))
        }
    }

    @Test fun permissionRevocationReleasesVisualSlotAndRejectsLateResult() = runBlocking {
        fixture { f ->
            val release = CountDownLatch(1)
            f.response = { _, request -> check(release.await(10, TimeUnit.SECONDS)); reply(request) }
            val completed = f.frame("revoked")
            f.received.receive()
            f.app.captureSettings.setEnabled(false)
            release.countDown()
            assertFalse(completed.await())
            assertTrue(f.app.monitor.canSampleFrame(true))
            assertTrue(f.app.monitor.state.value.findings.none { it.packageName == PACKAGE })
        }
    }

    private suspend fun fixture(test: suspend (Fixture) -> Unit) = withTimeout(30000) {
        val f = Fixture()
        val app = f.app
        val cloud = app.audioCoordinator.settings.config.value
        val monitor = app.monitor.config.value
        val enabled = app.captureSettings.enabled.value
        val packages = app.captureSettings.allowedPackages.value
        val requestPrefs = app.getSharedPreferences("audio_analysis", Context.MODE_PRIVATE)
        val framePrefs = app.getSharedPreferences("live_monitor", Context.MODE_PRIVATE)
        val day = requestPrefs.getString("day", null)
        val count = requestPrefs.getInt("count", 0)
        val frameDay = framePrefs.getString("frame_day", null)
        val frames = framePrefs.getInt("frames_used", 0)
        try {
            app.monitor.saveConfig(monitor.copy(visual = true, realtime = false, cocoon = false))
            app.audioCoordinator.settings.save(cloud.copy(enabled = true, endpoint = "http://127.0.0.1:${f.server.localPort}",
                token = ""), true)
            requestPrefs.edit().remove("day").putInt("count", 0).commit()
            framePrefs.edit().remove("frame_day").putInt("frames_used", 0).commit()
            app.captureSettings.setAllowedPackages(setOf(PACKAGE))
            app.captureSettings.setEnabled(true)
            delay(300)
            test(f)
        } finally {
            withContext(NonCancellable) {
                app.captureSettings.setEnabled(false)
                f.server.close(); f.executor.shutdownNow()
                withTimeout(5000) { while (!app.monitor.canSampleFrame(true)) delay(10) }
                f.ids.forEach { app.repository.deleteEvent(it) }
                app.monitor.invalidate(clearSemantics = false); app.monitor.clearScreen()
                app.audioCoordinator.settings.save(cloud, true)
                requestPrefs.edit().apply { if (day == null) remove("day") else putString("day", day) }
                    .putInt("count", count).commit()
                framePrefs.edit().apply { if (frameDay == null) remove("frame_day") else putString("frame_day", frameDay) }
                    .putInt("frames_used", frames).commit()
                app.captureSettings.setAllowedPackages(packages)
                app.captureSettings.setEnabled(enabled)
                app.monitor.saveConfig(monitor)
            }
        }
    }

    private class Fixture {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EmergeApplication
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val executor = Executors.newSingleThreadExecutor()
        val received = Channel<JSONObject>(Channel.UNLIMITED)
        val requestCount = AtomicInteger()
        val ids = mutableListOf<Long>()
        @Volatile var response: (Int, JSONObject) -> JSONObject = { _, request -> reply(request) }

        init {
            executor.submit {
                while (!server.isClosed) try {
                    server.accept().use { socket ->
                        socket.soTimeout = 10000
                        val input = socket.getInputStream()
                        check(line(input).startsWith("POST /monitor "))
                        var length = 0
                        while (true) {
                            val header = line(input)
                            if (header.isEmpty()) break
                            if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                        }
                        check(length in 1..512 * 1024)
                        val body = ByteArray(length)
                        var read = 0
                        while (read < length) { val count = input.read(body, read, length - read); check(count > 0); read += count }
                        val request = JSONObject(String(body, Charsets.UTF_8))
                        val index = requestCount.getAndIncrement()
                        received.trySend(request)
                        val bytes = response(index, request).toString().toByteArray(Charsets.UTF_8)
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                } catch (_: java.io.IOException) { /* Revocation deliberately disconnects the socket. */ }
            }
        }

        suspend fun frame(image: String, manual: Boolean = false): CompletableDeferred<Boolean> {
            delay(2) // IDs contain the capture timestamp; separate captures must have distinct IDs.
            val result = CompletableDeferred<Boolean>()
            assertTrue(app.monitor.submitFrame(PACKAGE, TEXT, image, "screen", System.currentTimeMillis(), manual) { result.complete(it) })
            return result
        }

        suspend fun text(index: Int) {
            delay(2)
            val at = System.currentTimeMillis()
            val text = "$TEXT；测试文本序号 $index"
            ids += app.repository.record(BrowseEvent(packageName = PACKAGE, timestamp = at, eventType = 32,
                visibleText = text, endTimestamp = at, workKey = "frame-pipeline-$index-$at"))
        }
    }

    companion object {
        private const val PACKAGE = "com.example.framepipeline"
        private const val TEXT = "测试画面内容仅用于本地回归检查"
        private fun reply(request: JSONObject) = JSONObject().put("id", request.getString("id")).put("claims", JSONArray())
        private fun line(input: InputStream): String = buildString {
            while (true) {
                val byte = input.read(); check(byte >= 0 && length < 8192)
                if (byte == 10) break
                if (byte != 13) append(byte.toChar())
            }
        }
    }
}
