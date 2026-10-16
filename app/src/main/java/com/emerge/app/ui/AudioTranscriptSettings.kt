package com.emerge.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Settings observes the same persisted sessions as the transcript detail, including failures. */
@Composable
internal fun AudioTranscriptSettings(sessions: List<AudioSessionUi>, onOpenRecords: () -> Unit,
    onSession: (AudioSessionUi) -> Unit, embedded: Boolean = false) {
    SettingsContentFrame(embedded, Modifier.testTag("settings-audio-transcripts")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (!embedded) Text("录音转文字", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            PojianTextButton(onClick = onOpenRecords, modifier = Modifier.testTag("settings-audio-records")) {
                Text("全部记录")
            }
        }
        MetadataText("${sessions.size} 条记录 · 已保存 ${sessions.sumOf { it.savedSegmentCount }} 段文字")
        val latest = sessions.maxWithOrNull(compareBy<AudioSessionUi> { it.createdAt }.thenBy { it.id })
        if (latest == null) MetadataText("暂无转写记录")
        else {
            HorizontalDivider()
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "查看转写") { onSession(latest) }
                .padding(vertical = 8.dp).testTag("settings-audio-latest"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SourceAppText(latest.sourcePackage, latest.title, style = MaterialTheme.typography.bodyMedium,
                    color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("${latest.statusLabel} · 已保存 ${latest.savedSegmentCount} 段文字",
                    color = if (latest.errorMessage != null) MaterialTheme.colorScheme.error else Ink,
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("settings-audio-status"))
                MetadataText("${audioDate(latest.createdAt)} · ${latest.sourceLabel}")
                val excerpt = audioTranscriptPreview(latest)
                if (excerpt.isNotBlank()) AudioSourceContent(fromAudio = true) {
                    Text(excerpt, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                latest.errorMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
