package com.emerge.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CocoonThemeTest {
    @Test fun preservesPrototypeHslAtEveryThreshold() {
        val expected = listOf(
            Sample(0f, 158, 78, 38, "多元均衡"),
            Sample(39f, 140, 78, 38, "多元均衡"),
            Sample(40f, 140, 88, 44, "轻度集中"),
            Sample(69f, 41, 88, 44, "轻度集中"),
            Sample(70f, 38, 84, 48, "重度同质化"),
            Sample(84f, 22, 84, 48, "重度同质化"),
            Sample(85f, 21, 84, 48, "重度茧房预警"),
            Sample(100f, 4, 84, 48, "重度茧房预警")
        )
        expected.forEach { sample ->
            val actual = computeCocoonTheme(sample.percent)
            assertEquals("hue at ${sample.percent}", sample.hue, actual.hue)
            assertEquals(sample.saturation, actual.saturation)
            assertEquals(sample.lightness, actual.lightness)
            assertEquals(sample.status, actual.statusText)
        }
    }

    @Test fun usesPrototypeRoundingForFractionalPercentages() {
        assertEquals(154, computeCocoonTheme(10f).hue)
        assertEquals(107, computeCocoonTheme(49.75f).hue)
        assertEquals(36, computeCocoonTheme(72f).hue)
    }

    @Test fun clampsFiniteInputsAndDoesNotTreatUnknownAsZero() {
        assertEquals(computeCocoonTheme(0f), computeCocoonTheme(-15f))
        assertEquals(computeCocoonTheme(100f), computeCocoonTheme(125f))
        assertThrows(IllegalArgumentException::class.java) { computeCocoonTheme(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { computeCocoonTheme(Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { computeCocoonTheme(Float.NEGATIVE_INFINITY) }
    }

    @Test fun convertsHslToOpaqueArgbForBothComposeAndNativeOverlay() {
        val green = computeCocoonTheme(0f)
        assertEquals(0xFF15AC75.toInt(), green.colorArgb)
        assertEquals(0xFFEDFDF7.toInt(), green.backgroundArgb)
        assertEquals(0xFFBAF2DE.toInt(), green.borderArgb)
        val red = computeCocoonTheme(100f)
        assertEquals(0xFFE12114.toInt(), red.colorArgb)
        assertEquals(0xFFFDEDEC.toInt(), red.backgroundArgb)
        assertNotEquals(green.colorArgb, red.colorArgb)
    }

    private data class Sample(
        val percent: Float,
        val hue: Int,
        val saturation: Int,
        val lightness: Int,
        val status: String
    )
}
