package com.lele.novelmaster.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun NovelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF7C4DFF),
            secondary = Color(0xFF00BFA5),
            tertiary = Color(0xFFFF6D00)
        ),
        content = content
    )
}
