package com.emerge.app.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/** The ring represents the existing concentration share, not a new diversity score. */
@Composable
@Suppress("UNUSED_PARAMETER")
internal fun CocoonVisual(density: Float?, dark: Boolean, playing: Boolean, modifier: Modifier = Modifier) {
    val knownDensity = density?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
    val systemMotion = rememberSystemMotionEnabled()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val moving = playing && knownDensity != null && systemMotion && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val amount by animateFloatAsState(knownDensity ?: 0f,
        tween(if (moving) 600 else 0), label = "cocoon-density")
    Canvas(modifier.testTag("cocoon-visual").semantics {
        contentDescription = knownDensity?.let { "观点集中度 ${String.format(java.util.Locale.CHINA, "%.1f", it * 100)}%" } ?: "观察不足"
    }) {
        val stroke = 8.dp.toPx()
        val diameter = (size.minDimension - stroke * 2).coerceAtLeast(0f)
        val origin = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val bounds = Size(diameter, diameter)
        drawArc(DesignTokens.Border, 0f, 360f, false, origin, bounds,
            style = Stroke(stroke, cap = StrokeCap.Round))
        if (knownDensity != null) {
            val accent = Color(computeCocoonTheme(amount * 100f).colorArgb)
            drawArc(accent, -90f, amount * 360f, false, origin, bounds,
                style = Stroke(stroke, cap = StrokeCap.Round))
            if (amount == 0f) {
                drawCircle(accent, stroke / 2f, Offset(size.width / 2f, origin.y))
            }
        }
    }
}

/** Observe the setting as well as lifecycle, so switching motion off takes effect immediately. */
@Composable
internal fun rememberSystemMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var enabled by remember(resolver) {
        mutableStateOf(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f)
    }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                enabled = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return enabled
}
