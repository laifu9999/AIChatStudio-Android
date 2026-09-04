package com.lele.mobipaint.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Screen { Shelf, Chat, Chapters, Settings, SettingMgr, Editor, Reader, Publish }

/** 全局导航状态（单 Activity + 状态切换，简单可靠）。 */
object Nav {
    var screen by mutableStateOf(Screen.Shelf)
    var pid by mutableStateOf(0L)
    var bookTitle by mutableStateOf("")
    /** 跨页定位章节号（写作台/阅读器打开时定位；0 = 自动定位最新一章） */
    var chapterNo by mutableStateOf(0)
}
