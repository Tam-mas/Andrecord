package com.andrecord.app.ui.theme

import androidx.compose.ui.graphics.Color

object AndrecordColors {
    val Ink900 = Color(0xFF14181F)
    val Ink600 = Color(0xFF3A4150)
    val Paper50 = Color(0xFFF6F3EC)
    val Brass500 = Color(0xFFC89B3C)
}

val SpeakerColors = listOf(
    Color(0xFF4FA3A0), // teal
    Color(0xFFC97064), // rose
    Color(0xFF7C87C9), // periwinkle
    Color(0xFF7C9A5C), // moss
)

fun speakerColorFor(speakerIndex: Int): Color = SpeakerColors[speakerIndex % SpeakerColors.size]
