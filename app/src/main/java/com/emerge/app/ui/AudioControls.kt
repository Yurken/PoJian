package com.emerge.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.emerge.app.audioanalysis.TextServiceConfig

data class AudioActions(val onImport: () -> Unit, val onCapture: () -> Unit)

@Composable
fun rememberAudioActions(model: AudioViewModel, apps: List<CaptureApp>, onChooseScope: () -> Unit): AudioActions {
    val context = LocalContext.current
    var chooseApp by rememberSaveable { mutableStateOf(false) }
    var pendingPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.importAudio(uri)
    }
    val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val packageName = pendingPackage
        pendingPackage = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null && packageName != null)
            model.startPlayback(packageName, result.resultCode, result.data!!)
        else model.notify("未授权，可重试或导入音频")
    }
    fun requestProjection() {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        projection.launch(if (Build.VERSION.SDK_INT >= 34)
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        else manager.createScreenCaptureIntent())
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestProjection() else { pendingPackage = null; model.notify("请授权录音或导入音频") }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) requestProjection()
        else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    if (chooseApp) AlertDialog(onDismissRequest = { chooseApp = false }, title = { Text("选择要录制的应用") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("录制应用声音，最长 5 分钟。")
            apps.forEach { app -> OutlinedButton(onClick = {
                chooseApp = false; pendingPackage = app.packageName
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                else requestProjection()
            }, modifier = Modifier.fillMaxWidth()) { Text(app.label) } }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { chooseApp = false }) { Text("取消") } })
    return AudioActions(onImport = { importer.launch(arrayOf("audio/*", "video/*")) }, onCapture = {
        if (apps.isEmpty()) { model.notify("请先在设置中选择要记录的应用"); onChooseScope() }
        else chooseApp = true
    })
}

@Composable
fun AudioCloudDialog(config: TextServiceConfig, onDismiss: () -> Unit,
    onSave: (TextServiceConfig, () -> Unit, (String) -> Unit) -> Unit) {
    var enabled by remember { mutableStateOf(config.enabled) }
    var endpoint by remember { mutableStateOf(config.endpoint) }
    var token by remember { mutableStateOf(config.token) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("云端文字分析") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("开启后发送转写与相关历史文字，不上传原声。")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("启用文字服务", modifier = Modifier.weight(1f)); Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !saving)
            }
            OutlinedTextField(endpoint, { endpoint = it }, label = { Text("服务地址") }, placeholder = { Text("https://your-service.example") },
                singleLine = true, enabled = !saving, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(token, { token = it }, label = { Text("访问令牌") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !saving)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !saving, onClick = {
            saving = true; error = null; onSave(TextServiceConfig(enabled, endpoint, token), onDismiss) { error = it; saving = false }
        }) { Text(if (saving) "保存中" else "保存") } },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}
