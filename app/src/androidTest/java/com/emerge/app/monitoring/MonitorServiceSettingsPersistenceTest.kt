package com.emerge.app.monitoring

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.EmergeApplication
import com.emerge.app.audioanalysis.AudioAnalysisSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MonitorServiceSettingsPersistenceTest {
    @Test fun explicitEmptyTokenAndAuthorizationRevisionSurviveRecreation() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as EmergeApplication
        val settings = app.audioCoordinator.settings
        val original = settings.config.value
        try {
            val saved = original.copy(enabled = false, endpoint = "http://127.0.0.1:49111", token = "")
            settings.save(saved, true)
            val reopened = AudioAnalysisSettings(app)
            assertTrue(saved == reopened.config.value) // Never print a configured token on assertion failure.
            assertEquals("", reopened.config.value.token)
            assertEquals(settings.authorizationRevision, reopened.authorizationRevision)
        } finally { settings.save(original, true) }
    }
}
