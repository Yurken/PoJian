package com.emerge.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.emerge.app.data.BrowseEvent
import com.emerge.app.data.contentText

/** Reuse the cleaned display while retaining the event's original evidence snapshot. */
@Composable
internal fun rememberOriginalText(event: BrowseEvent): String = remember(event.visibleText, event.sourceType) { event.contentText }
