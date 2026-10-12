package com.emerge.app.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.emerge.app.ui.PojianButton
import com.emerge.app.ui.PojianDialog
import com.emerge.app.ui.PojianTextButton

@Composable
internal fun AppUpdateHost(model: UpdateViewModel = viewModel()) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val lifecycle by owner.lifecycle.currentStateAsState()
    val state by model.ui.collectAsStateWithLifecycle()
    DisposableEffect(owner, model) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.checkOnForeground() }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) model.checkOnForeground()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) model.requestInstall()
        else model.installError("未允许安装更新。")
    }
    LaunchedEffect(state?.installPending, lifecycle) {
        if (state?.installPending == true && lifecycle.isAtLeast(Lifecycle.State.RESUMED)) {
            val file = model.consumeInstall() ?: return@LaunchedEffect
            try {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                } else {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }
            } catch (_: Exception) {
                model.installError("无法打开系统安装界面，请重试。")
            }
        }
    }
    state?.let { UpdateDialog(it, model::download, model::requestInstall, model::dismiss) }
}

@Composable
internal fun UpdateDialog(state: UpdateUiState, onDownload: () -> Unit, onInstall: () -> Unit, onDismiss: () -> Unit) {
    val busy = state.stage == UpdateStage.DOWNLOADING || state.stage == UpdateStage.VERIFYING
    PojianDialog(onDismissRequest = onDismiss,
        title = { Text(when (state.stage) {
            UpdateStage.AVAILABLE -> "发现新版本"
            UpdateStage.DOWNLOADING -> "下载更新"
            UpdateStage.READY -> "更新已就绪"
            UpdateStage.VERIFYING -> "校验更新"
        }) }, text = {
            Column(Modifier.fillMaxWidth().testTag("app-update-dialog"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("v${state.release.versionName}", style = MaterialTheme.typography.titleMedium)
                if (busy) {
                    val progress = state.progress
                    if (state.stage == UpdateStage.DOWNLOADING && progress != null) {
                        LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                        Text("$progress%", style = MaterialTheme.typography.bodySmall)
                    } else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            if (!busy) PojianButton(onClick = if (state.stage == UpdateStage.READY) onInstall else onDownload) {
                Text(if (state.stage == UpdateStage.READY) "安装" else if (state.error != null) "重试" else "立即更新")
            }
        }, dismissButton = { PojianTextButton(onClick = onDismiss) { Text(if (busy) "取消" else "稍后") } })
}
