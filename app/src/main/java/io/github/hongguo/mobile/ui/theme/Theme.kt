// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CoralRed = Color(0xFFFF3B5C)
val CoralRedDark = Color(0xFFE02849)
val GoldAccent = Color(0xFFFFB84D)
val BgBlack = Color(0xFF0D0D0F)
val SurfaceDark = Color(0xFF18181D)
val SurfaceElevated = Color(0xFF23232B)
val TextPrimary = Color(0xFFF5F5F7)
val TextSecondary = Color(0xFFA0A3B1)

private val DarkColors = darkColorScheme(
    primary = CoralRed,
    onPrimary = Color.White,
    secondary = GoldAccent,
    background = BgBlack,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary
)

@Composable
fun HongguoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
