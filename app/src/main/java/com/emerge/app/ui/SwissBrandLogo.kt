package com.emerge.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.emerge.app.R

/** Decorative brand mark; the adjacent page title provides the header's accessible name. */
@Composable
internal fun SwissBrandLogo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.mipmap.ic_launcher_artwork),
        contentDescription = null,
        modifier = modifier.size(28.dp).background(DesignTokens.Surface, RoundedCornerShape(7.dp))
    )
}
