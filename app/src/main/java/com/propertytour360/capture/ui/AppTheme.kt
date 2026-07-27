package com.propertytour360.capture.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF0A6670),
    secondary = Color(0xFFD68120),
    tertiary = Color(0xFF355C7D)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF75D6DD),
    secondary = Color(0xFFFFB86B)
)

@Composable
fun PropertyTourTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
