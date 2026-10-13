package com.emerge.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.emerge.app.R
import com.emerge.app.data.BrowseEvent
import com.emerge.app.data.contentText

/** Reuse the cleaned display while retaining the event's original evidence snapshot. */
@Composable
internal fun rememberOriginalText(event: BrowseEvent): String = remember(event.visibleText, event.sourceType) { event.contentText }

/** Mark actual transcripts without inferring their source from the quoted words. */
@Composable
internal fun AudioSourceContent(fromAudio: Boolean, content: @Composable () -> Unit) {
    if (!fromAudio) {
        content()
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Icon(painterResource(R.drawable.ic_recording), contentDescription = "音频转写", tint = Accent,
            modifier = Modifier.padding(top = 3.dp).size(16.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}
