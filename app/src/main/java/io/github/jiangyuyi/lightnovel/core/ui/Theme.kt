package io.github.jiangyuyi.lightnovel.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import io.github.jiangyuyi.lightnovel.core.preferences.AppPreferences
import io.github.jiangyuyi.lightnovel.core.preferences.AppThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF17636A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBDE5E5),
    onPrimaryContainer = Color(0xFF07363B),
    secondary = Color(0xFF9A4D3D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD0),
    onSecondaryContainer = Color(0xFF3C0B05),
    background = Color(0xFFF7F9F8),
    surface = Color(0xFFF7F9F8),
    surfaceVariant = Color(0xFFE1EAE8),
    onSurfaceVariant = Color(0xFF3F4A49),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF83D3D3),
    onPrimary = Color(0xFF003739),
    primaryContainer = Color(0xFF15565B),
    onPrimaryContainer = Color(0xFFBDEBED),
    secondary = Color(0xFFFFB4A1),
    onSecondary = Color(0xFF5B180F),
    secondaryContainer = Color(0xFF7D3024),
    onSecondaryContainer = Color(0xFFFFDAD0),
    background = Color(0xFF101918),
    surface = Color(0xFF101918),
    surfaceVariant = Color(0xFF354443),
    onSurfaceVariant = Color(0xFFC0CCCA),
)

@Composable
fun LightNovelTheme(
    preferences: AppPreferences = AppPreferences(),
    content: @Composable () -> Unit,
) {
    val dark = when (preferences.themeMode) {
        AppThemeMode.SYSTEM -> isSystemInDarkTheme()
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }
    val baseTypography = androidx.compose.material3.Typography()
    val scale = preferences.uiScale.factor
    val typography = baseTypography.scale(scale)
    CompositionLocalProvider(
        LocalAppUiScale provides scale,
        LocalAppIconScale provides preferences.iconScale.factor,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = typography,
            content = content,
        )
    }
}

val LocalAppUiScale = compositionLocalOf { 1f }
val LocalAppIconScale = compositionLocalOf { 1f }

private fun androidx.compose.material3.Typography.scale(factor: Float) = copy(
    displayLarge = displayLarge.scale(factor),
    displayMedium = displayMedium.scale(factor),
    displaySmall = displaySmall.scale(factor),
    headlineLarge = headlineLarge.scale(factor),
    headlineMedium = headlineMedium.scale(factor),
    headlineSmall = headlineSmall.scale(factor),
    titleLarge = titleLarge.scale(factor),
    titleMedium = titleMedium.scale(factor),
    titleSmall = titleSmall.scale(factor),
    bodyLarge = bodyLarge.scale(factor),
    bodyMedium = bodyMedium.scale(factor),
    bodySmall = bodySmall.scale(factor),
    labelLarge = labelLarge.scale(factor),
    labelMedium = labelMedium.scale(factor),
    labelSmall = labelSmall.scale(factor),
)

private fun TextStyle.scale(factor: Float): TextStyle = copy(
    fontSize = if (fontSize != TextUnit.Unspecified) fontSize * factor else fontSize,
    lineHeight = if (lineHeight != TextUnit.Unspecified) lineHeight * factor else lineHeight,
)

