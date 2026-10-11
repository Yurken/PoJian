package com.emerge.app.ui

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.graphics.drawable.toBitmap
import com.emerge.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Only local package identities are used; website titles are never guessed as app identities. */
private object SourceAppIcons {
    private val bitmaps = LruCache<String, ImageBitmap>(64)

    fun load(context: Context, packageName: String, configuration: String): ImageBitmap? = runCatching {
        val manager = context.packageManager
        // Check installation/version even on a cache hit, so removed or updated apps cannot reuse old artwork.
        val version = manager.getPackageInfo(packageName, 0).lastUpdateTime
        val key = "$packageName|$version|$configuration"
        bitmaps.get(key) ?: manager.getApplicationIcon(packageName)
            .toBitmap(width = 128, height = 128).asImageBitmap().also { bitmaps.put(key, it) }
    }.getOrNull()
}

@Composable
internal fun SourceAppIcon(packageName: String, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val configurationKey = "${configuration.densityDpi}|${configuration.uiMode}"
    key(packageName, configurationKey) {
        val icon by produceState<ImageBitmap?>(null, context) {
            value = withContext(Dispatchers.IO) { SourceAppIcons.load(context, packageName, configurationKey) }
        }
        Box(modifier.size(size).clip(RoundedCornerShape(22))
            .background(if (icon == null) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent),
            contentAlignment = Alignment.Center) {
            if (icon != null) Image(icon!!, contentDescription = null, modifier = Modifier.fillMaxSize())
            else Icon(painterResource(R.drawable.ic_source_app), contentDescription = null,
                modifier = Modifier.fillMaxSize(0.65f), tint = Muted)
        }
    }
}

/** Inline placement keeps the logo next to the app name, even when metadata precedes it or wraps. */
@Composable
internal fun SourceAppText(
    packageName: String?, text: String, modifier: Modifier = Modifier, appName: String = text,
    style: TextStyle = MaterialTheme.typography.bodySmall, color: Color = Muted,
    maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Clip
) {
    if (packageName.isNullOrBlank() || packageName == "__other__" || packageName == "音频导入") {
        Text(text, modifier = modifier, style = style, color = color, maxLines = maxLines, overflow = overflow)
        return
    }
    val annotated = remember(text, appName) {
        val offset = text.indexOf(appName).coerceAtLeast(0)
        buildAnnotatedString {
            append(text.substring(0, offset))
            appendInlineContent("source-app-icon")
            append(text.substring(offset))
        }
    }
    Text(annotated, modifier = modifier.clearAndSetSemantics { this.text = AnnotatedString(text) },
        style = style, color = color, maxLines = maxLines, overflow = overflow,
        inlineContent = mapOf("source-app-icon" to InlineTextContent(
            Placeholder(1.25.em, 1.em, PlaceholderVerticalAlign.TextCenter)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                SourceAppIcon(packageName, Modifier.fillMaxHeight().aspectRatio(1f))
            }
        }))
}
