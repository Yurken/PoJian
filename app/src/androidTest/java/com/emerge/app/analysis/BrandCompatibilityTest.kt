package com.emerge.app.analysis

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emerge.app.MainActivity
import com.emerge.app.capture.BrowseAccessibilityService
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

    @Test fun localPrototypeHasNoNetworkOrRecordingPermissions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val permissions = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertFalse("android.permission.INTERNET" in permissions)
        assertFalse("android.permission.RECORD_AUDIO" in permissions)
        assertFalse("android.permission.MANAGE_EXTERNAL_STORAGE" in permissions)
    }
}
