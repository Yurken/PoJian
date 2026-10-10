package com.emerge.app.analysis

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.audioanalysis.AudioAnalysisSettings
import com.emerge.app.audioanalysis.TextServiceConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AudioSettingsTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "audio-settings-test-${UUID.randomUUID()}"
    private val context = object : ContextWrapper(target) {
        override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(preferenceName, mode)
    }
    @After fun clean() { target.getSharedPreferences(preferenceName, Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun serviceCredentialIsEncryptedAndReadableAfterRecreation() {
        val settings = AudioAnalysisSettings(context)
        val config = TextServiceConfig(true, "https://service.example/", "fixture-service-token", 2)
        settings.save(config, false)
        val stored = context.getSharedPreferences("audio_analysis", Context.MODE_PRIVATE).getString("token", "")
        assertFalse(stored.isNullOrEmpty())
        assertNotEquals(config.token, stored)
        assertFalse(stored!!.contains(config.token))
        assertEquals(config.copy(endpoint = "https://service.example"), AudioAnalysisSettings(context).config.value)
    }
    @Test fun requestBudgetPersistsAcrossSettingsInstances() {
        val first = AudioAnalysisSettings(context)
        first.save(TextServiceConfig(true, "https://service.example", "token", 2), false)
        assertTrue(first.reserveRequest())
        val second = AudioAnalysisSettings(context)
        assertTrue(second.reserveRequest())
        assertFalse(first.reserveRequest())
        assertEquals(2, second.requestsToday())
    }
    @Test fun invalidCredentialOrReleaseHttpDoesNotChangeSettings() {
        val settings = AudioAnalysisSettings(context)
        val original = settings.config.value
        assertTrue(runCatching { settings.save(TextServiceConfig(true, "http://127.0.0.1:8787", "token"), false) }.isFailure)
        assertTrue(runCatching { settings.save(TextServiceConfig(true, "https://service.example", "token\nInjected: value"), false) }.isFailure)
        assertTrue(original == settings.config.value)
    }
}
