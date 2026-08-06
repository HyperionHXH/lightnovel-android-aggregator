package io.github.jiangyuyi.lightnovel.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF9D3A52),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E1),
    secondary = Color(0xFF72575D),
    background = Color(0xFFFFF8F6),
    surface = Color(0xFFFFF8F6),
    surfaceVariant = Color(0xFFF5DDE2),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB1C1),
    primaryContainer = Color(0xFF7D293D),
    secondary = Color(0xFFE1BDC5),
    background = Color(0xFF1B1113),
    surface = Color(0xFF1B1113),
    surfaceVariant = Color(0xFF514347),
)

@Composable
fun LightNovelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}

