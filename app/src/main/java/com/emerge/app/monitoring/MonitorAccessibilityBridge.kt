package com.emerge.app.monitoring

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowInsets
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.emerge.app.EmergeApplication
import com.emerge.app.MainActivity
import com.emerge.app.R
import com.emerge.app.analysis.CognitiveAnalyzer
import com.emerge.app.analysis.EventSemantics
import com.emerge.app.data.BrowseEvent
import com.emerge.app.ui.StatisticsPeriod
import com.emerge.app.ui.computeCocoonTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.abs
import kotlin.math.roundToInt

private data class OverlayAnalysisInput(
    val events: List<BrowseEvent>,
    val semantics: List<EventSemantics>,
    val generation: Long,
    val minute: Long
)

/** Mounted only in an explicitly selected, unlocked foreground application. */
class MonitorAccessibilityBridge(private val service: AccessibilityService, private val app: EmergeApplication) {
    private val monitor get() = app.monitor
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val manager = service.getSystemService(WindowManager::class.java)
    private var rootView: LinearLayout? = null
    private var bubble: LinearLayout? = null
    private var bubbleValue: TextView? = null
    private var bubbleDot: View? = null
    private var overlayPresentation = MonitorOverlayPresentation()
    private var panelFinding: MonitorFinding? = null
    private var dragging = false
    private val appLabels = mutableMapOf<String, String>()
    private var panel: ScrollView? = null
    private var panelAutomatic = false
    private var detail: TextView? = null
    private val detailValues = mutableMapOf<String, TextView>()
    private val actions = mutableListOf<Button>()
    private var closePanel: Job? = null
    private var revision = 0L
    private val screenScope = MonitorScreenScope()
    private val sampling = FrameSamplingPolicy()
    private val screenTextRecognizer = ScreenTextRecognizer()
    private var lastMaterial: Pair<String, BrowseEvent>? = null
    private var sensitiveInputVisible = false
    private var lastContentRefresh = -400L
    private var shotPending = false
    private val localThumbnails = LocalThumbnailCapture(service, app, scope) { shotPending }
    val captureRevision: Long get() = localThumbnails.revision
    fun observed(event: BrowseEvent, hint: com.emerge.app.capture.WorkHint, window: Int, revision: Long) =
        localThumbnails.observed(event, hint, window, revision)
    fun invalidateCapture() = localThumbnails.invalidate()
    fun blockPrivatePage() { sensitiveInputVisible = true; clearScreen(); hide() }
    private var windowId = -1
    private var bounds = Rect()
    private val position = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; x = 0; y = 260 }

    fun start() {
        monitor.bindManual { scope.launch { refresh(); if (monitor.config.value.visual) screenshot(true) else monitor.submitCurrentText() } }
        observeOverlayPresentation()
        scope.launch {
            combine(monitor.state, monitor.config, app.captureSettings.enabled, app.captureSettings.allowedPackages) { state, config, enabled, packages ->
                Triple(state, config, enabled && state.currentPackage in packages)
            }
                .collect { (state, config, enabled) ->
                    if (!enabled || !config.bubble || state.currentPackage.isBlank()) hide()
                    else {
                        show()
                        if (shotPending && Build.VERSION.SDK_INT < 34) rootView?.visibility = View.INVISIBLE
                        renderOverlay()
                        val finding = state.findings.firstOrNull { it.contextToken == state.contextToken && state.contextToken.isNotBlank() }
                        if (finding != null && monitor.consumeAlert(finding)) expand(finding, automatic = true)
                    }
                }
        }
        scope.launch {
            while (isActive) {
                refresh()
                val config = monitor.config.value
                if (config.visual && app.audioCoordinator.settings.config.value.enabled) screenshot(false)
                delay(2000)
            }
        }
    }

    /** Read-only UI projection; sampling, saved records, and monitoring decisions are untouched. */
    private fun observeOverlayPresentation() {
        scope.launch {
            val selectedApp = combine(monitor.state.map { it.currentPackage }.distinctUntilChanged(),
                app.captureSettings.allowedPackages) { packageName, packages -> packageName.isNotBlank() && packageName in packages
            }.distinctUntilChanged()
            val active = combine(app.captureSettings.enabled, monitor.config.map { it.bubble }.distinctUntilChanged(), selectedApp) {
                enabled, bubbleEnabled, selected -> enabled && bubbleEnabled && selected
            }.distinctUntilChanged()
            val minute = flow {
                while (currentCoroutineContext().isActive) {
                    emit(System.currentTimeMillis() / 60_000L)
                    delay(60_000L - System.currentTimeMillis() % 60_000L)
                }
            }
            combine(app.repository.allEvents, app.repository.semantics, active, minute) { events, semantics, enabled, at ->
                if (enabled) OverlayAnalysisInput(events, semantics, app.repository.captureGeneration, at) else null
            }.distinctUntilChanged { previous, current ->
                when {
                    previous === current -> true
                    previous == null || current == null -> false
                    else -> previous.minute == current.minute && previous.generation == current.generation &&
                        previous.semantics == current.semantics && sameOverlayDisplayEvents(previous.events, current.events)
                }
            }.collectLatest { input ->
                overlayPresentation = MonitorOverlayPresentation()
                renderOverlay()
                if (input == null || input.events.isEmpty()) return@collectLatest
                delay(500)
                try {
                    val presentation = withContext(Dispatchers.Default) {
                        val report = CognitiveAnalyzer().analyze(input.events, StatisticsPeriod.WEEK,
                            System.currentTimeMillis(), checkCancellation = { coroutineContext.ensureActive() }, semantics = input.semantics)
                        coroutineContext.ensureActive()
                        monitorOverlayPresentation(report, input.events)
                    }
                    if (input.generation == app.repository.captureGeneration && app.captureSettings.enabled.value && monitor.config.value.bubble) {
                        overlayPresentation = presentation
                        renderOverlay()
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    android.util.Log.w("EmergeOverlay", "Display analysis unavailable", error)
                }
            }
        }
    }
    fun onEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() == service.packageName) return
        localThumbnails.invalidate()
        if (event.eventType in setOf(AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)) {
            revision++
            // Revoke screen binding immediately; do not wait for the next periodic sample.
            monitor.revokeScreen()
            sampling.observeContext("", SystemClock.elapsedRealtime())
            collapsePanel()
        }
        if (event.packageName?.toString() !in app.captureSettings.allowedPackages.value &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) { clearScreen(); hide() }
        // Payment dialogs can replace content without changing the window id.
        val now = SystemClock.elapsedRealtime()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && now - lastContentRefresh >= 400) {
            lastContentRefresh = now
            refresh()
        }
    }
    private fun samplingScope(): String = monitor.state.value.contextToken.let {
        if (it.isBlank()) "" else "$it:${monitor.samplingRevision}"
    }
    private fun clearScreen() {
        localThumbnails.invalidate()
        monitor.clearScreen()
        sampling.observeContext("", SystemClock.elapsedRealtime())
        lastMaterial = null
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
            val privacy = com.emerge.app.capture.PagePrivacyGuard.inspect(root, bounds)
            if (privacy.blockText) { blockPrivatePage(); return }
            val (structureText, text) = visibleText(root)
            if (com.emerge.app.capture.WorkIdentityExtractor.isRecommendationList(name, structureText)) {
                clearScreen(); hide(); return
            }
            val token = screenScope.token(name, windowId, revision, text)
            monitor.setScreen(name, text, token)
            sampling.observeContext(samplingScope(), SystemClock.elapsedRealtime())
        } finally { root.recycle() }
    }
    @Suppress("DEPRECATION")
    private fun visibleText(root: AccessibilityNodeInfo): Pair<String, String> {
        val lines = linkedSetOf<String>(); var count = 0; var characters = 0
        val content = linkedSetOf<String>()
        sensitiveInputVisible = false
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (++count > 1500 || depth > 40) { sensitiveInputVisible = true; return }
            val rectangle = Rect(); node.getBoundsInScreen(rectangle)
            if (node.isPassword || node.isEditable) {
                if (node.isVisibleToUser && Rect.intersects(rectangle, bounds)) sensitiveInputVisible = true
                return
            }
            if (node.isVisibleToUser && Rect.intersects(rectangle, bounds)) {
                if (characters < 6000) {
                    com.emerge.app.capture.PlatformTextCleaner.nodeText(node.text?.toString()?.take(2000).orEmpty(),
                        node.contentDescription?.toString()?.take(2000).orEmpty(), node.viewIdResourceName.orEmpty())
                        .lines().filter(String::isNotBlank).forEach(content::add)
                }
                listOf(node.text, node.contentDescription).forEach { value ->
                    val line = value?.toString()?.trim()?.take(2000)
                    if (characters < 6000 && !line.isNullOrBlank() && lines.add(line)) characters += line.length
                }
            }
            for (i in 0 until node.childCount) {
                if (count >= 1500) { sensitiveInputVisible = true; break }
                node.getChild(i)?.let { child -> try { visit(child, depth + 1) } finally { child.recycle() } }
            }
        }
        visit(root, 0)
        return lines.joinToString("\n").take(6000) to content.joinToString("\n").take(6000)
    }

    @Suppress("DEPRECATION")
    private fun screenshot(manual: Boolean) {
        val state = monitor.state.value
        if (state.currentPackage.isBlank() || !app.captureSettings.enabled.value || !monitor.config.value.visual || shotPending || localThumbnails.pending) return
        if (sensitiveInputVisible) { monitor.notice("输入区域可见，已跳过画面采样"); return }
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
        val capturedWork = localThumbnails.currentEvent(state.currentPackage, capturedWindow)
        val captureRevision = localThumbnails.revision
        val generation = app.repository.captureGeneration
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
                        if (sensitiveInputVisible || samplingScope() != sampleScope || windowId != capturedWindow ||
                            !app.captureSettings.isPermissionCurrent(state.currentPackage, permission)) return@launch
                        val parent = capturedWork?.takeIf { localThumbnails.revision == captureRevision }
                            ?.takeIf { app.repository.findEvent(it.id)?.workKey == it.workKey }
                        val previous = if (manual) null else sampling.previousFingerprint(SystemClock.elapsedRealtime())
                        val prepared = withContext(Dispatchers.Default) {
                            val wrapped = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
                            try {
                                val bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
                                try {
                                    val region = if (Build.VERSION.SDK_INT >= 34) null else capturedBounds
                                    val encoded = MonitorFrameEncoder.encode(bitmap, region, previous)
                                    val recognized = if (encoded.image == null) "" else try {
                                        screenTextRecognizer.recognize(bitmap, region)
                                    } catch (error: Exception) {
                                        currentCoroutineContext().ensureActive()
                                        android.util.Log.w("EmergeCapture", "Screen OCR failed", error)
                                        ""
                                    }
                                    encoded to recognized
                                }
                                finally { bitmap.recycle() }
                            } finally { wrapped?.recycle() }
                        }
                        refresh()
                        if (prepared == null || sensitiveInputVisible || samplingScope() != sampleScope || localThumbnails.revision != captureRevision || windowId != capturedWindow ||
                            !app.captureSettings.isPermissionCurrent(state.currentPackage, permission)) return@launch
                        val (encoded, recognized) = prepared
                        sampling.sampled(sampleScope, encoded.image == null)
                        val image = encoded.image ?: return@launch
                        val rawExtra = ScreenTextMaterial.additionalText(recognized, state.screenText)
                        val extra = if (rawExtra.isBlank()) "" else app.cleanCaptureText(rawExtra, state.currentPackage, generation) {
                            app.captureSettings.isPermissionCurrent(state.currentPackage, permission)
                        }
                        val material = if (extra.isBlank()) null else {
                            val retained = lastMaterial?.takeIf { it.first == sampleScope && it.second.visibleText == extra &&
                                at - it.second.timestamp in 0 until 300_000L }?.second
                            if (retained != null && app.repository.findEvent(retained.id) == retained) retained else {
                                val event = ScreenTextMaterial.event(state.currentPackage, extra, at, parent)
                                app.repository.saveCapture(event, false, generation) {
                                    app.captureSettings.isPermissionCurrent(state.currentPackage, permission)
                                }?.let { event.copy(id = it).also { saved -> lastMaterial = sampleScope to saved } }
                            }
                        }
                        if (app.repository.captureGeneration != generation || samplingScope() != sampleScope ||
                            !app.captureSettings.isPermissionCurrent(state.currentPackage, permission)) return@launch
                        val accepted = monitor.submitFrame(state.currentPackage, state.screenText, image, token, at, manual, material, boundParent = parent) { success ->
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
    private fun bubbleWidth() = dp((96 * service.resources.configuration.fontScale.coerceIn(1f, 2f)).roundToInt())

    private fun overlaySize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return (metrics.bounds.width() - insets.left - insets.right).coerceAtLeast(1) to
                (metrics.bounds.height() - insets.top - insets.bottom).coerceAtLeast(1)
        }
        val metrics = service.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun layoutOverlay() {
        val view = rootView ?: return
        val (screenWidth, screenHeight) = overlaySize()
        val width = (if (panel?.visibility == View.VISIBLE) dp(296) else bubbleWidth())
            .coerceAtMost((screenWidth - dp(8)).coerceAtLeast(1))
        val height = (screenHeight - dp(16)).coerceAtLeast(1)
        // Measure the whole expanded window, including buttons; ScrollView receives the remaining height.
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
        position.width = width
        position.height = view.measuredHeight.coerceAtMost(height)
        val right = (screenWidth - width).coerceAtLeast(0)
        position.x = if (dragging) position.x.coerceIn(0, right) else right
        position.y = position.y.coerceIn(0, (screenHeight - position.height - dp(8)).coerceAtLeast(0))
        runCatching { manager.updateViewLayout(view, position) }
    }

    private fun collapsePanel() {
        closePanel?.cancel()
        panel?.apply {
            animate().cancel()
            alpha = 1f; translationY = 0f; visibility = View.GONE
        }
        bubble?.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        layoutOverlay()
    }

    private fun show() {
        if (rootView != null) { layoutOverlay(); return }
        val textColor = Color.rgb(9, 9, 11)
        val mutedColor = Color.rgb(113, 113, 122)
        val borderColor = Color.rgb(228, 228, 231)
        val secondaryColor = Color.rgb(244, 244, 245)
        val view = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        val ball = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x1409090B),
                ContextCompat.getDrawable(service, R.drawable.bg_monitor_bubble_v6), null)
            isFocusable = true
            accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Button::class.java.name
                    info.addAction(if (panel?.visibility == View.VISIBLE && !panelAutomatic)
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                    else AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND)
                }

                override fun performAccessibilityAction(host: View, action: Int, arguments: Bundle?): Boolean {
                    if (action == AccessibilityNodeInfo.ACTION_EXPAND || action == AccessibilityNodeInfo.ACTION_COLLAPSE)
                        return host.performClick()
                    return super.performAccessibilityAction(host, action, arguments)
                }
            }
        }
        val dot = View(service)
        ball.addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { rightMargin = dp(8) })
        val value = TextView(service).apply {
            textSize = 15f; gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD)
            fontFeatureSettings = "tnum"
            includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        ball.addView(value)
        view.addView(ball, LinearLayout.LayoutParams(bubbleWidth(), LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { gravity = Gravity.RIGHT })
        val box = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat(); setColor(Color.WHITE); setStroke(dp(1), borderColor)
            }
        }
        val scroll = ScrollView(service).apply {
            visibility = View.GONE
            isFillViewport = false
            addView(box, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val stackedFields = service.resources.configuration.fontScale > 1.3f
        fun field(title: String, prominent: Boolean = false) {
            val row = LinearLayout(service).apply {
                orientation = if (stackedFields) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.TOP
                setPadding(0, dp(4), 0, dp(4))
                isScreenReaderFocusable = true
            }
            val caption = TextView(service).apply {
                text = title; textSize = 12f; setTextColor(mutedColor)
                includeFontPadding = false
                setLineSpacing(dp(2).toFloat(), 1f)
            }
            row.addView(caption, LinearLayout.LayoutParams(
                if (stackedFields) LinearLayout.LayoutParams.MATCH_PARENT else dp(88), LinearLayout.LayoutParams.WRAP_CONTENT))
            val valueView = TextView(service).apply {
                textSize = if (prominent) 20f else 14f
                setTextColor(textColor); includeFontPadding = false
                typeface = android.graphics.Typeface.create(if (prominent) "monospace" else "sans-serif-medium",
                    android.graphics.Typeface.NORMAL)
                fontFeatureSettings = "tnum"
                setLineSpacing(dp(3).toFloat(), 1f)
            }
            row.addView(valueView, LinearLayout.LayoutParams(
                if (stackedFields) LinearLayout.LayoutParams.MATCH_PARENT else 0, LinearLayout.LayoutParams.WRAP_CONTENT,
                if (stackedFields) 0f else 1f).apply {
                if (stackedFields) topMargin = dp(4) else marginStart = dp(12)
            })
            detailValues[title] = valueView
            box.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        field("当前应用")
        field("观点集中度 · 近7天", prominent = true)
        field("当前话题")
        field("重复依据")
        field("内容情绪")
        box.addView(View(service).apply { setBackgroundColor(borderColor) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8); bottomMargin = dp(8)
            })
        val label = TextView(service).apply {
            visibility = View.GONE
            textSize = 14f; setTextColor(textColor); fontFeatureSettings = "tnum"
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setLineSpacing(dp(4).toFloat(), 1f)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(secondaryColor) }
        }
        box.addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        fun button(title: String, primary: Boolean = false, action: () -> Unit) {
            val button = Button(service).apply {
                text = title; textSize = 14f; minHeight = dp(48); isAllCaps = false
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(if (primary) Color.WHITE else textColor)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                backgroundTintList = null; stateListAnimator = null; elevation = 0f
                val fill = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat(); setColor(if (primary) textColor else secondaryColor)
                }
                val mask = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.WHITE) }
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(if (primary) 0x22FFFFFF else 0x1409090B), fill, mask)
                setOnClickListener { action() }
            }
            actions += button
            box.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }
        button("核查当前内容", primary = true) {
            monitor.checkCurrent()
        }
        button("查看核查记录 →") {
            service.startActivity(Intent(service, MainActivity::class.java).putExtra("monitor_discover", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)); collapsePanel()
        }
        button("暂停记录") { app.captureSettings.setEnabled(false); app.audioCoordinator.stopAll(); hide() }
        view.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        var startX = 0; var startY = 0; var touchX = 0f; var touchY = 0f; var moved = false
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        ball.setOnClickListener {
            if (scroll.visibility == View.VISIBLE && !panelAutomatic) collapsePanel()
            else expand(monitor.state.value.findings.firstOrNull {
                it.contextToken == monitor.state.value.contextToken && it.contextToken.isNotBlank()
            }, false)
        }
        ball.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = position.x; startY = position.y; touchX = event.rawX; touchY = event.rawY; moved = false
                    ball.drawableHotspotChanged(event.x, event.y); ball.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX; val dy = event.rawY - touchY
                    moved = moved || abs(dx) > slop || abs(dy) > slop
                    if (moved) {
                        ball.isPressed = false
                        dragging = true
                        if (scroll.visibility == View.VISIBLE) collapsePanel()
                        position.x = startX + dx.toInt()
                        position.y = startY + dy.toInt()
                        layoutOverlay()
                    }; true
                }
                MotionEvent.ACTION_UP -> {
                    ball.isPressed = false
                    dragging = false
                    if (!moved) ball.performClick() else layoutOverlay()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { ball.isPressed = false; dragging = false; layoutOverlay(); true }
                else -> false
            }
        }
        try {
            manager.addView(view, position); rootView = view; bubble = ball; bubbleValue = value; bubbleDot = dot; panel = scroll; detail = label
            renderOverlay()
            layoutOverlay()
        }
        catch (_: Exception) { monitor.notice("悬浮球无法显示，请检查辅助功能服务状态") }
    }

    @Suppress("DEPRECATION")
    private fun appLabel(packageName: String): String = appLabels.getOrPut(packageName) {
        runCatching { service.packageManager.getApplicationLabel(service.packageManager.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault(packageName)
    }

    private fun renderOverlay() {
        val share = overlayPresentation.concentration
        val value = share?.let { String.format(java.util.Locale.CHINA, "%.0f%%", it * 100) } ?: "—"
        val accent = share?.let { computeCocoonTheme(it * 100).colorArgb } ?: Color.rgb(161, 161, 170)
        bubbleValue?.text = value
        bubbleValue?.setTextColor(accent)
        bubbleDot?.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
        bubble?.contentDescription = "观点集中度，近7天，$value"
        val state = monitor.state.value
        actions.firstOrNull()?.apply { isEnabled = !state.busy; text = if (state.busy) "核查中" else "核查当前内容" }
        if (panel?.visibility != View.VISIBLE) return
        val current = overlayPresentation.current(state.currentPackage, state.screenText)
        val currentFindings = state.findings.filter { it.contextToken.isNotBlank() && it.contextToken == state.contextToken }
        val finding = currentFindings.firstOrNull { it.id == panelFinding?.id } ?: currentFindings.firstOrNull()
        val topic = current?.topic ?: finding?.topic?.takeIf { it.isNotBlank() } ?: "—"
        detailValues["当前应用"]?.text = appLabel(state.currentPackage)
        detailValues["观点集中度 · 近7天"]?.text = value
        detailValues["当前话题"]?.text = topic
        detailValues["重复依据"]?.text = current?.repeatedMaterials?.let { "$it 条" } ?: "—"
        detailValues["内容情绪"]?.text = current?.emotions?.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: "—"
        detail?.visibility = if (finding == null) View.GONE else View.VISIBLE
        detail?.text = buildString {
            finding?.let {
                append("${it.label}\n${it.claim}")
                if (!panelAutomatic) append("\n${it.explanation.take(500)}")
            }
        }
        layoutOverlay()
    }

    private fun expand(finding: MonitorFinding?, automatic: Boolean) {
        val opening = panel?.visibility != View.VISIBLE
        panelFinding = finding
        detail?.maxLines = if (automatic) 9 else Int.MAX_VALUE
        panelAutomatic = automatic
        actions.forEach { it.visibility = if (automatic) View.GONE else View.VISIBLE }
        panel?.visibility = View.VISIBLE
        panel?.scrollTo(0, 0)
        renderOverlay()
        bubble?.sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        if (opening && ValueAnimator.areAnimatorsEnabled()) panel?.apply {
            animate().cancel()
            alpha = 0f; translationY = -dp(4).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(160L).start()
        }
        closePanel?.cancel()
        if (automatic) closePanel = scope.launch {
            delay(monitor.config.value.displaySeconds * 1000L)
            collapsePanel()
        }
    }
    private fun hide() {
        panel?.animate()?.cancel()
        closePanel?.cancel(); rootView?.let { runCatching { manager.removeView(it) } }
        rootView = null; bubble = null; bubbleValue = null; bubbleDot = null; panel = null; detail = null
        panelFinding = null; dragging = false; actions.clear(); detailValues.clear()
    }
    fun interrupt() { clearScreen(); hide() }
    fun stop() { monitor.bindManual(null); clearScreen(); scope.cancel(); screenTextRecognizer.close(); hide() }
}
