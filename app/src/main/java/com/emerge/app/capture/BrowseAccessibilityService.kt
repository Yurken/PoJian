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
    private var monitorBridge: com.emerge.app.monitoring.MonitorAccessibilityBridge? = null
    private var latestType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    private var screenReceiverRegistered = false
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                sessions.reset()
                monitorBridge?.invalidateCapture()
            }
        }
    }
    private val app get() = application as EmergeApplication
    private val supported = setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!screenReceiverRegistered) {
            val filter = android.content.IntentFilter(Intent.ACTION_SCREEN_OFF)
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(screenReceiver, filter)
            screenReceiverRegistered = true
        }
        monitorBridge?.stop()
        monitorBridge = com.emerge.app.monitoring.MonitorAccessibilityBridge(this, app).also { it.start() }
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
                    monitorBridge?.invalidateCapture()
                    app.captureDiagnostics.update { it.copy(result = when {
                        !enabled -> "采集已暂停"
                        packages.isEmpty() -> "请先选择要记录的应用，当前不会读取任何内容"
                        else -> "采集范围已更新，等待所选应用的内容"
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
        monitorBridge?.onEvent(event)
        if (event == null || event.eventType !in supported) return
        if (event.eventType in setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED))
            observeBoundary()
        app.captureDiagnostics.update { it.copy(connected = true, eventsReceived = it.eventsReceived + 1) }
        if (!app.captureSettings.enabled.value || app.captureSettings.allowedPackages.value.isEmpty()) return
        // Events also come from status bars, keyboards and accessibility overlays.
        // Their package is not evidence that the user left the post. Only the
        // verified active root below can end a viewing session.
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
        // Animated pages can emit faster than a model reply; never queue stale sampling ticks.
        if (!sampleMutex.tryLock()) return
        try { captureWindow(type) } finally { sampleMutex.unlock() }
    }

    /** Notice exits before the debounced sample, including a short app switch between periodic ticks. */
    @Suppress("DEPRECATION")
    private fun observeBoundary() {
        val power = getSystemService(android.os.PowerManager::class.java)
        val keyguard = getSystemService(android.app.KeyguardManager::class.java)
        if (!power.isInteractive || keyguard.isKeyguardLocked) { sessions.reset(); return }
        val root = rootInActiveWindow ?: run { sessions.pause(); return }
        try {
            val active = root.packageName?.toString()
            if (active == packageName || active == "com.android.settings") sessions.reset()
            else if (excluded(active)) sessions.pause()
            else if (sessions.packageName != null && active != sessions.packageName) sessions.reset()
        } finally { root.recycle() }
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
        val bridgeRevision = monitorBridge?.captureRevision ?: -1L
        var root: AccessibilityNodeInfo? = null
        try {
            root = rootInActiveWindow
            if (root == null) {
                sessions.pause()
                app.captureDiagnostics.update { it.copy(lastCheck = now, result = "已连接服务，但当前窗口没有内容节点") }
                return
            }
            val activePackage = root.packageName?.toString()
            if (excluded(activePackage)) {
                if (activePackage == packageName || activePackage == "com.android.settings") sessions.reset()
                else sessions.pause()
                app.captureDiagnostics.update { it.copy(lastCheck = now, result = "当前是破茧、系统界面或输入法，已跳过") }
                return
            }
            val permission = activePackage?.let(app.captureSettings::permissionToken)
            if (activePackage == null || permission == null) {
                sessions.reset()
                app.captureDiagnostics.update { it.copy(lastCheck = now, result = "当前应用不在选择范围内，不会读取内容") }
                return
            }
            // Check the active package before traversing any visible text or descriptions.
            val privacy = PagePrivacyGuard.inspect(root, Rect().also(root::getBoundsInScreen))
            if (privacy.blockText) {
                sessions.reset()
                monitorBridge?.blockPrivatePage()
                app.captureDiagnostics.update { it.copy(lastCheck = now, result = "隐私页面，已跳过") }
                return
            }
            val identityNodes = mutableListOf<IdentityNode>()
            val text = PlatformTextCleaner.clean(extractText(root, identityNodes))
            // Page structure and identity still need gallery/follow controls, even when prose excludes them.
            val structureText = identityNodes.flatMap { listOf(it.text, it.description) }
                .filter(String::isNotBlank).distinct().joinToString("\n")
            app.captureDiagnostics.update { it.copy(lastCheck = now, lastPackage = activePackage) }
            if (text.isBlank()) {
                sessions.pause()
                app.captureDiagnostics.update { it.copy(result = "可以访问窗口，但读不到可见文字（受画面或节点限制）") }
                return
            }
            if (WorkIdentityExtractor.isRecommendationList(activePackage, structureText, identityNodes)) {
                sessions.reset()
                app.captureDiagnostics.update { it.copy(result = "当前是推荐列表，摘要不计为已浏览作品；打开单篇后开始计数") }
                return
            }
            if (!app.captureSettings.isPermissionCurrent(activePackage, permission)) { sessions.reset(); return }
            val elapsed = android.os.SystemClock.elapsedRealtime()
            val hint = app.repository.resolveHint(activePackage, WorkIdentityExtractor.extract(activePackage, structureText, identityNodes))
            if (hint.page == WorkPage.NON_CONTENT ||
                (hint.page == WorkPage.COMMENTS && !sessions.canAttachComments(activePackage))) {
                sessions.pause()
                app.captureDiagnostics.update { it.copy(result = "当前不是可识别作品，或评论缺少原视频上下文，未增加作品数") }
                return
            }
            val captured = sessions.sample(activePackage, text, now, elapsed, type, hint)
            if (monitorBridge?.captureRevision != bridgeRevision) { sessions.pause(); return }
            val capturedWindow = root.windowId
            root.recycle()
            root = null
            // A valid historical snapshot need not still be on screen when the fast model replies.
            // Identity/time were sampled above; permissions/deletion are checked again at the write.
            val cleaned = app.cleanCaptureText(text, activePackage, generation) {
                app.captureSettings.isPermissionCurrent(activePackage, permission)
            }
            val sample = captured.withCleanedText(cleaned)
            if (sample.event.visibleText.isBlank()) { sessions.pause(); return }
            val id = app.repository.saveCapture(sample.event, sample.continuing, generation,
                work = sample.work, sampleText = cleaned) {
                app.captureSettings.isPermissionCurrent(activePackage, permission)
            }
            if (!app.captureSettings.isPermissionCurrent(activePackage, permission)) {
                sessions.reset()
                return
            }
            if (!sessions.isCurrent(sample)) return
            sessions.saved(sample, id)
            if (id != null && monitorBridge?.captureRevision == bridgeRevision)
                monitorBridge?.observed(sample.event.copy(id = id), hint, capturedWindow, bridgeRevision)
            app.captureDiagnostics.update { it.copy(lastSaved = if (id == null) it.lastSaved else now,
                result = when {
                    id == null -> "记录已删除或采集范围有变化，本次采样已丢弃"
                    sample.continuing -> "${sample.region.label}仍在显示，可见区间累计 ${sample.event.durationMillis / 1000} 秒"
                    else -> "已开始记录一段浏览"
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
    private fun extractText(root: AccessibilityNodeInfo, identityNodes: MutableList<IdentityNode>): String {
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
                identityNodes += IdentityNode(node.viewIdResourceName.orEmpty(),
                    node.text?.toString()?.trim()?.take(2000).orEmpty(),
                    node.contentDescription?.toString()?.trim()?.take(2000).orEmpty())
                val captured = identityNodes.last()
                length += captured.text.length + captured.description.length
                PlatformTextCleaner.captureNodeText(captured.text, captured.description, captured.resourceId).lines().forEach { line ->
                    if (line.isNotBlank()) lines.add(line)
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
        monitorBridge?.interrupt()
        pending?.cancel()
        sessions.reset()
        app.captureDiagnostics.update { it.copy(result = "系统中断了辅助功能反馈，等待下一次采集") }
    }
    override fun onUnbind(intent: Intent?): Boolean {
        monitorBridge?.stop(); monitorBridge = null
        app.captureDiagnostics.update { it.copy(connected = false, result = "系统已断开辅助功能服务，请重新开启") }
        scope.cancel()
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        monitorBridge?.stop(); monitorBridge = null
        app.captureDiagnostics.update { it.copy(connected = false, result = "辅助功能服务已停止") }
        scope.cancel()
        super.onDestroy()
    }
}
