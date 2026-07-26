package com.andrecord.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.andrecord.app.R

val DisplayFont = FontFamily(Font(R.font.space_grotesk))
val BodyFont = FontFamily(Font(R.font.source_serif4))
val MonoFont = FontFamily(Font(R.font.ibm_plex_mono))

val AndrecordTypography = Typography(
    titleLarge = TextStyle(fontFamily = DisplayFont, fontSize = 24.sp),
    titleMedium = TextStyle(fontFamily = DisplayFont, fontSize = 18.sp),
    bodyLarge = TextStyle(fontFamily = BodyFont, fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = BodyFont, fontSize = 15.sp, lineHeight = 22.sp),
    labelSmall = TextStyle(fontFamily = MonoFont, fontSize = 12.sp),
)
