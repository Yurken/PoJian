package com.emerge.app.monitoring

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.emerge.app.EmergeApplication
import com.emerge.app.capture.WorkHint
import com.emerge.app.capture.WorkPage
import com.emerge.app.data.BrowseEvent
import kotlinx.coroutines.*

/** Local consent is independent of the cloud gateway. Never submits these bytes to MonitorRuntime. */
internal class LocalThumbnailCapture(private val service: AccessibilityService, private val app: EmergeApplication,
    private val scope: CoroutineScope, private val cloudPending: () -> Boolean) {
    @Volatile var revision = 0L
        private set
    @Volatile private var active: ThumbnailContext? = null
    @Volatile private var observedEvent: BrowseEvent? = null
    @Volatile var pending = false
        private set
    private var lastAttempt = 0L

    fun invalidate() { revision++; active = null; observedEvent = null }

    fun currentEvent(pkg: String, window: Int): BrowseEvent? {
        val context = active ?: return null
        return observedEvent?.takeIf { context.revision == revision && context.packageName == pkg &&
            context.windowId == window && app.captureSettings.isPermissionCurrent(pkg, context.permission) &&
            it.id == context.eventId && it.workKey == context.workKey }
    }

    fun observed(event: BrowseEvent, hint: WorkHint, window: Int, expectedRevision: Long) {
        if (revision != expectedRevision || hint.page in setOf(WorkPage.COMMENTS, WorkPage.NON_CONTENT)) return
        val permission = app.captureSettings.permissionToken(event.packageName) ?: return
        val key = event.workKey ?: return
        val context = ThumbnailContext(event.id, key, event.packageName, window, revision, permission,
            app.captureSettings.thumbnailRevision)
        observedEvent = event
        active = context
        if (Build.VERSION.SDK_INT < 30 || pending || cloudPending() || !valid(context) ||
            event.identityMethod !in setOf("platform_id", "author_title")) return
        val existing = app.workThumbnails.thumbnails.value[key]
        if (existing != null && existing.kind != "page_screenshot") return
        val elapsed = SystemClock.elapsedRealtime()
        if (elapsed - lastAttempt < 3000) return
        val inspected = inspect(context) ?: return
        if (existing != null && inspected.content == null) return
        lastAttempt = elapsed
        capture(context, event, hint, inspected)
    }

    private fun valid(context: ThumbnailContext): Boolean = context.matches(active, revision,
        app.captureSettings.permissionToken(context.packageName), app.captureSettings.thumbnailRevision,
        app.captureSettings.localThumbnails.value) &&
        service.getSystemService(PowerManager::class.java).isInteractive &&
        !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private data class Page(val window: Rect, val visible: Rect, val content: Rect?)

    /** An incomplete privacy scan is unsafe. Inspect inputs even after the text extraction limit. */
    @Suppress("DEPRECATION")
    private fun inspect(context: ThumbnailContext): Page? {
        if (!valid(context)) return null
        val root = service.rootInActiveWindow ?: return null
        val windows = service.windows
        try {
            if (root.packageName?.toString() != context.packageName || root.windowId != context.windowId) return null
            val target = windows.firstOrNull { it.id == context.windowId } ?: return null
            val window = Rect().also(target::getBoundsInScreen)
            val visible = Rect().also(root::getBoundsInScreen)
            if (!visible.intersect(window) || visible.isEmpty) return null
            // Window screenshots exclude overlays, but an obscured/private page isn't representative.
            if (windows.any { other ->
                val area = Rect().also(other::getBoundsInScreen)
                val ownOverlay = if (Build.VERSION.SDK_INT >= 34 && other.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                    val overlayRoot = other.root
                    try { overlayRoot?.packageName?.toString() == service.packageName } finally { overlayRoot?.recycle() }
                } else false
                !ownOverlay && other.id != context.windowId && Rect.intersects(area, visible) &&
                    (other.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD || other.isFocused ||
                        other.isActive || other.layer > target.layer)
            }) return null
            var nodes = 0
            var unsafe = false
            val candidates = mutableListOf<Rect>()
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (++nodes > 1500 || depth > 40) { unsafe = true; return }
                val area = Rect().also(node::getBoundsInScreen)
                if (node.isVisibleToUser && Rect.intersects(area, visible)) {
                    if (node.isPassword || node.isEditable) unsafe = true
                    if (node.viewIdResourceName?.substringAfterLast('/') in setOf(
                            "note_image", "note_content_image", "video_view", "player_view", "video_surface") &&
                        area.intersect(visible) && area.width() >= 160 && area.height() >= 160) candidates += area
                }
                for (i in 0 until node.childCount) {
                    if (unsafe) break
                    val child = node.getChild(i) ?: continue
                    try { visit(child, depth + 1) } finally { child.recycle() }
                }
            }
            visit(root, 0)
            if (unsafe) return null
            // Multiple image regions could be a feed/grid; only one unambiguous content region is cropped.
            return Page(window, visible, candidates.distinct().singleOrNull())
        } finally { root.recycle(); windows.forEach { it.recycle() } }
    }

    @androidx.annotation.RequiresApi(30)
    private fun capture(context: ThumbnailContext, event: BrowseEvent, hint: WorkHint, page: Page) {
        pending = true
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onFailure(errorCode: Int) {
                pending = false
                if (valid(context)) app.workThumbnails.captureFailed()
            }
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val hardware = result.hardwareBuffer
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        ensureActive()
                        if (!valid(context) || inspect(context) != page) return@launch
                        val encoded = withContext(Dispatchers.Default) {
                            val wrapped = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
                            try {
                                val bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false) ?: return@withContext null
                                try {
                                    val crop = Rect(page.content ?: page.visible)
                                    if (Build.VERSION.SDK_INT >= 34) {
                                        // Don't guess coordinate scaling for a resized/rotating window.
                                        if (bitmap.width != page.window.width() || bitmap.height != page.window.height()) return@withContext null
                                        crop.offset(-page.window.left, -page.window.top)
                                    }
                                    if (!Rect(0, 0, bitmap.width, bitmap.height).contains(crop)) return@withContext null
                                    val kind = if (page.content == null) "page_screenshot"
                                        else if (hint.page == WorkPage.VIDEO) "video_frame" else "content_crop"
                                    ThumbnailEncoder.encode(bitmap, crop, kind)
                                } finally { bitmap.recycle() }
                            } finally { wrapped?.recycle() }
                        } ?: return@launch
                        if (!valid(context) || inspect(context) != page) return@launch
                        app.workThumbnails.save(event, encoded, context.consent) { valid(context) }
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        if (valid(context)) app.workThumbnails.captureFailed()
                    } finally { hardware.close(); pending = false }
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) service.takeScreenshotOfWindow(context.windowId, service.mainExecutor, callback)
            else service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, callback)
        } catch (_: Exception) { pending = false; if (valid(context)) app.workThumbnails.captureFailed() }
    }
}
