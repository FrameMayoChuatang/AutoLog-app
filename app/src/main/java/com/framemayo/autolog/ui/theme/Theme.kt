package com.framemayo.autolog.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = darkColorScheme(
    primary = Color.White,
    onPrimary = Color(0xFF17181D),
    onBackground = Color(0xFFF4F5F7),
    onSurface = Color(0xFFF4F5F7),
    onSurfaceVariant = Color(0xFFB9BEC8),
    outline = Color(0xFF696B75),
    background = Color(0xFF0B0C0F),
    surface = Color(0xFF17181D),
)

@Composable
fun AutoLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
