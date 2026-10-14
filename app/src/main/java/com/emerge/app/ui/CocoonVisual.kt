package com.emerge.app.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
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
import kotlin.math.sin

/** The ring represents the existing concentration share, not a new diversity score. */
@Composable
internal fun CocoonVisual(density: Float?, dark: Boolean, playing: Boolean, modifier: Modifier = Modifier) {
    val systemMotion = rememberSystemMotionEnabled()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val moving = playing && density != null && systemMotion && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val amount by animateFloatAsState(density?.coerceIn(0f, 1f) ?: 0f,
        tween(if (moving) 600 else 0), label = "cocoon-density")
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(moving) {
        if (moving) {
            var previous = 0L
            while (true) withInfiniteAnimationFrameNanos { now ->
                if (previous != 0L) phase.floatValue += ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
                previous = now
            }
        }
    }
    Canvas(modifier.testTag("cocoon-visual").semantics {
        contentDescription = density?.let { "观点集中度 ${String.format(java.util.Locale.CHINA, "%.1f", it * 100)}%" } ?: "观察不足"
    }) {
        val stroke = 8.dp.toPx()
        val diameter = (size.minDimension - stroke * 2).coerceAtLeast(0f)
        val origin = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val bounds = Size(diameter, diameter)
        val accent = if (dark) Color(0xFF2DD4BF) else Color(0xFF0D9488)
        drawArc(if (dark) Color.White.copy(alpha = .14f) else Color(0xFFE2E8F0),
            0f, 360f, false, origin, bounds, style = Stroke(stroke, cap = StrokeCap.Round))
        if (density != null) {
            val glow = .08f + (sin(phase.floatValue * 1.5f) + 1f) * .035f
            drawArc(accent.copy(alpha = glow), -90f, amount * 360f, false, origin, bounds,
                style = Stroke(stroke * 1.8f, cap = StrokeCap.Round))
            drawArc(accent, -90f, amount * 360f, false, origin, bounds,
                style = Stroke(stroke, cap = StrokeCap.Round))
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
