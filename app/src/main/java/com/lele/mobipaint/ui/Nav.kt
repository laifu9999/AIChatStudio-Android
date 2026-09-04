package com.lele.mobipaint.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Screen { Shelf, Chat, Chapters, Settings }

/** 全局导航状态（单 Activity + 状态切换，简单可靠）。 */
object Nav {
    var screen by mutableStateOf(Screen.Shelf)
    var pid by mutableStateOf(0L)
    var bookTitle by mutableStateOf("")
}
