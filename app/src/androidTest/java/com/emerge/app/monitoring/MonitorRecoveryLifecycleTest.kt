package com.emerge.app.monitoring

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.EmergeApplication
import com.emerge.app.audioanalysis.TextServiceConfig
import com.emerge.app.data.BrowseEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/** Two separate instrumentation processes, coordinated by check-monitor-recovery.py on an isolated emulator. */
@RunWith(AndroidJUnit4::class)
class MonitorRecoveryLifecycleTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EmergeApplication
    private val prefs get() = app.getSharedPreferences("monitor_recovery_fixture", Context.MODE_PRIVATE)
    private val args get() = InstrumentationRegistry.getArguments()

    @Test fun prepareRunningTask() = runBlocking {
        assumeTrue(args.getString("monitor_recovery_stage") == "prepare")
        require(!prefs.contains("original")) { "Recovery fixture already active; run resume to restore settings" }
        val cloud = app.audioCoordinator.settings.config.value
        val monitor = app.monitor.config.value
        val budget = app.getSharedPreferences("audio_analysis", Context.MODE_PRIVATE)
        val original = JSONObject().put("enabled", app.captureSettings.enabled.value)
            .put("packages", JSONArray(app.captureSettings.allowedPackages.value.toList()))
            .put("cloudEnabled", cloud.enabled).put("endpoint", cloud.endpoint).put("token", cloud.token)
            .put("limit", cloud.dailyLimit).put("realtime", monitor.realtime).put("cocoon", monitor.cocoon)
            .put("visual", monitor.visual).put("day", budget.getString("day", null)).put("count", budget.getInt("count", 0))
        check(prefs.edit().putString("original", MonitorPayloadCipher().seal(original.toString(), "recovery-fixture")).commit())
        app.monitor.saveConfig(monitor.copy(realtime = false, cocoon = false, visual = false))
        val port = requireNotNull(args.getString("monitor_recovery_port")).toInt()
        app.audioCoordinator.settings.save(TextServiceConfig(true, "http://127.0.0.1:$port", "", 100), true)
        app.captureSettings.setAllowedPackages(setOf(PACKAGE))
        app.captureSettings.setEnabled(true)
        delay(300)
        val now = System.currentTimeMillis()
        val row = BrowseEvent(packageName = PACKAGE, timestamp = now, eventType = 32,
            visibleText = TEXT, endTimestamp = now, sampleCount = 1, workKey = "recovery-fixture-$now")
        val id = app.repository.record(row)
        check(prefs.edit().putLong("eventId", id).commit())
        app.monitor.setScreen(PACKAGE, TEXT, "recovery-screen-$now")
        app.monitor.submitCurrentText()
        withTimeout(10000) { app.monitorTasks.observeInputStates().first { it[id] == "running" } }
        // Instrumentation may stop the process as soon as it returns; prove the request reached the fake server first.
        withTimeout(10000) {
            while (true) {
                val connection = URL("http://127.0.0.1:$port/__fixture_status").openConnection() as HttpURLConnection
                val accepted = try {
                    connection.connectTimeout = 1000; connection.readTimeout = 1000
                    connection.inputStream.bufferedReader().use { it.readText() } == "accepted"
                } finally { connection.disconnect() }
                if (accepted) break
                delay(100)
            }
        }
        assertTrue(app.repository.semantics.first().none { it.eventId == id })
    }

    @Test fun resumeAndVerifyRecoveredTask() = runBlocking {
        assumeTrue(args.getString("monitor_recovery_stage") == "resume")
        val original = JSONObject(MonitorPayloadCipher().open(requireNotNull(prefs.getString("original", null)), "recovery-fixture"))
        val id = prefs.getLong("eventId", -1)
        try {
            withTimeout(20000) { app.repository.semantics.first { rows -> rows.any { it.eventId == id } } }
            withTimeout(5000) { app.monitorTasks.observeInputStates().first { it[id] == "completed" } }
            val row = requireNotNull(app.repository.findEvent(id))
            val semantics = app.repository.semantics.first().single { it.eventId == id }
            assertTrue(semantics.validFor(row))
            assertEquals("招聘岗位正在减少", semantics.claims.single().quote)
            assertEquals(0L, row.durationMillis)
            assertEquals(1, app.repository.allEvents.first().count { it.packageName == PACKAGE })
            assertTrue(app.monitor.state.value.contextToken.isEmpty())
            assertTrue(app.monitor.state.value.findings.filter { it.packageName == PACKAGE }.none(app.monitor::consumeAlert))
        } finally {
            app.captureSettings.setEnabled(false)
            if (id > 0) app.repository.deleteEvent(id)
            app.monitor.invalidate(clearSemantics = false)
            app.audioCoordinator.settings.save(TextServiceConfig(original.getBoolean("cloudEnabled"),
                original.getString("endpoint"), original.getString("token"), original.getInt("limit")), true)
            val budget = app.getSharedPreferences("audio_analysis", Context.MODE_PRIVATE)
            budget.edit().apply { if (original.isNull("day")) remove("day") else putString("day", original.getString("day")) }
                .putInt("count", original.getInt("count")).commit()
            val packages = original.getJSONArray("packages")
            app.captureSettings.setAllowedPackages((0 until packages.length()).map { packages.getString(it) }.toSet())
            app.captureSettings.setEnabled(original.getBoolean("enabled"))
            app.monitor.saveConfig(app.monitor.config.value.copy(realtime = original.getBoolean("realtime"),
                cocoon = original.getBoolean("cocoon"), visual = original.getBoolean("visual")))
            prefs.edit().clear().commit()
        }
    }

    companion object {
        private const val PACKAGE = "com.example.recoveryfixture"
        private const val TEXT = "今年招聘岗位正在减少，因为岗位同比减少20%"
    }
}
