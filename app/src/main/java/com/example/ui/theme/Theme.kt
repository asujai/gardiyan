package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Minimalist, premium, gentle light scheme
private val GuardLightColorScheme = lightColorScheme(
    primary = Color(0xFF2EC4B6),       // Mint Green
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF4EA8DE),     // Soft Blue
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF8F9FA),    // Beautiful off-white
    onBackground = Color(0xFF212529),  // Slate black text
    surface = Color(0xFFFFFFFF),       // White card backgrounds
    onSurface = Color(0xFF212529),
    outline = Color(0xFFE9ECEF),       // Delicate border gray
    error = Color(0xFFE63946),         // Soft coral danger red
    onError = Color(0xFFFFFFFF)
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = GuardLightColorScheme,
        typography = Typography,
        content = content
    )
}
