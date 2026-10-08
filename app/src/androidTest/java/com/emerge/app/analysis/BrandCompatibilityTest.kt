package com.emerge.app.analysis

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.MainActivity
import com.emerge.app.capture.BrowseAccessibilityService
import com.emerge.app.audio.ProjectionAudioService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrandCompatibilityTest {
    @Test fun newBrandPreservesExistingLauncherAndServiceComponents() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        assertEquals("破茧", manager.getApplicationLabel(context.applicationInfo).toString())

        val launcher = manager.getActivityInfo(
            ComponentName(context.packageName, context.packageName + ".MainActivity"), 0)
        assertEquals(MainActivity::class.java.name, launcher.targetActivity)
        assertTrue(launcher.exported)

        val service = manager.getServiceInfo(
            ComponentName(context.packageName, context.packageName + ".capture.BrowseAccessibilityService"), 0)
        assertEquals("破茧浏览采集", service.loadLabel(manager).toString())
        assertTrue(BrowseAccessibilityService::class.java.isAssignableFrom(Class.forName(service.name)))
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", service.permission)
    }

    @Test fun audioUsesExplicitCapturePermissionsWithoutBroadStorageOrMicrophoneServiceAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        val permissions = manager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        setOf("android.permission.INTERNET", "android.permission.RECORD_AUDIO",
            "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION",
            "android.permission.POST_NOTIFICATIONS").forEach { permission ->
            assertTrue("Audio capture requires $permission", permission in permissions)
        }
        setOf("android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.READ_MEDIA_AUDIO",
            "android.permission.READ_MEDIA_VIDEO", "android.permission.FOREGROUND_SERVICE_MICROPHONE")
            .forEach { permission -> assertFalse("Unexpected permission $permission", permission in permissions) }

        val audioService = manager.getServiceInfo(ComponentName(context, ProjectionAudioService::class.java), 0)
        assertFalse("Playback capture cannot be started by another app", audioService.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, audioService.foregroundServiceType)
    }
}
