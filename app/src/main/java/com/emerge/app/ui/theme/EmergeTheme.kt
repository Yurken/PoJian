package com.emerge.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PaperColors = lightColorScheme(
    primary = Color(0xFF102F2A),
    onPrimary = Color(0xFFF9F9F8),
    primaryContainer = Color(0xFFE3ECE6),
    onPrimaryContainer = Color(0xFF173E32),
    secondary = Color(0xFF496A75),
    onSecondary = Color(0xFFF9F9F8),
    secondaryContainer = Color(0xFFE6ECEE),
    onSecondaryContainer = Color(0xFF284D58),
    tertiary = Color(0xFF88631D),
    onTertiary = Color(0xFFFFF8EA),
    tertiaryContainer = Color(0xFFF1E8D5),
    onTertiaryContainer = Color(0xFF624614),
    background = Color(0xFFF9F9F8),
    onBackground = Color(0xFF202B25),
    surface = Color(0xFFFCFCFA),
    onSurface = Color(0xFF202B25),
    surfaceVariant = Color(0xFFF0F2EC),
    onSurfaceVariant = Color(0xFF5F6B65),
    surfaceTint = Color(0xFF102F2A),
    surfaceBright = Color(0xFFFCFCFA),
    surfaceDim = Color(0xFFE2E5DF),
    surfaceContainerLowest = Color(0xFFFCFCFA),
    surfaceContainerLow = Color(0xFFF4F5F0),
    surfaceContainer = Color(0xFFEEF0EA),
    surfaceContainerHigh = Color(0xFFE8EBE4),
    surfaceContainerHighest = Color(0xFFE2E5DF),
    outline = Color(0xFF6F7C73),
    outlineVariant = Color(0xFFD6DBD5),
    error = Color(0xFF9E3932),
    onError = Color(0xFFFFF8F6),
    errorContainer = Color(0xFFF7E2DE),
    onErrorContainer = Color(0xFF6F2722),
    inverseSurface = Color(0xFF2A332D),
    inverseOnSurface = Color(0xFFF1F3ED),
    inversePrimary = Color(0xFFADD2BF)
)

private val GraphiteColors = darkColorScheme(
    primary = Color(0xFFB1D5C4),
    onPrimary = Color(0xFF173C2F),
    primaryContainer = Color(0xFF2C483A),
    onPrimaryContainer = Color(0xFFD1E7D9),
    secondary = Color(0xFFABC8D0),
    onSecondary = Color(0xFF203F49),
    secondaryContainer = Color(0xFF304B55),
    onSecondaryContainer = Color(0xFFD0E3E8),
    tertiary = Color(0xFFDCC18D),
    onTertiary = Color(0xFF433211),
    tertiaryContainer = Color(0xFF4C4029),
    onTertiaryContainer = Color(0xFFF1DDAF),
    background = Color(0xFF171C1B),
    onBackground = Color(0xFFE8EBE5),
    surface = Color(0xFF1D2321),
    onSurface = Color(0xFFE8EBE5),
    surfaceVariant = Color(0xFF2A322D),
    onSurfaceVariant = Color(0xFFB7C2B8),
    surfaceTint = Color(0xFFB1D5C4),
    surfaceBright = Color(0xFF343D36),
    surfaceDim = Color(0xFF171C1B),
    surfaceContainerLowest = Color(0xFF121714),
    surfaceContainerLow = Color(0xFF202725),
    surfaceContainer = Color(0xFF252D29),
    surfaceContainerHigh = Color(0xFF2C342F),
    surfaceContainerHighest = Color(0xFF333D36),
    outline = Color(0xFF89968A),
    outlineVariant = Color(0xFF414C43),
    error = Color(0xFFEBB1A9),
    onError = Color(0xFF591E19),
    errorContainer = Color(0xFF63332D),
    onErrorContainer = Color(0xFFF4D5CF),
    inverseSurface = Color(0xFFE0E6DC),
    inverseOnSurface = Color(0xFF29322B),
    inversePrimary = Color(0xFF244B39)
)

private fun body(size: Int, lineHeight: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp
)

private fun display(size: Int, lineHeight: Int) = body(size, lineHeight, FontWeight.Medium)
    .copy(fontFamily = FontFamily.Serif)

private val EmergeTypography = Typography(
    displayLarge = display(48, 56),
    displayMedium = display(40, 48),
    displaySmall = display(36, 44),
    headlineLarge = display(32, 40),
    headlineMedium = display(28, 36),
    headlineSmall = display(24, 32),
    titleLarge = body(20, 28, FontWeight.SemiBold),
    titleMedium = body(16, 24, FontWeight.SemiBold),
    titleSmall = body(14, 22, FontWeight.SemiBold),
    bodyLarge = body(16, 26),
    bodyMedium = body(14, 23),
    bodySmall = body(12, 20),
    labelLarge = body(14, 20, FontWeight.Medium),
    labelMedium = body(12, 18, FontWeight.Medium),
    labelSmall = body(11, 16, FontWeight.Medium)
)

private val EmergeShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp)
)

/** Uses fixed brand colors while following the phone's light or dark appearance. */
@Composable
fun EmergeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) GraphiteColors else PaperColors,
        typography = EmergeTypography,
        shapes = EmergeShapes,
        content = content
    )
}
