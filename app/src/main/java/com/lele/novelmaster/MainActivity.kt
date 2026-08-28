package com.lele.novelmaster

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lele.novelmaster.ui.AiScreen
import com.lele.novelmaster.ui.AutoWriteScreen
import com.lele.novelmaster.ui.CardsScreen
import com.lele.novelmaster.ui.ChaptersScreen
import com.lele.novelmaster.ui.EditorScreen
import com.lele.novelmaster.ui.ExportScreen
import com.lele.novelmaster.ui.HomeScreen
import com.lele.novelmaster.ui.NovelTheme
import com.lele.novelmaster.ui.ProjectScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NovelTheme { AppNav() }
        }
    }
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen(nav) }
        composable("project/{id}") { e ->
            ProjectScreen(nav, e.arguments?.getString("id")?.toLongOrNull() ?: 0L)
        }
        composable("cards/{id}") { e ->
            CardsScreen(nav, e.arguments?.getString("id")?.toLongOrNull() ?: 0L)
        }
        composable("chapters/{id}") { e ->
            ChaptersScreen(nav, e.arguments?.getString("id")?.toLongOrNull() ?: 0L)
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
