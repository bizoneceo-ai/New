package com.bizoneceo.snake8bit.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val SnakeColorScheme = darkColorScheme(
    primary = PixelLightest,
    background = PixelDarkest,
    surface = PixelDarkest,
    onPrimary = PixelDarkest,
    onBackground = PixelLightest,
    onSurface = PixelLightest,
)

@Composable
fun Snake8BitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SnakeColorScheme,
        content = content,
    )
}
