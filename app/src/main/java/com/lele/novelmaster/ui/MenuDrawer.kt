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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lele.novelmaster.data.Repo
import com.lele.novelmaster.tools.Tools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private val BrandTop = Color(0xFF6750A4)
private val BrandBottom = Color(0xFF8B5CF6)

/**
 * 豆包式会话历史抽屉：
 *  - ＋ 新会话（= 新建小说）
 *  - 会话列表：点按切换（每个会话=一本小说，数据完全隔离），🗑 删除（级联清数据）
 *  - 功能入口：仪表盘/设定卡/章节/上下文预览/自动写作/AI模型/导出
 */
@Composable
fun MenuDrawer(
    currentPid: Long,
    onClose: () -> Unit,
    onSelect: (Long) -> Unit,
    onNav: (String) -> Unit,
    onNewSession: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val projects by Repo.dao.projectsFlow().collectAsState(initial = emptyList())
    var deleteTarget by remember { mutableStateOf<Long?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable { onClose() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 64.dp)
                .background(Color(0xFFF7F5FC))
                .clickable(enabled = false) { }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部渐变头
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(BrandTop, BrandBottom)))
                        .padding(horizontal = 16.dp, vertical = 18.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("乐乐写小说", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, null, tint = Color.White) }
                    }
                    Spacer(Modifier.height(10.dp))
                    // 新会话按钮
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                            .clickable { onNewSession() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("＋  新建会话（新小说）", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                }

                // 会话列表
                Text(
                    "  会话记录（${projects.size}）",
                    color = Color(0xFF8A8698), fontSize = 12.sp,
                    modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp)
                )
                LazyColumn(Modifier.weight(1f)) {
                    if (projects.isEmpty()) {
                        item {
                            Text(
                                "  还没有会话\n  点上方「新建会话」或在聊天里描述你的灵感",
                                color = Color(0xFF8A8698), fontSize = 13.sp, lineHeight = 20.sp,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                    items(projects.size) { i ->
                        val p = projects[i]
                        val selected = p.id == currentPid
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (selected) Color(0xFFEEE4FF) else Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clickable { onSelect(p.id) }
                                    .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "《${p.title}》",
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 15.sp, color = Color(0xFF1F1B2E),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${p.genre} · ${p.targetChapters}章",
                                        fontSize = 11.sp, color = Color(0xFF8A8698)
                                    )
                                }
                                IconButton(onClick = { deleteTarget = p.id }) {
                                    Text("🗑", fontSize = 15.sp)
                                }
                            }
                        }
                    }
                }

                // 功能入口
                Column(Modifier.padding(bottom = 16.dp)) {
                    Text(
                        "  功能",
                        color = Color(0xFF8A8698), fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp)
                    )
                    FunRow("📚 项目仪表盘") { onNav("project/$currentPid") }
                    FunRow("🗂 设定卡管理") { onNav("cards/$currentPid") }
                    FunRow("📖 章节列表 / 阅读") { onNav("chapters/$currentPid") }
                    FunRow("🔍 上下文注入预览") { onNav("preview/$currentPid") }
                    FunRow("✍️ 自动写作控制台") { onNav("autowrite/$currentPid") }
                    FunRow("🤖 AI 模型（添加 / 测试）") { onNav("ai") }
                    FunRow("📤 导出 / 发布") { onNav("export/$currentPid") }
                }
            }
        }
    }

    // 删除会话确认
    if (deleteTarget != null) {
        val pid = deleteTarget!!
        val name = projects.firstOrNull { it.id == pid }?.title ?: ""
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话") },
            text = { Text("确定删除《$name》？\n该会话的章节、设定卡、聊天记录将一并删除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { Tools.deleteProject(pid) }
                    deleteTarget = null
                }) { Text("删除", color = Color(0xFFB91C1C)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun FunRow(title: String, action: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { action() }
            .padding(horizontal = 20.dp, vertical = 11.dp)
    ) { Text(title, fontSize = 15.sp, color = Color(0xFF1F1B2E)) }
}
