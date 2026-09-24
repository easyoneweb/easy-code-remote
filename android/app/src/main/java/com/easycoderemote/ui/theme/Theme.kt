package com.easycoderemote.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Cyan40,
    onPrimary = Color.White,
    primaryContainer = CyanLightContainer,
    onPrimaryContainer = CyanOnLightContainer,
    secondary = Neutral10,
    secondaryContainer = Neutral90,
    tertiary = Green40,
    error = Red40,
    background = Color.White,
    surface = Color.White,
    surfaceVariant = Neutral90,
)

private val DarkColors = darkColorScheme(
    primary = Cyan80,
    onPrimary = CyanOnDarkContainer,
    primaryContainer = CyanDarkContainer,
    onPrimaryContainer = CyanOnDarkContainer,
    secondary = Neutral95,
    secondaryContainer = Neutral20,
    tertiary = Green80,
    error = Red80,
    background = Color(0xFF101418),
    surface = Color(0xFF141218),
    surfaceVariant = Neutral20,
)

private val AppTypography = Typography()

@Composable
fun EasyCodeRemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}