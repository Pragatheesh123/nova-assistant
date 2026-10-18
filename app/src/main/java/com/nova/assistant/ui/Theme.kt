package com.nova.assistant.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Nova {
    val Ink = Color(0xFF050505)
    val Carbon = Color(0xFF0E0E0E)
    val Graphite = Color(0xFF161616)
    val Pearl = Color(0xFFF4F1EA)
    val Pearl2 = Color(0xA3F4F1EA)
    val Pearl3 = Color(0x5CF4F1EA)
    val Line = Color(0x17FFFFFF)
    val Surface = Color(0x0BFFFFFF)

    val kicker = TextStyle(fontSize = 11.sp, letterSpacing = 3.5.sp, color = Pearl3, fontWeight = FontWeight.Normal)
    val wordmark = TextStyle(fontSize = 15.sp, letterSpacing = 8.sp, color = Pearl, fontWeight = FontWeight.Light)
    val answer = TextStyle(fontSize = 19.sp, lineHeight = 27.sp, color = Pearl, fontWeight = FontWeight.Light)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = Pearl2)
    val title = TextStyle(fontSize = 16.sp, color = Pearl, fontWeight = FontWeight.Medium)
}

@Composable
fun NovaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Nova.Pearl, onPrimary = Nova.Ink,
            background = Nova.Ink, onBackground = Nova.Pearl,
            surface = Nova.Carbon, onSurface = Nova.Pearl,
            secondary = Nova.Pearl2, outline = Nova.Line,
        ),
        content = content,
    )
}
