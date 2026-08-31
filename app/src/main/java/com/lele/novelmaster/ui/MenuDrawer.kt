package com.lele.novelmaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
 * 会话抽屉 v5.1：会话列表 + 功能全部放在一个可滚动列表里（小屏也不截断）。
 * 点击遮罩关闭；会话可切换/删除；入口含书架与阅读器。
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
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 头部（渐变 + 新会话）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(BrandTop, BrandBottom)))
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("乐乐写小说", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, null, tint = Color.White) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                            .clickable { onNewSession() }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) { Text("＋  新建会话（新小说）", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                }

                // 全部内容：一个 LazyColumn，显示不完可滚动
                LazyColumn(Modifier.fillMaxSize()) {
                    // 会话列表
                    item { SectionLabel("会话记录（${projects.size}） · 每个会话=一本书") }
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
                                    Text("${p.genre} · ${p.targetChapters}章", fontSize = 11.sp, color = Color(0xFF8A8698))
                                }
                                TextButton(onClick = { deleteTarget = p.id }) {
                                    Text("删除", color = Color(0xFFB91C1C), fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(28.dp)) }
                }
            }
        }
    }

    if (deleteTarget != null) {
        val pid = deleteTarget!!
        val name = projects.firstOrNull { it.id == pid }?.title ?: ""
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除会话") },
            text = { Text("确定删除《$name》？\n该会话的章节、设定卡、聊天记录、项目文件夹将一并删除，不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    // v6.9.40：删除前停掉该书全部关联任务（自动写作/聊天生成/页面长任务），防白烧 token
                    com.lele.novelmaster.data.AutoWriteManager.stopProjectTasks(pid)
                    scope.launch(Dispatchers.IO) { Tools.deleteProject(pid) }
                    // v6.9.50：删除的正是当前会话时复位记忆——否则恢复进来是幽灵 pid（「项目不存在」根源）
                    if (com.lele.novelmaster.ui.ChatSessionMemory.lastPid == pid) {
                        com.lele.novelmaster.ui.ChatSessionMemory.lastPid = 0L
                    }
                    deleteTarget = null
                }) { Text("删除", color = Color(0xFFB91C1C)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        "  $text",
        color = Color(0xFF8A8698), fontSize = 12.sp,
        modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp)
    )
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
