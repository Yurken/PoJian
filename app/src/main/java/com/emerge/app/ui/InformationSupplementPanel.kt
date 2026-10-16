package com.emerge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.emerge.app.audioanalysis.TextServiceConfig
import com.emerge.app.R
import com.emerge.app.monitoring.InformationSupplementClient
import com.emerge.app.monitoring.InformationSupplementState
import com.emerge.app.monitoring.SupplementRequest
import com.emerge.app.monitoring.SupplementScopeGuard
import com.emerge.app.monitoring.WebEvidence
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Mount only after the user selects 补充资料. A context change closes rather than re-fetches. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InformationSupplementPanel(request: SupplementRequest, config: TextServiceConfig,
    authorizationKey: String, allowLocalHttp: Boolean, isCurrent: () -> Boolean,
    onRequest: () -> Unit, onDismiss: () -> Unit) {
    val guard = remember { SupplementScopeGuard(request, config, authorizationKey) }
    val currentRequest by rememberUpdatedState(request)
    val currentConfig by rememberUpdatedState(config)
    val currentAuthorization by rememberUpdatedState(authorizationKey)
    val currentCheck by rememberUpdatedState(isCurrent)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentOnRequest by rememberUpdatedState(onRequest)
    var state by remember { mutableStateOf(InformationSupplementState()) }
    var attempt by remember { mutableStateOf(0) }
    val current = {
        val accepted = guard.isCurrent(currentRequest, currentConfig, currentAuthorization) && currentCheck()
        if (!accepted) guard.cancel()
        accepted
    }
    DisposableEffect(Unit) { onDispose { guard.cancel() } }
    LaunchedEffect(request, config, authorizationKey) {
        if (!current()) { guard.cancel(); currentDismiss() }
    }
    LaunchedEffect(attempt) {
        state = InformationSupplementState()
        try {
            val result = InformationSupplementClient.retrieve(request, config, allowLocalHttp, current,
                onRequest = { currentOnRequest() })
            if (current()) state = InformationSupplementState(loading = false, sources = result.sources)
            else currentDismiss()
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                guard.cancel()
                currentDismiss()
                return@LaunchedEffect
            }
            currentCoroutineContext().ensureActive()
            if (current()) state = InformationSupplementState(loading = false,
                error = InformationSupplementClient.errorMessage(error))
            else currentDismiss()
        }
    }
    ModalBottomSheet(onDismissRequest = { guard.cancel(); onDismiss() },
        containerColor = MaterialTheme.colorScheme.background,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).testTag("information-supplement")) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("补充资料", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() })
                PojianIconButton(onClick = { guard.cancel(); onDismiss() }) {
                    Icon(Icons.Outlined.Close, contentDescription = "关闭", modifier = Modifier.size(20.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { Text(request.topic, style = MaterialTheme.typography.titleMedium) }
                if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.error?.let { error -> item {
                    Text(error, color = MaterialTheme.colorScheme.error)
                    PojianTextButton(onClick = { attempt += 1 }) { Text("重试") }
                } }
                if (!state.loading && state.error == null && state.sources.isEmpty()) item {
                    Text("暂无补充资料", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(state.sources, key = { it.url }) { source -> SupplementSource(source) }
            }
        }
    }
}

@Composable
private fun SupplementSource(source: WebEvidence) {
    val uri = LocalUriHandler.current
    var expanded by remember(source) { mutableStateOf(false) }
    var linkError by remember(source) { mutableStateOf(false) }
    WhiteCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(source.title, style = MaterialTheme.typography.titleSmall)
                Text(remember(source.url) { URI(source.url).host }, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PojianIconButton(onClick = { linkError = runCatching { uri.openUri(source.url) }.isFailure }) {
                Icon(painterResource(R.drawable.ic_open_in_new), contentDescription = "打开来源", modifier = Modifier.size(20.dp))
            }
        }
        SelectionContainer { Text(if (expanded) source.excerpt else source.quote,
            style = MaterialTheme.typography.bodyMedium) }
        if (source.excerpt != source.quote) PojianTextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起" else "正文")
        }
        if (linkError) Text("无法打开链接，请稍后重试", color = MaterialTheme.colorScheme.error)
    }
}
