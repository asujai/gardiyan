package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val GuardDarkColorScheme = darkColorScheme(
    primary = PureWhite,
    onPrimary = PureBlack,
    secondary = MutedGray,
    onSecondary = PureWhite,
    background = PureBlack,
    onBackground = PureWhite,
    surface = DarkCharcoal,
    onSurface = PureWhite,
    outline = BorderGray,
    error = DangerRed,
    onError = PureWhite
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = GuardDarkColorScheme,
        typography = Typography,
        content = content
    )
}
