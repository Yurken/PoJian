package com.emerge.app.monitoring

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.emerge.app.EmergeApplication
import com.emerge.app.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlin.math.abs
import kotlin.math.roundToInt

/** Mounted only in an explicitly selected, unlocked foreground application. */
class MonitorAccessibilityBridge(private val service: AccessibilityService, private val app: EmergeApplication) {
    private val monitor get() = app.monitor
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager = service.getSystemService(WindowManager::class.java)
    private var rootView: LinearLayout? = null
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var detail: TextView? = null
    private val actions = mutableListOf<Button>()
    private var closePanel: Job? = null
    private var revision = 0L
    private val screenScope = MonitorScreenScope()
    private val sampling = FrameSamplingPolicy()
    private var shotPending = false
    private var windowId = -1
    private var bounds = Rect()
    private val position = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; x = 0; y = 260 }

    fun start() {
        monitor.bindManual { scope.launch { refresh(); if (monitor.config.value.visual) screenshot(true) else monitor.submitCurrentText() } }
        scope.launch {
            combine(monitor.state, monitor.config, app.captureSettings.enabled, app.captureSettings.allowedPackages) { state, config, enabled, packages ->
                Triple(state, config, enabled && state.currentPackage in packages)
            }
                .collect { (state, config, enabled) ->
                    if (!enabled || !config.bubble || state.currentPackage.isBlank()) hide()
                    else {
                        show()
                        if (shotPending && Build.VERSION.SDK_INT < 34) rootView?.visibility = View.INVISIBLE
                        bubble?.text = if (state.busy) "…" else "茧"
                        val finding = state.findings.firstOrNull { it.contextToken == state.contextToken && state.contextToken.isNotBlank() }
                        if (finding != null && monitor.consumeAlert(finding)) expand(finding, automatic = true)
                    }
                }
        }
        scope.launch {
            while (isActive) {
                refresh()
                val config = monitor.config.value
                if (config.visual && app.audioCoordinator.settings.config.value.enabled &&
                    monitor.frameCount() < config.dailyFrames) screenshot(false)
                delay(2000)
            }
        }
    }
    fun onEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() == service.packageName) return
        if (event.eventType in setOf(AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
            revision++
            // Revoke screen binding immediately; do not wait for the next periodic sample.
            monitor.revokeScreen()
            sampling.observeContext("", SystemClock.elapsedRealtime())
            closePanel?.cancel(); panel?.visibility = View.GONE
        }
        if (event.packageName?.toString() !in app.captureSettings.allowedPackages.value &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) { clearScreen(); hide() }
    }
    private fun samplingScope(): String = monitor.state.value.contextToken.let {
        if (it.isBlank()) "" else "$it:${monitor.samplingRevision}"
    }
    private fun clearScreen() {
        monitor.clearScreen()
        sampling.observeContext("", SystemClock.elapsedRealtime())
    }
    @Suppress("DEPRECATION")
    private fun refresh() {
        val power = service.getSystemService(android.os.PowerManager::class.java)
        val lock = service.getSystemService(android.app.KeyguardManager::class.java)
        if (!app.captureSettings.enabled.value || !power.isInteractive || lock.isKeyguardLocked) {
            clearScreen(); hide(); return
        }
        val root = service.rootInActiveWindow ?: run { clearScreen(); hide(); return }
        try {
            val name = root.packageName?.toString().orEmpty()
            if (name == service.packageName || name !in app.captureSettings.allowedPackages.value ||
                name.startsWith("com.android.inputmethod") || name.contains("keyboard", true) || name == "com.android.systemui") {
                clearScreen(); hide(); return
            }
            windowId = root.windowId
            root.getBoundsInScreen(bounds)
            val text = visibleText(root)
            if (com.emerge.app.capture.WorkIdentityExtractor.isRecommendationList(name, text)) {
                clearScreen(); hide(); return
            }
            val token = screenScope.token(name, windowId, revision, text)
            monitor.setScreen(name, text, token)
            sampling.observeContext(samplingScope(), SystemClock.elapsedRealtime())
        } finally { root.recycle() }
    }
    @Suppress("DEPRECATION")
    private fun visibleText(root: AccessibilityNodeInfo): String {
        val lines = linkedSetOf<String>(); var count = 0; var characters = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (++count > 1500 || depth > 40 || characters >= 6000 || node.isPassword || node.isEditable) return
            val rectangle = Rect(); node.getBoundsInScreen(rectangle)
            if (node.isVisibleToUser && Rect.intersects(rectangle, bounds)) {
                listOf(node.text, node.contentDescription).forEach { value ->
                    val line = value?.toString()?.trim()?.take(2000)
                    if (!line.isNullOrBlank() && lines.add(line)) characters += line.length
                }
            }
            for (i in 0 until node.childCount) {
                if (count >= 1500 || characters >= 6000) break
                node.getChild(i)?.let { child -> try { visit(child, depth + 1) } finally { child.recycle() } }
            }
        }
        visit(root, 0); return lines.joinToString("\n").take(6000)
    }

    @Suppress("DEPRECATION")
    private fun screenshot(manual: Boolean) {
        val state = monitor.state.value
        if (state.currentPackage.isBlank() || !app.captureSettings.enabled.value || !monitor.config.value.visual || shotPending) return
        if (!monitor.canSampleFrame(manual)) {
            if (manual) monitor.notice("待处理内容已满，本次未核查")
            return
        }
        if (!sampling.shouldCapture(SystemClock.elapsedRealtime(), monitor.config.value.frameIntervalSeconds, manual, false)) return
        if (Build.VERSION.SDK_INT < 30) { monitor.notice("Android 10 当前仅支持文字与播放音频；关键画面需要 Android 11+"); return }
        screenshotSupported(manual)
    }

    @androidx.annotation.RequiresApi(30)
    private fun screenshotSupported(manual: Boolean) {
        val state = monitor.state.value
        if (!app.audioCoordinator.settings.config.value.enabled) { monitor.notice("请先配置并启用支持视觉模型的云端网关"); return }
        val permission = app.captureSettings.permissionToken(state.currentPackage) ?: return
        val capturedWindow = windowId
        val capturedBounds = Rect(bounds)
        val token = state.contextToken
        val sampleScope = samplingScope()
        val at = System.currentTimeMillis()
        // On Android 11–13 the API captures a display: avoid input methods and another focused window.
        if (Build.VERSION.SDK_INT < 34 && service.windows.any {
                it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD ||
                    (it.isFocused && it.id != capturedWindow)
            }) { monitor.notice("当前有输入法或其他窗口遮挡，已跳过画面采样"); return }
        shotPending = true; sampling.started(SystemClock.elapsedRealtime())
        if (Build.VERSION.SDK_INT < 34) rootView?.visibility = View.INVISIBLE
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onFailure(errorCode: Int) {
                shotPending = false; rootView?.visibility = View.VISIBLE
                monitor.notice("此画面暂不可采集（系统代码 $errorCode），未进行视觉核查")
            }
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val hardware = result.hardwareBuffer
                // Enter try/finally even when the service was stopped before this callback arrived.
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        ensureActive()
                        refresh()
                        if (samplingScope() != sampleScope || windowId != capturedWindow ||
                            !app.captureSettings.isPermissionCurrent(state.currentPackage, permission)) return@launch
                        val previous = if (manual) null else sampling.previousFingerprint(SystemClock.elapsedRealtime())
                        val encoded = withContext(Dispatchers.Default) {
                            val wrapped = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
                            try {
                                val bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
                                try { MonitorFrameEncoder.encode(bitmap, if (Build.VERSION.SDK_INT >= 34) null else capturedBounds, previous) }
                                finally { bitmap.recycle() }
                            } finally { wrapped?.recycle() }
                        }
                        refresh()
                        if (encoded == null || samplingScope() != sampleScope || windowId != capturedWindow ||
                            !app.captureSettings.isPermissionCurrent(state.currentPackage, permission)) return@launch
                        sampling.sampled(sampleScope, encoded.image == null)
                        val image = encoded.image ?: return@launch
                        val accepted = monitor.submitFrame(state.currentPackage, state.screenText, image, token, at, manual) { success ->
                            scope.launch { sampling.completed(sampleScope, encoded.fingerprint, success, SystemClock.elapsedRealtime()) }
                        }
                        if (!accepted) sampling.completed(sampleScope, encoded.fingerprint, false, SystemClock.elapsedRealtime())
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        monitor.notice("画面采样未完成：${e.message?.take(80).orEmpty()}")
                    } finally { hardware.close(); shotPending = false; rootView?.visibility = View.VISIBLE }
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) service.takeScreenshotOfWindow(capturedWindow, service.mainExecutor, callback)
            else service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, callback)
        } catch (_: Exception) {
            shotPending = false; rootView?.visibility = View.VISIBLE; monitor.notice("系统拒绝画面采集，请检查辅助功能授权")
        }
    }
    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).roundToInt()
    private fun show() {
        if (rootView != null) return
        val view = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        val ball = TextView(service).apply {
            text = "茧"; textSize = 17f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); alpha = 0.72f
            contentDescription = "破茧监测悬浮球，点击核查或查看提醒，拖动调整位置"
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(47, 100, 167)) }
        }
        view.addView(ball, LinearLayout.LayoutParams(dp(44), dp(44)))
        val box = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); visibility = View.GONE
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.WHITE) }
        }
        val label = TextView(service).apply { textSize = 14f; setTextColor(Color.DKGRAY); maxLines = 8 }
        box.addView(label, LinearLayout.LayoutParams(dp(248), WindowManager.LayoutParams.WRAP_CONTENT))
        fun button(title: String, action: () -> Unit) {
            val button = Button(service).apply { text = title; setOnClickListener { action() } }
            actions += button; box.addView(button)
        }
        button("核查当前内容") { monitor.checkCurrent(); label.text = "已请求核查当前内容，结果可能稍后出现在发现页。" }
        button("查看发现") {
            service.startActivity(Intent(service, MainActivity::class.java).putExtra("monitor_discover", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)); box.visibility = View.GONE
        }
        button("暂停全部监测") { app.captureSettings.setEnabled(false); app.audioCoordinator.stopAll(); hide() }
        view.addView(box)
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        ball.setOnClickListener { expand(monitor.state.value.findings.firstOrNull {
            it.contextToken == monitor.state.value.contextToken && it.contextToken.isNotBlank()
        }, false) }
        ball.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = position.x; startY = position.y; touchX = event.rawX; touchY = event.rawY; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX; val dy = event.rawY - touchY
                    moved = moved || abs(dx) > slop || abs(dy) > slop
                    if (moved) {
                        box.visibility = View.GONE
                        position.x = (startX + dx.toInt()).coerceIn(0, (service.resources.displayMetrics.widthPixels - dp(44)).coerceAtLeast(0))
                        position.y = (startY + dy.toInt()).coerceIn(0, (service.resources.displayMetrics.heightPixels - dp(80)).coerceAtLeast(0))
                        runCatching { manager.updateViewLayout(view, position) }
                    }; true
                }
                MotionEvent.ACTION_UP -> { if (!moved) ball.performClick(); true }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        try { manager.addView(view, position); rootView = view; bubble = ball; panel = box; detail = label }
        catch (_: Exception) { monitor.notice("悬浮球无法显示，请检查辅助功能服务状态") }
    }
    private fun expand(finding: MonitorFinding?, automatic: Boolean) {
        val text = finding?.let { "${it.label}\n${it.claim}\n${it.explanation.take(160)}" }
            ?: "当前内容暂无核查结果。可点击“核查当前内容”；没有提醒不表示内容真实。"
        detail?.text = text; detail?.maxLines = if (automatic) 3 else 8
        actions.forEach { it.visibility = if (automatic) View.GONE else View.VISIBLE }
        panel?.visibility = View.VISIBLE
        position.x = position.x.coerceAtMost((service.resources.displayMetrics.widthPixels - dp(276)).coerceAtLeast(0))
        rootView?.let { runCatching { manager.updateViewLayout(it, position) } }
        closePanel?.cancel()
        if (automatic) closePanel = scope.launch { delay(monitor.config.value.displaySeconds * 1000L); panel?.visibility = View.GONE }
    }
    private fun hide() {
        closePanel?.cancel(); rootView?.let { runCatching { manager.removeView(it) } }
        rootView = null; bubble = null; panel = null; detail = null; actions.clear()
    }
    fun interrupt() { clearScreen(); hide() }
    fun stop() { monitor.bindManual(null); clearScreen(); scope.cancel(); hide() }
}
