package com.emerge.app.capture

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.emerge.app.EmergeApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log

open class BrowseAccessibilityService : AccessibilityService() {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sampleMutex = Mutex()
    private var periodic: Job? = null
    private var pending: Job? = null
    private val sessions = CaptureSessionTracker()
    private var settingsJob: Job? = null
    private var latestType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    private val app get() = application as EmergeApplication
    private val supported = setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        app.captureDiagnostics.update { it.copy(connected = true, result = "服务已连接，等待浏览内容") }
        // A bounded periodic sample prevents endlessly animated feeds from starving the debounce.
        sessions.reset()
        settingsJob?.cancel()
        settingsJob = scope.launch {
            combine(app.captureSettings.enabled, app.captureSettings.allowedPackages) { enabled, packages ->
                enabled to packages
            }.collect { (enabled, packages) ->
                pending?.cancel()
                sampleMutex.withLock {
                    sessions.reset()
                    app.captureDiagnostics.update { it.copy(result = when {
                        !enabled -> "采集已暂停"
                        packages.isEmpty() -> "请先选择允许采集的应用，当前不会读取正文"
                        else -> "采集范围已更新，等待选中应用的可见内容"
                    }) }
                }
            }
        }
        periodic?.cancel()
        periodic = scope.launch {
            while (isActive) {
                delay(2000)
                if (app.captureSettings.enabled.value && app.captureSettings.allowedPackages.value.isNotEmpty()) {
                    sampleWindow(latestType)
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType !in supported) return
        app.captureDiagnostics.update { it.copy(connected = true, eventsReceived = it.eventsReceived + 1) }
        if (!app.captureSettings.enabled.value || app.captureSettings.allowedPackages.value.isEmpty()) return
        val name = event.packageName?.toString()
        if (excluded(name) || name == null || !app.captureSettings.isPackageAllowed(name)) {
            pending?.cancel()
            sessions.reset()
            app.captureDiagnostics.update { it.copy(lastCheck = System.currentTimeMillis(), result =
                if (excluded(name)) "当前是破茧、系统界面或输入法，已跳过" else "当前应用不在选定范围内，不读取正文") }
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (name != sessions.packageName) {
                pending?.cancel()
                sessions.reset()
            }
        }
        latestType = event.eventType
        if (pending?.isActive == true) return
        val type = latestType
        pending = scope.launch {
            delay(400)
            pending = null
            sampleWindow(type)
        }
    }

    private suspend fun sampleWindow(type: Int) {
        sampleMutex.withLock { captureWindow(type) }
    }

    @Suppress("DEPRECATION")
    private suspend fun captureWindow(type: Int) {
        if (!app.captureSettings.enabled.value || app.captureSettings.allowedPackages.value.isEmpty()) {
            sessions.reset()
            return
        }
        val power = getSystemService(android.os.PowerManager::class.java)
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        if (!power.isInteractive || keyguard.isKeyguardLocked) { sessions.reset(); return }
        val now = System.currentTimeMillis()
        val generation = app.repository.captureGeneration
        var root: AccessibilityNodeInfo? = null
        try {
            root = rootInActiveWindow
            if (root == null) {
                sessions.reset()
                app.captureDiagnostics.update { it.copy(lastCheck = now, result = "收到了服务连接，但当前窗口节点为空") }
                return
            }
            val activePackage = root.packageName?.toString()
            if (excluded(activePackage)) {
                sessions.reset()
                app.captureDiagnostics.update { it.copy(lastCheck = now, result = "当前是破茧、系统界面或输入法，已跳过") }
                return
            }
            val permission = activePackage?.let(app.captureSettings::permissionToken)
            if (activePackage == null || permission == null) {
                sessions.reset()
                app.captureDiagnostics.update { it.copy(lastCheck = now, result = "当前应用不在选定范围内，不读取正文") }
                return
            }
            // Check the active package before traversing any visible text or descriptions.
            val text = extractText(root)
            app.captureDiagnostics.update { it.copy(lastCheck = now, lastPackage = activePackage) }
            if (text.isBlank()) {
                sessions.reset()
                app.captureDiagnostics.update { it.copy(result = "窗口可访问，但没有可读取的可见文字（画面或节点限制）") }
                return
            }
            if (!app.captureSettings.isPermissionCurrent(activePackage, permission)) { sessions.reset(); return }
            val elapsed = android.os.SystemClock.elapsedRealtime()
            val sample = sessions.sample(activePackage, text, now, elapsed, type)
            val id = app.repository.saveCapture(sample.event, sample.continuing, generation) {
                app.captureSettings.isPermissionCurrent(activePackage, permission) && sessions.isCurrent(sample)
            }
            if (!app.captureSettings.isPermissionCurrent(activePackage, permission)) {
                sessions.reset()
                return
            }
            if (!sessions.isCurrent(sample)) return
            sessions.saved(sample, id)
            app.captureDiagnostics.update { it.copy(lastSaved = if (id == null) it.lastSaved else now,
                result = when {
                    id == null -> "记录已删除或采集范围已变化，已丢弃此次采样"
                    sample.continuing -> "同一内容持续可见，已更新估计停留 ${sample.event.durationMillis / 1000} 秒"
                    else -> "已开始一条浏览会话"
                }) }

        } catch (error: Exception) {
            if (error is CancellationException) throw error
            app.captureDiagnostics.update { it.copy(lastCheck = now, result = "采集或保存失败：${error.javaClass.simpleName}") }
            Log.e("EmergeCapture", "Capture failed", error)
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
        sessions.reset()
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
