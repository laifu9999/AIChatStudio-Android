package com.lele.novelmaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lele.novelmaster.data.AutoWriteManager
import com.lele.novelmaster.data.Message
import com.lele.novelmaster.data.Project
import com.lele.novelmaster.data.Repo
import com.lele.novelmaster.engine.ChatService
import kotlinx.coroutines.launch

/**
 * 主界面 —— 像豆包 / 元宝一样以聊天为主。
 *
 * 消息分类：
 *  - user: 用户发
 *  - assistant: AI 回复
 *  - tool: 本地工具执行结果（绿色气泡，可点击跳转 / 折叠详情）
 *  - system: 系统提示（顶栏提示、欢迎语、错误）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentPid by remember { mutableStateOf(0L) }
    var menuOpen by remember { mutableStateOf(false) }
    var drawerOpen by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val projects by Repo.dao.projectsFlow().collectAsState(initial = emptyList())
    val messages by Repo.dao.messagesFlow(if (currentPid == 0L) -1 else currentPid).collectAsState(initial = emptyList())
    val aw by AutoWriteManager.state.collectAsState()
    val listState = rememberLazyListState()

    // 选中第一本作为默认项目；同时未启用自动初始化欢迎语
    LaunchedEffect(projects) {
        if (currentPid == 0L && projects.isNotEmpty()) currentPid = projects.first().id
        if (currentPid == 0L && projects.isEmpty()) {
            val exist = Repo.dao.messagesFlow(-1).first()
            if (exist.isEmpty()) Repo.dao.insertMessage(Message(projectId = 0, role = "assistant", content = ChatService.Welcome, kind = "text"))
        }
    }

    // 自动滚动
    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send(userInput: String) {
        val text = userInput.trim()
        if (text.isEmpty() || busy) return
        input = ""
        busy = true
        scope.launch {
            ChatService.handle(ctx, currentPid, text) { newPid ->
                if (newPid != 0L) currentPid = newPid
            }
            busy = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("乐乐", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        ProjectMenu(currentPid, projects) { picked ->
                            currentPid = picked
                            menuOpen = false
                        }
                    }
                },
                actions = {
                    TextButton(onClick = { nav.navigate("ai") }) { Text("AI 模型", color = Color.White) }
                    IconButton(onClick = { drawerOpen = true }) {
                        Icon(Icons.Filled.Menu, contentDescription = "菜单", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF6750A4),
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            InputBar(input = input, busy = busy, onChange = { input = it }, onSend = { send(it) })
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .background(Color(0xFFF6F4FB))
        ) {
            // 自动写作实时卡片
            if (aw.running) AutoWriteCard(aw) { AutoWriteManager.stop() }

            // 快捷指令栏
            QuickChipsRow(onPick = { send(it) })

            // 消息列表
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(messages, key = { it.id }) { m ->
                    MessageBubble(m)
                }
                if (busy) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("正在思考…", color = Color(0xFF888888), fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        if (drawerOpen) MenuDrawer(
            onClose = { drawerOpen = false },
            onNav = { route -> drawerOpen = false; nav.navigate(route) }
        )
    }
}

/* ------------------ 顶栏项目切换菜单 ------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectMenu(currentPid: Long, projects: List<Project>, onPick: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val title = projects.firstOrNull { it.id == currentPid }?.title ?: "未选择"
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0x33FFFFFF),
        modifier = Modifier.clickable { open = true }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(title, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.width(4.dp))
            Text("▾", color = Color.White, fontSize = 12.sp)
        }
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        if (projects.isEmpty()) DropdownMenuItem(text = { Text("暂无项目，先在菜单创建一本") }, onClick = { open = false })
        projects.forEach { p ->
            DropdownMenuItem(
                text = { Text("《${p.title}》  ·  目标${p.targetChapters}章") },
                onClick = { open = false; onPick(p.id) }
            )
        }
    }
}

/* ------------------ 输入栏 ------------------ */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputBar(input: String, busy: Boolean, onChange: (String) -> Unit, onSend: (String) -> Unit) {
    Surface(color = Color.White, tonalElevation = 4.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = 160.dp),
                placeholder = { Text("告诉 AI 你想写什么 / 要做什么…") },
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                enabled = !busy,
                onClick = { onSend(input) }
            ) {
                if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Icon(Icons.Filled.Send, contentDescription = "发送", tint = Color(0xFF6750A4))
            }
        }
    }
}

/* ------------------ 单条消息气泡 ------------------ */

@Composable
private fun MessageBubble(m: Message) {
    val bg = when (m.role) {
        "user" -> Color(0xFFEEE4FF)
        "assistant" -> Color.White
        "tool" -> Color(0xFFE6F6EC)
        "system" -> Color(0xFFFFF6D6)
        else -> Color.White
    }
    val align = if (m.role == "user") Alignment.End else Alignment.Start

    val maxW = 320.dp
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.role == "user") Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bg),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = maxW)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(m.content, color = Color(0xFF222222), fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = m.role + " · " + timeFmt(m.createdAt),
                    color = Color(0xFF999999),
                    fontSize = 10.sp
                )
            }
        }
    }
}

/* ------------------ 自动写作实时卡片 ------------------ */

@Composable
private fun AutoWriteCard(aw: com.lele.novelmaster.data.AutoWriteManager.Progress, onStop: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF6D6)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color(0xFF8A6E2F))
                Spacer(Modifier.width(6.dp))
                Text("自动写作中", fontWeight = FontWeight.Bold, color = Color(0xFF8A6E2F))
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onStop) { Text("停止", color = Color(0xFFB91C1C)) }
            }
            Spacer(Modifier.height(4.dp))
            Text("当前：${aw.currentChapter}", fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            LinearProgress(progress = if (aw.total == 0) 0f else aw.done.toFloat() / aw.total)
            Spacer(Modifier.height(4.dp))
            Text("${aw.done}/${aw.total} 章", fontSize = 12.sp, color = Color(0xFF555555))
            if (aw.logs.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Divider(color = Color(0x33AA8A4F))
                Spacer(Modifier.height(4.dp))
                aw.logs.take(3).forEach {
                    Text("• $it", fontSize = 11.sp, color = Color(0xFF666666))
                }
            }
        }
    }
}

@Composable
private fun LinearProgress(progress: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(Color(0x33AA8A4F), RoundedCornerShape(3.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(6.dp)
                .background(Color(0xFF8A6E2F), RoundedCornerShape(3.dp))
        )
    }
}

/* ------------------ 快捷指令 ------------------ */

@Composable
private fun QuickChipsRow(onPick: (String) -> Unit) {
    val items = listOf(
        "写下下一章" to "写下一章",
        "列出所有设定卡" to "列出设定卡",
        "一键补全大纲" to "补全大纲",
        "开始自动写作" to "开始自动写作 1 到 30",
        "停止写作" to "停止写作"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items.forEach { (label, sendText) ->
            AssistChip(
                onClick = { onPick(sendText) },
                label = { Text(label, fontSize = 12.sp) }
            )
        }
    }
}

private fun timeFmt(ms: Long): String {
    val d = java.util.Date(ms)
    val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA)
    return fmt.format(d)
}
