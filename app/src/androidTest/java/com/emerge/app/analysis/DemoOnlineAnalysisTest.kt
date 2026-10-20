package com.emerge.app.analysis

import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.BuildConfig
import com.emerge.app.EmergeApplication
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in: one real cloud request using synthetic text, never the bundled private records. */
class DemoOnlineAnalysisTest {
    @Test fun demoUsesConfiguredGatewayForOnlineViewpointAnalysis() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("demoOnlineSmoke") == "true")
        assumeTrue(BuildConfig.BUNDLED_DEMO_DATA)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EmergeApplication
        val config = app.audioCoordinator.settings.config.value
        assertTrue("Demo cloud service should be enabled", config.enabled)
        assertTrue("Demo should retain the configured gateway credential", config.token.isNotEmpty())
        val text = "我认为学习新技能需要定期练习和复盘；完成一次练习后回顾错误，可以帮助下一次改进。"
        val id = "demo-online-device-smoke"
        val response = withContext(Dispatchers.IO) {
            val connection = (URL(config.endpoint.trimEnd('/') + "/monitor").openConnection() as HttpURLConnection)
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 45_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Authorization", "Bearer ${config.token}")
                val payload = JSONObject().put("version", 1).put("id", id).put("kind", "text")
                    .put("text", text).put("captured_at", System.currentTimeMillis()).put("check_facts", false)
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                assertEquals("Online viewpoint analysis must succeed", 200, connection.responseCode)
                connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            } finally { connection.disconnect() }
        }
        assertEquals(id, response.getString("id"))
        val claims = response.getJSONArray("claims")
        assertTrue("Cloud analysis should return verifiable claims", claims.length() in 1..3)
        for (index in 0 until claims.length()) {
            val quote = claims.getJSONObject(index).getString("quote")
            assertTrue(quote.isNotBlank() && text.contains(quote))
        }
    }
}
