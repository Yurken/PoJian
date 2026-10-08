package com.emerge.app.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CaptureStatus(
    val connected: Boolean = false,
    val eventsReceived: Long = 0,
    val lastPackage: String = "—",
    val lastCheck: Long = 0,
    val lastSaved: Long = 0,
    val result: String = "尚未连接辅助功能服务"
)

class CaptureDiagnostics {
    private val state = MutableStateFlow(CaptureStatus())
    val status = state.asStateFlow()
    fun update(change: (CaptureStatus) -> CaptureStatus) { state.value = change(state.value) }
}
