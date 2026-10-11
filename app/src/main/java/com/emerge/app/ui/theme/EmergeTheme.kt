package com.emerge.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.ripple
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val FunctionalLightColors = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEFF6FF),
    onPrimaryContainer = Color(0xFF1E40AF),
    secondary = Color(0xFF0369A1),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF075985),
    tertiary = Color(0xFFC2410C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFEDD5),
    onTertiaryContainer = Color(0xFF9A3412),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF64748B),
    surfaceTint = Color(0xFF2563EB),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE2E8F0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFC),
    surfaceContainer = Color(0xFFF1F5F9),
    surfaceContainerHigh = Color(0xFFE9EFF6),
    surfaceContainerHighest = Color(0xFFE2E8F0),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFE2E8F0),
    error = Color(0xFFB91C1C),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF991B1B),
    inverseSurface = Color(0xFF1E293B),
    inverseOnSurface = Color(0xFFF8FAFC),
    inversePrimary = Color(0xFF93C5FD)
)

private val FunctionalDarkColors = darkColorScheme(
    primary = Color(0xFF93C5FD),
    onPrimary = Color(0xFF172554),
    primaryContainer = Color(0xFF172D50),
    onPrimaryContainer = Color(0xFFBFDBFE),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF123B52),
    onSecondaryContainer = Color(0xFFBAE6FD),
    tertiary = Color(0xFFFDBA74),
    onTertiary = Color(0xFF431407),
    tertiaryContainer = Color(0xFF4B2919),
    onTertiaryContainer = Color(0xFFFED7AA),
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF172235),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF263449),
    onSurfaceVariant = Color(0xFFCBD5E1),
    surfaceTint = Color(0xFF93C5FD),
    surfaceBright = Color(0xFF334155),
    surfaceDim = Color(0xFF0F172A),
    surfaceContainerLowest = Color(0xFF0B1120),
    surfaceContainerLow = Color(0xFF141F32),
    surfaceContainer = Color(0xFF1B283D),
    surfaceContainerHigh = Color(0xFF263449),
    surfaceContainerHighest = Color(0xFF334155),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFF334155),
    error = Color(0xFFFCA5A5),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF571D22),
    onErrorContainer = Color(0xFFFEE2E2),
    inverseSurface = Color(0xFFE2E8F0),
    inverseOnSurface = Color(0xFF1E293B),
    inversePrimary = Color(0xFF2563EB)
)

private fun body(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp
)

private fun display(size: Int, lineHeight: Int) = body(size, lineHeight, FontWeight.Medium)

private val EmergeTypography = Typography(
    displayLarge = display(48, 56),
    displayMedium = display(40, 48),
    displaySmall = display(36, 44),
    headlineLarge = body(28, 36, FontWeight.SemiBold),
    headlineMedium = body(24, 32, FontWeight.SemiBold),
    headlineSmall = body(22, 30, FontWeight.SemiBold),
    titleLarge = body(20, 28, FontWeight.SemiBold),
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
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/** Uses fixed brand colors while following the phone's light or dark appearance. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun EmergeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) FunctionalDarkColors else FunctionalLightColors,
        typography = EmergeTypography,
        shapes = EmergeShapes
    ) {
        CompositionLocalProvider(LocalIndication provides ripple(), LocalRippleConfiguration provides RippleConfiguration(
            rippleAlpha = RippleAlpha(draggedAlpha = 0.08f, focusedAlpha = 0.08f,
                hoveredAlpha = 0.04f, pressedAlpha = 0.06f))) {
            content()
        }
    }
}
