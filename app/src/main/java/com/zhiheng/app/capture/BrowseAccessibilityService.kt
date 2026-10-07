package com.zhiheng.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zhiheng.app.ZhiHengApplication
import com.zhiheng.app.data.BrowseEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log

class BrowseAccessibilityService : AccessibilityService() {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sampleMutex = Mutex()
    private var periodic: Job? = null
    private var pending: Job? = null
    private var session: BrowseEvent? = null
    private var lastElapsed = 0L
    private var settingsJob: Job? = null
    private var latestType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    private val app get() = application as ZhiHengApplication
    private val supported = setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        app.captureDiagnostics.update { it.copy(connected = true, result = "服务已连接，等待浏览内容") }
        // A bounded periodic sample prevents endlessly animated feeds from starving the debounce.
        session = null
        settingsJob?.cancel()
        settingsJob = scope.launch {
            app.captureSettings.enabled.collect { enabled ->
                if (!enabled) sampleMutex.withLock { session = null }
            }
        }
        periodic?.cancel()
        periodic = scope.launch {
            while (isActive) {
                delay(2000)
                if (app.captureSettings.enabled.value) sampleWindow(latestType)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType !in supported) return
        app.captureDiagnostics.update { it.copy(connected = true, eventsReceived = it.eventsReceived + 1) }
        if (!app.captureSettings.enabled.value) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val name = event.packageName?.toString()
            if (name != null && name != session?.packageName) {
                scope.launch { sampleMutex.withLock { session = null } }
            }
        }
        if (excluded(event.packageName?.toString())) return
        latestType = event.eventType
        if (pending?.isActive == true) return
        val type = latestType
        pending = scope.launch { delay(400); sampleWindow(type) }
    }

    private suspend fun sampleWindow(type: Int) {
        sampleMutex.withLock { captureWindow(type) }
    }

    @Suppress("DEPRECATION")
    private suspend fun captureWindow(type: Int) {
        if (!app.captureSettings.enabled.value) { session = null; return }
        val power = getSystemService(android.os.PowerManager::class.java)
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        if (!power.isInteractive || keyguard.isKeyguardLocked) { session = null; return }
        val now = System.currentTimeMillis()
        var root: AccessibilityNodeInfo? = null
        try {
            root = rootInActiveWindow
            if (root == null) {
                session = null
                app.captureDiagnostics.update { it.copy(lastCheck = now, result = "收到了服务连接，但当前窗口节点为空") }
                return
            }
            val activePackage = root.packageName?.toString()
            if (excluded(activePackage)) {
                session = null
                app.captureDiagnostics.update { it.copy(lastCheck = now, result = "当前是知衡、系统界面或输入法，已跳过") }
                return
            }
            val text = extractText(root)
            app.captureDiagnostics.update { it.copy(lastCheck = now, lastPackage = activePackage ?: "未知应用") }
            if (text.isBlank()) {
                session = null
                app.captureDiagnostics.update { it.copy(result = "窗口可访问，但没有可读取的可见文字（画面或节点限制）") }
                return
            }
            if (!app.captureSettings.enabled.value) { session = null; return }
            val elapsed = android.os.SystemClock.elapsedRealtime()
            val old = session
            val gap = elapsed - lastElapsed
            val continuing = old != null && old.packageName == activePackage && gap in 0..6000 &&
                ContentContinuity.same(old.visibleText, text)
            val next = if (continuing) old!!.copy(endTimestamp = now,
                durationMillis = old.durationMillis + gap, sampleCount = old.sampleCount + 1)
            else BrowseEvent(packageName = activePackage ?: return, timestamp = now,
                eventType = type, visibleText = text, endTimestamp = now, sampleCount = 1)
            val id = if (continuing) app.repository.updateSession(next) else app.repository.record(next)
            session = next.copy(id = id)
            lastElapsed = elapsed
            app.captureDiagnostics.update { it.copy(lastSaved = now, result = if (continuing)
                "同一内容持续可见，已更新估计停留 ${next.durationMillis / 1000} 秒" else "已开始一条浏览会话") }

        } catch (error: Exception) {
            if (error is CancellationException) throw error
            app.captureDiagnostics.update { it.copy(lastCheck = now, result = "采集或保存失败：${error.javaClass.simpleName}") }
            Log.e("ZhiHengCapture", "Capture failed", error)
        } finally { root?.recycle() }
    }

    private fun excluded(name: String?) = name == null || name == packageName ||
        name == "com.android.systemui" || name == "com.android.settings" ||
        name.startsWith("com.android.inputmethod") || name.contains("keyboard", ignoreCase = true)

    @Suppress("DEPRECATION")
    private fun extractText(root: AccessibilityNodeInfo): String {
        val lines = linkedSetOf<String>()
        var nodes = 0
        var length = 0
        val screen = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (++nodes > 1500 || depth > 40 || length >= 16000) return
            if (node.isPassword || node.isEditable) return
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (node.isVisibleToUser && Rect.intersects(bounds, screen)) {
                listOf(node.text, node.contentDescription).forEach { raw ->
                    val line = raw?.toString()?.trim()?.take(2000)
                    if (!line.isNullOrBlank() && lines.add(line)) length += line.length
                }
            }
            for (index in 0 until node.childCount) {
                if (nodes >= 1500 || length >= 16000) break
                node.getChild(index)?.let { child -> try { visit(child, depth + 1) } finally { child.recycle() } }
            }
        }
        visit(root, 0)
        return lines.joinToString("\n").take(16000)
    }

    override fun onInterrupt() {
        pending?.cancel()
        session = null
        app.captureDiagnostics.update { it.copy(result = "系统中断了辅助功能反馈，等待下一次采集") }
    }
    override fun onUnbind(intent: Intent?): Boolean {
        app.captureDiagnostics.update { it.copy(connected = false, result = "系统已断开辅助功能服务，请重新启用") }
        scope.cancel()
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        app.captureDiagnostics.update { it.copy(connected = false, result = "辅助功能服务已停止") }
        scope.cancel()
        super.onDestroy()
    }
}
