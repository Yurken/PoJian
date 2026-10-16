package com.emerge.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

/** Decorative brand mark; the adjacent page title provides the header's accessible name. */
@Composable
internal fun SwissBrandLogo(modifier: Modifier = Modifier) {
    Canvas(modifier.size(28.dp).background(DesignTokens.Ink, RoundedCornerShape(7.dp))) {
        scale(size.width / 48f, size.height / 48f, pivot = Offset.Zero) {
            val left = Path().apply {
                moveTo(19.5f, 11.5f)
                cubicTo(12.5f, 16.5f, 12.5f, 31.5f, 19.5f, 36.5f)
                cubicTo(21.5f, 38f, 23f, 38.5f, 24f, 38.5f)
            }
            val right = Path().apply {
                moveTo(28.5f, 11.5f)
                cubicTo(35.5f, 16.5f, 35.5f, 31.5f, 28.5f, 36.5f)
                cubicTo(26.5f, 38f, 25f, 38.5f, 24f, 38.5f)
            }
            drawPath(left, Color.White, style = Stroke(2.5f, cap = StrokeCap.Round))
            drawPath(right, Color.White, style = Stroke(2.5f, cap = StrokeCap.Round))
            drawLine(DesignTokens.Amber, Offset(24f, 27f), Offset(24f, 8f), 2.5f, StrokeCap.Round)
            drawCircle(DesignTokens.Amber, 3f, Offset(24f, 7f))
        }
    }
}
