package com.emerge.app.ui.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SwissLightColors = lightColorScheme(
    primary = Color(0xFF09090B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF4F4F5),
    onPrimaryContainer = Color(0xFF09090B),
    secondary = Color(0xFF2563EB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFF6FF),
    onSecondaryContainer = Color(0xFF1D4ED8),
    tertiary = Color(0xFFF59E0B),
    onTertiary = Color(0xFF09090B),
    tertiaryContainer = Color(0xFFFFFBEB),
    onTertiaryContainer = Color(0xFF92400E),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF09090B),
    surface = Color.White,
    onSurface = Color(0xFF09090B),
    surfaceVariant = Color(0xFFF4F4F5),
    onSurfaceVariant = Color(0xFF52525B),
    surfaceTint = Color.Transparent,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFE4E4E7),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF4F4F5),
    surfaceContainerHigh = Color(0xFFE4E4E7),
    surfaceContainerHighest = Color(0xFFD4D4D8),
    outline = Color(0xFFA1A1AA),
    outlineVariant = Color(0xFFE4E4E7),
    error = Color(0xFFE11D48),
    onError = Color.White,
    errorContainer = Color(0xFFFFF1F2),
    onErrorContainer = Color(0xFF9F1239),
    inverseSurface = Color(0xFF09090B),
    inverseOnSurface = Color.White,
    inversePrimary = Color(0xFFD4D4D8)
)

private fun body(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp,
    fontFeatureSettings = "tnum"
)

private fun display(size: Int, lineHeight: Int) =
    body(size, lineHeight, FontWeight.SemiBold).copy(fontFamily = FontFamily.Monospace)

private val EmergeTypography = Typography(
    displayLarge = display(48, 56),
    displayMedium = display(40, 48),
    displaySmall = display(36, 44),
    headlineLarge = body(28, 36, FontWeight.SemiBold),
    headlineMedium = body(24, 32, FontWeight.SemiBold),
    headlineSmall = body(22, 30, FontWeight.SemiBold),
    titleLarge = body(18, 26, FontWeight.SemiBold),
    titleMedium = body(16, 24, FontWeight.SemiBold),
    titleSmall = body(14, 22, FontWeight.SemiBold),
    bodyLarge = body(16, 24),
    bodyMedium = body(14, 22),
    bodySmall = body(12, 18),
    labelLarge = body(14, 20, FontWeight.Medium),
    labelMedium = body(12, 18, FontWeight.Medium),
    labelSmall = body(11, 16, FontWeight.Medium)
)

private val EmergeShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

/** The requested fixed light appearance also applies when the system uses dark mode. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
fun EmergeTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = SwissLightColors, typography = EmergeTypography, shapes = EmergeShapes) {
        CompositionLocalProvider(
            LocalIndication provides ripple(),
            LocalRippleConfiguration provides RippleConfiguration(
                rippleAlpha = RippleAlpha(
                    draggedAlpha = 0.08f,
                    focusedAlpha = 0.08f,
                    hoveredAlpha = 0.04f,
                    pressedAlpha = 0.06f
                )
            )
        ) { content() }
    }
}
