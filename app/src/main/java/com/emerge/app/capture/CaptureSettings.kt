package com.emerge.app.capture

import android.content.Context
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CaptureSettings(context: Context) {
    private val preferences = context.getSharedPreferences("capture", Context.MODE_PRIVATE)
    private val lock = Any()
    private var revision = 0L
    private val enabledState = MutableStateFlow(preferences.getBoolean("enabled", false))
    private val packagesState = MutableStateFlow(immutablePackages(preferences.getStringSet("allowed_packages", emptySet()).orEmpty()))
    val enabled: StateFlow<Boolean> = enabledState.asStateFlow()
    val allowedPackages: StateFlow<Set<String>> = packagesState.asStateFlow()

    fun setEnabled(value: Boolean) = synchronized(lock) {
        if (enabledState.value != value) {
            revision++
            preferences.edit().putBoolean("enabled", value).apply()
            enabledState.value = value
        }
    }

    fun setAllowedPackages(packages: Set<String>) = synchronized(lock) {
        val copy = immutablePackages(packages)
        if (packagesState.value != copy) {
            revision++
            // SharedPreferences does not copy its string sets; never pass or expose caller-owned sets.
            preferences.edit().putStringSet("allowed_packages", HashSet(copy)).apply()
            packagesState.value = copy
        }
    }

    fun isPackageAllowed(packageName: String): Boolean = packageName in packagesState.value

    internal fun permissionToken(packageName: String): Long? = synchronized(lock) {
        revision.takeIf { enabledState.value && packageName in packagesState.value }
    }

    internal fun isPermissionCurrent(packageName: String, token: Long): Boolean = synchronized(lock) {
        revision == token && enabledState.value && packageName in packagesState.value
    }

    private fun immutablePackages(packages: Set<String>): Set<String> {
        return Collections.unmodifiableSet(packages.filterTo(HashSet()) { it.isNotBlank() })
    }
}
