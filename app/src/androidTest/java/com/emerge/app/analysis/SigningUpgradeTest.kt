package com.emerge.app.analysis

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.EmergeApplication
import com.emerge.app.capture.CaptureSettings
import com.emerge.app.data.BrowseEvent
import com.emerge.app.monitoring.MonitorPayloadCipher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, two-process upgrade check. Run only on a dedicated, disposable test device. */
@RunWith(AndroidJUnit4::class)
class SigningUpgradeTest {
    @Test fun recordsSettingsAndKeystoreSurviveReplacement() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("signingUpgradePhase")
        assumeTrue("Requires explicit seed/verify phase on a dedicated test device", phase in setOf("seed", "verify"))
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EmergeApplication
        val prefs = app.getSharedPreferences("signing_upgrade_test", Context.MODE_PRIVATE)
        val dao = app.database.browseEventDao()
        val version = app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
        val body = "固定签名升级测试：浏览记录应完整保留。"
        val identity = "signing-upgrade-test"
        if (phase == "seed") {
            check(!prefs.contains("eventId")) { "Use a fresh test device; refusing to replace existing fixture." }
            val id = dao.insert(BrowseEvent(packageName = "com.example.signingfixture", timestamp = 123456789L,
                eventType = 32, visibleText = body, endTimestamp = 123461789L, durationMillis = 5000L))
            CaptureSettings(app).setAllowedPackages(setOf("com.example.signingfixture"))
            assertTrue(prefs.edit().putLong("eventId", id).putLong("version", version)
                .putInt("uid", app.applicationInfo.uid)
                .putString("envelope", MonitorPayloadCipher().seal(body, identity)).commit())
        } else {
            assertTrue("Version must increase across installation", version > prefs.getLong("version", Long.MAX_VALUE))
            assertEquals("App UID must survive", prefs.getInt("uid", -1), app.applicationInfo.uid)
            val event = requireNotNull(dao.event(prefs.getLong("eventId", -1)))
            assertEquals(body, event.visibleText)
            assertEquals(5000L, event.durationMillis)
            assertEquals(123456789L, event.timestamp)
            assertEquals(setOf("com.example.signingfixture"), CaptureSettings(app).allowedPackages.value)
            assertEquals(body, MonitorPayloadCipher().open(requireNotNull(prefs.getString("envelope", null)), identity))
        }
    }
}
