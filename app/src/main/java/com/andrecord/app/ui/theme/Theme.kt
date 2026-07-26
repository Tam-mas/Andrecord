package com.andrecord.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AndrecordDarkScheme = darkColorScheme(
    background = AndrecordColors.Ink900,
    surface = AndrecordColors.Ink900,
    onBackground = AndrecordColors.Paper50,
    onSurface = AndrecordColors.Paper50,
    primary = AndrecordColors.Brass500,
    onPrimary = AndrecordColors.Ink900,
    outline = AndrecordColors.Ink600,
)

@Composable
fun AndrecordTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AndrecordDarkScheme,
        typography = AndrecordTypography,
        content = content
    )
}
