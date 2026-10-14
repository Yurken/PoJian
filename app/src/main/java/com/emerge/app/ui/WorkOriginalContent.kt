package com.emerge.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.emerge.app.EmergeApplication
import com.emerge.app.data.BrowseEvent
import com.emerge.app.data.OriginalLinks
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@Composable
internal fun WorkOriginalContent(event: BrowseEvent) {
    if (!OriginalLinks.supports(event.packageName)) return
    val app = LocalContext.current.applicationContext as? EmergeApplication ?: return
    val dao = app.database.workOriginalDao()
    val key = event.workKey
    val source by remember(key) { key?.let(dao::observe) ?: flowOf(null) }.collectAsState(initial = null)
    val work by remember(key) { key?.let(dao::observeWork) ?: flowOf(null) }.collectAsState(initial = null)
    val url = source?.url?.takeIf { OriginalLinks.allowed(it, event.packageName) } ?: work?.let(OriginalLinks::forWork)
    var edit by rememberSaveable(event.id) { mutableStateOf(false) }
    var expanded by rememberSaveable(event.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag("work-original-content"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("原作", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            PojianTextButton(onClick = { edit = true }, modifier = Modifier.testTag("work-original-link")) {
                Text(if (url == null) "添加链接" else "修改链接")
            }
            if (url != null) PojianTextButton(onClick = { expanded = true }) { Text("全屏") }
        }
        if (url == null) MetadataText("原作链接未保存")
        else if (!expanded) key(url) { OriginalPage(url, Modifier.fillMaxWidth().height(420.dp)) }
    }
    if (expanded && url != null) Dialog(onDismissRequest = { expanded = false },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("原作", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    PojianTextButton(onClick = { expanded = false }) { Text("关闭") }
                }
                key(url) { OriginalPage(url, Modifier.fillMaxWidth().weight(1f)) }
            }
        }
    }
    if (edit) OriginalLinkDialog(url.orEmpty(), event.packageName, onDismiss = { edit = false }) { input ->
        app.repository.saveOriginal(event.id, input)
    }
}

@Composable
private fun OriginalLinkDialog(initial: String, packageName: String, onDismiss: () -> Unit,
    save: suspend (String) -> Boolean) {
    var input by rememberSaveable { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("原作链接") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, onValueChange = { input = it.take(16000); error = null },
                label = { Text("分享链接") }, modifier = Modifier.testTag("work-original-input"),
                minLines = 2, maxLines = 5, enabled = !busy, isError = error != null)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        TextButton(enabled = !busy && input.isNotBlank(), onClick = {
            if (OriginalLinks.fromInput(input, packageName) == null) error = "请输入该平台的单个作品链接"
            else scope.launch {
                busy = true
                try {
                    if (save(input)) onDismiss() else error = "记录已删除或无法保存"
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "保存失败" }
                finally { busy = false }
            }
        }) { Text("保存") }
    }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") } })
}

/** Render the actual platform page so its video, gallery order and article layout stay intact. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun OriginalPage(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var browser by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var externalFailed by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var fullscreen by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    fun leaveFullscreen() {
        val callback = fullscreenCallback
        fullscreenCallback = null
        fullscreen = null
        callback?.onCustomViewHidden()
    }
    DisposableEffect(owner, browser) {
        val current = browser
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                current?.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause();});", null)
                current?.onPause()
            }
            if (event == Lifecycle.Event.ON_RESUME) current?.onResume()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) { onDispose { leaveFullscreen() } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            if (failed || externalFailed) Text(if (failed) "原作加载失败" else "无法打开链接", Modifier.weight(1f),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            else Spacer(Modifier.weight(1f))
            if (failed) PojianTextButton(onClick = { retry++; loading = true; failed = false }) { Text("重试") }
            PojianTextButton(onClick = {
                externalFailed = runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.isFailure
            }) { Text("在原平台打开") }
        }
        key(url, retry) {
            AndroidView(modifier = Modifier.fillMaxWidth().weight(1f).testTag("work-original-page"), factory = { ctx ->
                WebView(ctx).apply {
                    browser = this
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.mediaPlaybackRequiresUserGesture = true
                    settings.setSupportMultipleWindows(false)
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                            !OriginalLinks.canNavigate(request.url.toString())
                        override fun onPageStarted(view: WebView, url: String?, icon: android.graphics.Bitmap?) {
                            loading = true; failed = false
                        }
                        override fun onPageFinished(view: WebView, url: String?) { loading = false }
                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) { failed = true; loading = false }
                        }
                        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                            if (request.isForMainFrame && response.statusCode >= 400) { failed = true; loading = false }
                        }
                        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
                            handler.cancel(); failed = true; loading = false
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                            if (fullscreen != null) callback.onCustomViewHidden()
                            else { fullscreen = view; fullscreenCallback = callback }
                        }
                        override fun onHideCustomView() { leaveFullscreen() }
                    }
                    // Let the platform page handle its gallery and vertical scroll inside the sheet.
                    setOnTouchListener { view, event ->
                        view.parent?.requestDisallowInterceptTouchEvent(event.actionMasked !in setOf(
                            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL))
                        false
                    }
                    if (OriginalLinks.canNavigate(url)) loadUrl(url) else { failed = true; loading = false }
                }
            }, onRelease = { view ->
                leaveFullscreen()
                if (browser === view) browser = null
                view.stopLoading()
                view.onPause()
                view.loadUrl("about:blank")
                view.removeAllViews()
                view.destroy()
            })
        }
    }
    fullscreen?.let { video ->
        Dialog(onDismissRequest = { leaveFullscreen() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            BackHandler { leaveFullscreen() }
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                FrameLayout(ctx).apply {
                    (video.parent as? ViewGroup)?.removeView(video)
                    addView(video, FrameLayout.LayoutParams(-1, -1))
                }
            }, onRelease = { it.removeAllViews() })
        }
    }
}
