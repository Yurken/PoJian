package com.zhiheng.app.capture

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class CaptureSettings(context: Context) {
    private val preferences = context.getSharedPreferences("capture", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(preferences.getBoolean("enabled", false))
    val enabled = state.asStateFlow()
    fun setEnabled(value: Boolean) {
        preferences.edit().putBoolean("enabled", value).apply()
        state.value = value
    }
}
