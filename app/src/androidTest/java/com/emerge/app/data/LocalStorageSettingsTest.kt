package com.emerge.app.data

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalStorageSettingsTest {
    @Test fun retentionSurvivesRecreationAndInvalidValuesCannotReplaceSavedPolicy() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "retention-test-${UUID.randomUUID()}"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
        }
        try {
            val settings = LocalStorageSettings(context)
            assertEquals(0, settings.retentionDays.value)
            settings.setRetentionDays(7)
            assertEquals(7, settings.retentionDays.value)
            assertEquals(7, LocalStorageSettings(context).retentionDays.value)
            assertThrows(IllegalArgumentException::class.java) { settings.setRetentionDays(-1) }
            assertEquals(7, LocalStorageSettings(context).retentionDays.value)
            settings.setRetentionDays(0)
            assertEquals(0, LocalStorageSettings(context).retentionDays.value)
            base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putInt("retention_days", 1).commit()
            assertEquals(0, LocalStorageSettings(context).retentionDays.value)
        } finally {
            base.deleteSharedPreferences(name)
        }
    }
}
