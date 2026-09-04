package com.lele.mobipaint

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import com.lele.mobipaint.ui.ChatScreen
import com.lele.mobipaint.ui.ChaptersScreen
import com.lele.mobipaint.ui.MobipaintTheme
import com.lele.mobipaint.ui.Nav
import com.lele.mobipaint.ui.Screen
import com.lele.mobipaint.ui.SettingsScreen
import com.lele.mobipaint.ui.ShelfScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Db.init(this)
        setContent {
            MobipaintTheme {
                AppRoot()
            }
        }
    }
}

@androidx.compose.runtime.Composable
fun AppRoot() {
    // 从章节页返回时刷新聊天回执；从书架进入时初始化
    LaunchedEffect(Unit) { ChatEngine.reload(Nav.pid) }
    when (Nav.screen) {
        Screen.Shelf -> ShelfScreen()
        Screen.Chat -> ChatScreen()
        Screen.Chapters -> ChaptersScreen()
        Screen.Settings -> SettingsScreen()
    }
}
