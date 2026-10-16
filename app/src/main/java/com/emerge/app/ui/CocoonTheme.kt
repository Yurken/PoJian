package com.emerge.app.ui

import kotlin.math.abs
import kotlin.math.floor

/** Scalar colors keep the prototype's mapping identical in Compose and the native overlay. */
internal data class CocoonTheme(
    val hue: Int,
    val saturation: Int,
    val lightness: Int,
    val statusText: String
) {
    val colorArgb: Int get() = hslArgb(hue, saturation, lightness)
    val backgroundArgb: Int get() = hslArgb(hue, saturation, 96)
    val borderArgb: Int get() = hslArgb(hue, saturation - 10, 84)
}

/** Reproduces computeCocoonTheme(pct) from the supplied HTML, including its thresholds. */
internal fun computeCocoonTheme(percent: Float): CocoonTheme {
    require(percent.isFinite()) { "A measured, finite percentage is required" }
    val p = percent.toDouble().coerceIn(0.0, 100.0)
    return when {
        p < 40 -> CocoonTheme(jsRound(158 - p / 40 * 18), 78, 38, "多元均衡")
        p < 70 -> CocoonTheme(jsRound(140 - (p - 40) / 30 * 102), 88, 44, "轻度集中")
        else -> CocoonTheme(jsRound(38 - (p - 70) / 30 * 34), 84, 48,
            if (p >= 85) "重度茧房预警" else "重度同质化")
    }
}

private fun jsRound(value: Double): Int = floor(value + 0.5).toInt()

private fun hslArgb(hue: Int, saturation: Int, lightness: Int): Int {
    val s = saturation / 100.0
    val l = lightness / 100.0
    val chroma = (1 - abs(2 * l - 1)) * s
    val segment = hue / 60.0
    val x = chroma * (1 - abs(segment % 2 - 1))
    val (r, g, b) = when {
        segment < 1 -> Triple(chroma, x, 0.0)
        segment < 2 -> Triple(x, chroma, 0.0)
        segment < 3 -> Triple(0.0, chroma, x)
        segment < 4 -> Triple(0.0, x, chroma)
        segment < 5 -> Triple(x, 0.0, chroma)
        else -> Triple(chroma, 0.0, x)
    }
    val m = l - chroma / 2
    fun channel(value: Double) = jsRound((value + m) * 255).coerceIn(0, 255)
    return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}
