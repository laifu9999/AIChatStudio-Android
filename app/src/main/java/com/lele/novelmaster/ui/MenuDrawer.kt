package com.lele.novelmaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lele.novelmaster.data.Repo
import com.lele.novelmaster.tools.Tools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 全屏覆盖式抽屉（豆包/元宝风格：右上图标打开一个浮层菜单）。
 * 提供：项目仪表盘、设定卡、章节列表、AI模型、自动写作、导出、新建项目、清空聊天。
 */
@Composable
fun MenuDrawer(onClose: () -> Unit, onNav: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var showCreate by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("玄幻") }
    var total by remember { mutableStateOf("300") }
    var words by remember { mutableStateOf("2500") }

    val projects by Repo.dao.projectsFlow().collectAsState(initial = emptyList())
    var currentPid by remember { mutableStateOf(0L) }
    LaunchedEffect(projects) { if (currentPid == 0L) currentPid = projects.firstOrNull()?.id ?: 0L }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 80.dp)
                .background(Color(0xFFF6F4FB))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF6750A4))
                        .padding(12.dp)
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.ArrowBack, null, tint = Color.White)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text("  功能菜单", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))

                if (showCreate) {
                    Card(
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text("开新书（创建项目）", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("书名") })
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = genre, onValueChange = { genre = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("类型（玄幻/都市/科幻/...）") })
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = total, onValueChange = { total = it.filter { c -> c.isDigit() } }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("目标总章数") })
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = words, onValueChange = { words = it.filter { c -> c.isDigit() } }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("每章目标字数") })
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { showCreate = false }) { Text("取消") }
                                TextButton(onClick = {
                                    if (title.isBlank()) return@TextButton
                                    scope.launch(Dispatchers.IO) {
                                        Tools.createProject(
                                            title = title, genre = genre, desc = "",
                                            totalCh = total.toIntOrNull() ?: 300,
                                            chWords = words.toIntOrNull() ?: 2500
                                        )
                                    }
                                    showCreate = false
                                    onClose()
                                }) { Text("创建", color = Color(0xFF6750A4)) }
                            }
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        item { Spacer(Modifier.height(4.dp)) }
                        item {
                            MenuNavRow("📚  项目仪表盘") { onNav("project/$currentPid") }
                            MenuNavRow("🗂  设定卡管理") { onNav("cards/$currentPid") }
                            MenuNavRow("📖  章节列表 / 阅读") { onNav("chapters/$currentPid") }
                            MenuNavRow("✍️  自动写作控制台") { onNav("autowrite/$currentPid") }
                            MenuNavRow("🤖  AI 模型（添加 / 测试）") { onNav("ai") }
                            MenuNavRow("📤  导出 / 发布") { onNav("export/$currentPid") }
                        }

                        item {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "  数据管理",
                                color = Color.Gray,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                            )
                        }

                        item {
                            MenuActionRow("📕  开新书（创建项目）") { showCreate = true }
                            MenuActionRow("🧹  清空当前项目的聊天记录") {
                                scope.launch(Dispatchers.IO) {
                                    if (currentPid > 0L) Repo.dao.clearMessages(currentPid)
                                }
                            }
                            MenuActionRow("🗑  清空未关联项目的聊天记录") {
                                scope.launch(Dispatchers.IO) {
                                    Repo.dao.clearMessages(0L)
                                }
                            }
                        }

                        item {
                            Spacer(Modifier.height(20.dp))
                            Text(
                                "  💡 聊天里说「开新书」「写下一章」「列出设定卡」「开始自动写作 1 到 50」「停止写作」也能直接做。",
                                fontSize = 12.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuNavRow(title: String, action: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { action() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { Text(title, fontSize = 16.sp) }
}

@Composable
private fun MenuActionRow(title: String, action: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { action() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { Text(title, fontSize = 15.sp) }
}
