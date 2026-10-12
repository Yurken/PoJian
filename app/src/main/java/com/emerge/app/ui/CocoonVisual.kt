package com.emerge.app.ui

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.math.*

/** Visual density is supplied by the caller; this renderer does not infer risk. */
@Composable
internal fun CocoonVisual(density: Float?, dark: Boolean, playing: Boolean, modifier: Modifier = Modifier) {
    val systemMotion = rememberSystemMotionEnabled()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val moving = playing && density != null && systemMotion && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val amount by animateFloatAsState(density?.coerceIn(0f, 1f) ?: 0f, tween(if (systemMotion) 400 else 0), label = "cocoon-density")
    val phase = remember { mutableFloatStateOf(0f) }
    var touch by remember { mutableStateOf(Offset.Zero) }
    var pressed by remember { mutableStateOf(false) }
    val tiltX by animateFloatAsState(if (moving) touch.x else 0f, tween(if (systemMotion) 250 else 0), label = "cocoon-tilt-x")
    val tiltY by animateFloatAsState(if (moving) touch.y else 0f, tween(if (systemMotion) 250 else 0), label = "cocoon-tilt-y")
    val compression by animateFloatAsState(
        if (moving && pressed) 1f else 0f,
        if (moving) spring(dampingRatio = .62f, stiffness = 260f) else tween(0),
        label = "cocoon-press")
    LaunchedEffect(moving) {
        if (moving) {
            var previous = 0L
            while (true) withInfiniteAnimationFrameNanos { now ->
                if (previous != 0L) phase.floatValue += ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
                previous = now
            }
        }
    }
    val strandPath = remember { Path() }
    val star = remember { Path() }
    Canvas(modifier.testTag("cocoon-visual").semantics {
        contentDescription = density?.let { "观点集中度 ${String.format(java.util.Locale.CHINA, "%.1f", it * 100)}%" } ?: "观察不足"
    }.pointerInput(moving) {
        if (moving) detectTapGestures(onPress = { point ->
            touch = Offset((point.x / size.width - .5f) * 2, (point.y / size.height - .5f) * 2)
            pressed = true
            try { tryAwaitRelease() } finally { touch = Offset.Zero; pressed = false }
        })
    }) {
        val unit = size.minDimension
        val rx = unit * .22f
        val ry = unit * .33f
        if (density == null) {
            drawOval((if (dark) Color(0xFF64748B) else Color(0xFF94A3B8)).copy(alpha = .4f),
                Offset(size.width / 2 - rx, size.height / 2 - ry), Size(rx * 2, ry * 2), style = Stroke(unit * .002f))
            return@Canvas
        }
        val time = if (systemMotion) phase.floatValue else 0f
        val visibility = 1 - amount
        val gold = if (dark) Color(0xFFFBBF24) else Color(0xFFD97706)
        val teal = if (dark) Color(0xFF2DD4BF) else Color(0xFF0D9488)
        val silk = if (dark) Color(0xFFCBD5E1) else Color(0xFF334155)
        val bright = if (dark) Color(0xFFFEF08A) else Color(0xFF1E3A8A)
        // One shared clock keeps breathing, silk and drift frozen together when paused.
        val breath = sin(time * 1.5f)
        val driftX = sin(time * .72f) * unit * .012f
        val driftY = sin(time * 1.08f) * unit * .018f
        val sway = sin(time * .72f) * 2.4f
        translate(size.width / 2 + driftX + tiltX * unit * .04f,
            size.height / 2 + driftY + tiltY * unit * .04f) {
            withTransform({
                rotate((-0.06f + tiltX * .05f) * 180f / PI.toFloat() + sway, Offset.Zero)
                scale(1f + breath * .018f - compression * .045f,
                    1f - breath * .012f - compression * .025f, Offset.Zero)
            }) {
                if (visibility > .005f) {
                    val radius = (rx * .72f + breath * unit * .007f) * (.85f + visibility * .2f) * 1.4f
                    drawCircle(Brush.radialGradient(
                        0f to gold.copy(alpha = visibility * .95f),
                        .45f to teal.copy(alpha = visibility * .7f),
                        1f to teal.copy(alpha = 0f), center = Offset.Zero, radius = radius),
                        radius, Offset.Zero)
                    val r = unit * .035f
                    star.reset()
                    star.moveTo(0f, -r)
                    star.quadraticTo(r * .22f, -r * .22f, r, 0f)
                    star.quadraticTo(r * .22f, r * .22f, 0f, r)
                    star.quadraticTo(-r * .22f, r * .22f, -r, 0f)
                    star.quadraticTo(-r * .22f, -r * .22f, 0f, -r)
                    star.close()
                    drawPath(star, Color.White.copy(alpha = visibility * .95f))
                }
                val strandCount = amount * 160
                val active = ceil(strandCount).toInt()
                for (front in listOf(false, true)) {
                    for (i in 0 until active) {
                        val strandAlpha = (strandCount - i).coerceIn(0f, 1f)
                        val type = i % 4
                        val tilt = sin(i * 1.7f) * .35f
                        val speed = (.3f + (i % 5) * .12f) * if (type == 1) -1 else 1
                        val angle = i * 2.399963f + time * speed * .42f + tiltX * .4f
                        strandPath.reset()
                        var pen = false
                        // Interpolate along the existing silk rather than adding a second particle field.
                        val tracing = front && i % 13 == 0
                        val traceSample = ((time * .12f + i * .618034f) % 1f) * 38f
                        var previousX = 0f
                        var previousY = 0f
                        var previousZ = 0f
                        var trace: Offset? = null
                        var traceDepth = 0f
                        for (j in 0..38) {
                            val t = j / 38f * PI.toFloat()
                            val x: Float
                            val y: Float
                            val z: Float
                            if (type < 2) {
                                y = -cos(t) * ry
                                val waist = sin(t) * (1 - .12f * cos(t))
                                val u = angle + t * if (type == 0) 2.3f else -2.1f
                                x = cos(u) * rx * waist
                                z = sin(u) * rx * waist
                            } else if (type == 2) {
                                val u = t * 2 + angle
                                y = tilt * ry * 1.6f + sin(u * 2) * unit * .018f
                                val radius = rx * sqrt(max(.1f, 1 - (y / ry).pow(2)))
                                x = cos(u) * radius
                                z = sin(u) * radius
                            } else {
                                val v = t * 2 + angle
                                y = sin(v) * ry
                                val radius = rx * sqrt(max(.1f, 1 - (y / ry).pow(2)))
                                x = sin(tilt) * cos(v) * radius
                                z = cos(tilt) * cos(v) * radius
                            }
                            if (tracing && j > 0 && traceSample >= j - 1 && traceSample < j) {
                                val blend = traceSample - (j - 1)
                                trace = Offset(previousX + (x - previousX) * blend, previousY + (y - previousY) * blend)
                                traceDepth = previousZ + (z - previousZ) * blend
                            }
                            previousX = x
                            previousY = y
                            previousZ = z
                            if ((z >= 0) != front) { pen = false; continue }
                            if (pen) strandPath.lineTo(x, y) else strandPath.moveTo(x, y)
                            pen = true
                        }
                        drawPath(strandPath, (if (i % 6 == 0) bright else silk).copy(
                            alpha = (if (front) .35f + amount * .55f else .14f) * strandAlpha),
                            style = Stroke(unit * if (front) .0018f + amount * .002f else .0012f, cap = StrokeCap.Round))
                        if (front && traceDepth > 0f) trace?.let { point ->
                            val depthAlpha = (traceDepth / (rx * .4f)).coerceIn(0f, 1f)
                            val edgeAlpha = sin(traceSample / 38f * PI.toFloat()).coerceIn(0f, 1f)
                            drawCircle(bright.copy(alpha = .8f * depthAlpha * edgeAlpha * strandAlpha),
                                unit * .006f, point)
                        }
                    }
                    if (!front && amount > .72f) {
                        val shell = if (dark) Color(0xFF111827) else Color(0xFFF1F5F9)
                        drawOval(shell.copy(alpha = ((amount - .72f) / .28f).coerceIn(0f, 1f) * if (dark) .96f else .94f),
                            Offset(-rx * .94f, -ry * .96f), Size(rx * 1.88f, ry * 1.92f))
                    }
                }
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
