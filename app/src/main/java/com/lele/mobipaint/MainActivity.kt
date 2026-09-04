package com.lele.mobipaint

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.lele.mobipaint.ui.MobipaintTheme
import com.lele.mobipaint.ui.Nav
import com.lele.mobipaint.ui.Screen
import com.lele.mobipaint.ui.ChatScreen
import com.lele.mobipaint.ui.ChaptersScreen
import com.lele.mobipaint.ui.EditorScreen
import com.lele.mobipaint.ui.PublishScreen
import com.lele.mobipaint.ui.ReaderScreen
import com.lele.mobipaint.ui.SettingManagerScreen
import com.lele.mobipaint.ui.SettingsScreen
import com.lele.mobipaint.ui.ShelfScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边到边模式 + adjustResize(Manifest) + imePadding(下方) 三件套，
        // 保证聊天/写作/设定的输入框弹出键盘时不会被遮挡
        enableEdgeToEdge()
        Db.init(this)
        setContent {
            MobipaintTheme {
                // 关键修复：系统状态栏/导航栏/键盘安全留白，
                // 解决顶栏被状态栏（时间/电量）遮挡的问题
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFF6F7FB))
                        .systemBarsPadding()
                        .imePadding()
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
fun AppRoot() {
    // 从章节页返回时刷新聊天回执；从书架进入时初始化
    LaunchedEffect(Unit) { ChatEngine.reload(Nav.pid) }
    Box(modifier = Modifier.fillMaxSize()) {
        when (Nav.screen) {
            Screen.Shelf -> ShelfScreen()
            Screen.Chat -> ChatScreen()
            Screen.Chapters -> ChaptersScreen()
            Screen.Settings -> SettingsScreen()
            Screen.SettingMgr -> SettingManagerScreen()
            Screen.Editor -> EditorScreen()
            Screen.Reader -> ReaderScreen()
            Screen.Publish -> PublishScreen()
        }
    }
}
