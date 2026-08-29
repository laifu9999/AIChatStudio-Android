package com.lele.novelmaster

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lele.novelmaster.ui.AiScreen
import com.lele.novelmaster.ui.AutoWriteScreen
import com.lele.novelmaster.ui.BookshelfScreen
import com.lele.novelmaster.ui.CardsScreen
import com.lele.novelmaster.ui.ChatScreen
import com.lele.novelmaster.ui.ChaptersScreen
import com.lele.novelmaster.ui.ContextPreviewScreen
import com.lele.novelmaster.ui.EditorScreen
import com.lele.novelmaster.ui.ExportScreen
import com.lele.novelmaster.ui.FilesScreen
import com.lele.novelmaster.ui.NovelTheme
import com.lele.novelmaster.ui.ProjectScreen
import com.lele.novelmaster.ui.ReaderScreen

class MainActivity : ComponentActivity() {

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* 结果不影响主流程 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        requestNeededPermissions()
        setContent {
            NovelTheme { AppNav() }
        }
    }

    /** 首次启动自动弹出所需权限（存储/通知），无需用户去设置里手动开 */
    private fun requestNeededPermissions() {
        val wanted = mutableListOf<String>()
        fun need(p: String) =
            ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT <= 32) {
            if (need(Manifest.permission.WRITE_EXTERNAL_STORAGE)) wanted += Manifest.permission.WRITE_EXTERNAL_STORAGE
            if (need(Manifest.permission.READ_EXTERNAL_STORAGE)) wanted += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= 33 && need(Manifest.permission.POST_NOTIFICATIONS)) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        if (wanted.isNotEmpty()) permLauncher.launch(wanted.toTypedArray())
    }
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "chat") {
        // 主入口：豆包/元宝风格聊天界面
        composable("chat") { ChatScreen(nav) }
        composable("project/{id}") { e ->
            ProjectScreen(nav, e.arguments?.getString("id")?.toLongOrNull() ?: 0L)
        }
        composable("cards/{id}") { e ->
            CardsScreen(nav, e.arguments?.getString("id")?.toLongOrNull() ?: 0L)
        }
        composable("chapters/{id}") { e ->
            ChaptersScreen(nav, e.arguments?.getString("id")?.toLongOrNull() ?: 0L)
        }
        composable("preview/{id}") { e ->
            ContextPreviewScreen(nav, e.arguments?.getString("id")?.toLongOrNull() ?: 0L)
        }
        composable("files/{id}") { e ->
            FilesScreen(nav, e.arguments?.getString("id")?.toLongOrNull() ?: 0L)
        }
        composable("shelf") { BookshelfScreen(nav) }
        composable("reader/{id}") { e ->
            ReaderScreen(nav, e.arguments?.getString("id")?.toLongOrNull() ?: 0L)
        }
        composable("editor/{chapterId}") { e ->
            EditorScreen(nav, e.arguments?.getString("chapterId")?.toLongOrNull() ?: 0L)
        }
        composable("ai") { AiScreen(nav) }
        composable("autowrite/{id}") { e ->
            AutoWriteScreen(nav, e.arguments?.getString("id")?.toLongOrNull() ?: 0L)
        }
        composable("export/{id}") { e ->
            ExportScreen(nav, e.arguments?.getString("id")?.toLongOrNull() ?: 0L)
        }
    }
}
