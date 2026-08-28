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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lele.novelmaster.data.AutoWriteManager
import com.lele.novelmaster.data.Message
import com.lele.novelmaster.data.Repo
import com.lele.novelmaster.engine.ChatService
import com.lele.novelmaster.tools.Tools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ---------- 配色 ----------
private val BrandTop = Color(0xFF6750A4)
private val BrandBottom = Color(0xFF8B5CF6)
private val PageBgTop = Color(0xFFF7F5FC)
private val PageBgBottom = Color(0xFFEFEBF9)
private val BubbleUser = Color(0xFF6750A4)
private val BubbleAi = Color(0xFFFFFFFF)
private val BubbleTool = Color(0xFFE7F6EC)
private val BubbleErr = Color(0xFFFDE8E8)
private val TextMain = Color(0xFF1F1B2E)
private val TextSub = Color(0xFF8A8698)

/**
 * 主界面 v3 —— 豆包式：会话=小说，数据完全隔离。
 * 顶栏：☰ 会话历史 | 当前书名 | ＋ 新会话
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentPid by remember { mutableStateOf(0L) }
    var drawerOpen by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val projects by Repo.dao.projectsFlow().collectAsState(initial = emptyList())
    val messages by Repo.dao.messagesFlow(currentPid).collectAsState(initial = emptyList())
    val aw by AutoWriteManager.state.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(projects) {
        if (currentPid == 0L && projects.isNotEmpty()) currentPid = projects.first().id
        if (currentPid == 0L && projects.isEmpty()) {
            val exist = Repo.dao.messagesFlow(0).first()
            if (exist.isEmpty()) {
                Repo.dao.insertMessage(
                    Message(projectId = 0, role = "assistant", content = ChatService.Welcome, kind = "text")
                )
            }
        }
    }

    LaunchedEffect(messages.size, busy) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        input = ""
        busy = true
        scope.launch {
            ChatService.handle(ctx, currentPid, t) { newPid -> if (newPid != 0L) currentPid = newPid }
            busy = false
        }
    }

    val currentTitle = projects.firstOrNull { it.id == currentPid }?.title

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { drawerOpen = true }) {
                        Icon(Icons.Filled.Menu, "会话历史", tint = Color.White)
                    }
                },
                title = {
                    Column {
                        Text(currentTitle ?: "新会话", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                        Text(
                            if (currentPid == 0L) "聊一个灵感，开一本新书" else "会话隔离 · 设定/章节/聊天独立",
                            color = Color.White.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 1
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { nav.navigate("ai") }) {
                        Text("AI", color = Color.White, fontSize = 13.sp)
                    }
                    IconButton(onClick = { showCreate = true }) {
                        Icon(Icons.Filled.Add, "新会话", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        bottomBar = { InputBar(input, busy, { input = it }, { send(it) }) }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .background(Brush.verticalGradient(listOf(PageBgTop, PageBgBottom)))
        ) {
            if (aw.running && aw.projectId == currentPid) AutoWriteCard(aw) { AutoWriteManager.stop() }

            QuickChipsRow { send(it) }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
            ) {
                items(messages, key = { it.id }) { m -> MessageBubble(m) }
                if (busy) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 44.dp)) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = BrandTop)
                            Spacer(Modifier.width(8.dp))
                            Text("乐乐正在处理…", color = TextSub, fontSize = 13.sp)
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }

        if (drawerOpen) {
            MenuDrawer(
                currentPid = currentPid,
                onClose = { drawerOpen = false },
                onSelect = { pid -> drawerOpen = false; currentPid = pid },
                onNav = { r -> drawerOpen = false; nav.navigate(r) },
                onNewSession = { drawerOpen = false; showCreate = true }
            )
        }
        if (showCreate) {
            CreateSessionDialog(
                onDismiss = { showCreate = false },
                onCreated = { pid -> showCreate = false; currentPid = pid }
            )
        }
    }
}

/* ---------------- 新建会话对话框（= 新建小说） ---------------- */

@Composable
private fun CreateSessionDialog(onDismiss: () -> Unit, onCreated: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("玄幻") }
    var total by remember { mutableStateOf("300") }
    var creating by remember { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建会话（新小说）", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("也可以稍后在聊天里直接描述灵感，AI 会自动完善设定。", fontSize = 12.sp, color = TextSub)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("书名") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = genre, onValueChange = { genre = it }, modifier = Modifier.fillMaxWidth(), label = { Text("类型") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = total, onValueChange = { total = it.filter { c -> c.isDigit() }.take(3) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("目标章数（1~600）") }, singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !creating && title.isNotBlank(),
                onClick = {
                    creating = true
                    scope.launch(Dispatchers.IO) {
                        val r = Tools.createProject(
                            title = title.trim(), genre = genre.trim(), desc = "",
                            totalCh = (total.toIntOrNull() ?: 300).coerceIn(1, 600), chWords = 2500
                        )
                        val np = r.newProjectId ?: 0L
                        kotlinx.coroutines.withContext(Dispatchers.Main) { onCreated(np) }
                    }
                }
            ) { Text(if (creating) "创建中…" else "创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/* ---------------- 输入栏（防键盘遮挡：imePadding） ---------------- */

@Composable
private fun InputBar(input: String, busy: Boolean, onChange: (String) -> Unit, onSend: (String) -> Unit) {
    Surface(color = Color.Transparent) {
        Column(
            modifier = Modifier
                .background(Color.White)
                .navigationBarsPadding()
                .imePadding()
        ) {
            HorizontalDivider(color = Color(0x14000000))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp, max = 150.dp),
                    placeholder = { Text("想写什么、要做什么，直接说…", color = TextSub, fontSize = 14.sp) },
                    shape = RoundedCornerShape(23.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrandTop,
                        unfocusedBorderColor = Color(0x1F000000),
                        focusedContainerColor = Color(0xFFF8F6FD),
                        unfocusedContainerColor = Color(0xFFF8F6FD)
                    ),
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(Brush.verticalGradient(listOf(BrandTop, BrandBottom)), CircleShape)
                        .clickable(enabled = !busy) { onSend(input) },
                    contentAlignment = Alignment.Center
                ) {
                    if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    else Icon(Icons.AutoMirrored.Filled.Send, "发送", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/* ---------------- 消息气泡 ---------------- */

@Composable
private fun MessageBubble(m: Message) {
    when (m.role) {
        "user" -> UserBubble(m)
        "tool" -> ToolBubble(m)
        "system" -> SystemBubble(m)
        else -> AiBubble(m)
    }
}

@Composable
private fun UserBubble(m: Message) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 300.dp)) {
            Box(
                modifier = Modifier
                    .background(BubbleUser, RoundedCornerShape(18.dp).copy(bottomEnd = RoundedCornerShape(4.dp).bottomEnd))
            ) {
                Text(m.content, color = Color.White, fontSize = 15.sp, lineHeight = 22.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
            Text(timeFmt(m.createdAt), color = TextSub, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp, end = 4.dp))
        }
    }
}

@Composable
private fun AiBubble(m: Message) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(Brush.verticalGradient(listOf(BrandTop, BrandBottom)), CircleShape),
            contentAlignment = Alignment.Center
        ) { Text("乐", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.widthIn(max = 300.dp)) {
            Box(
                modifier = Modifier
                    .background(BubbleAi, RoundedCornerShape(18.dp).copy(bottomStart = RoundedCornerShape(4.dp).bottomStart))
            ) {
                Text(m.content, color = TextMain, fontSize = 15.sp, lineHeight = 22.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
            Text(timeFmt(m.createdAt), color = TextSub, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp, start = 4.dp))
        }
    }
}

@Composable
private fun ToolBubble(m: Message) {
    val lines = m.content.split("\n")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = BubbleTool),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(lines.firstOrNull() ?: "", color = Color(0xFF1B7A3D), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                if (lines.size > 1) {
                    Spacer(Modifier.height(4.dp))
                    Text(lines.drop(1).joinToString("\n"), color = Color(0xFF2E5B3C), fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}

@Composable
private fun SystemBubble(m: Message) {
    val isErr = m.kind == "error"
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = if (isErr) BubbleErr else Color(0xFFFFF6D6)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(m.content, color = if (isErr) Color(0xFFB91C1C) else Color(0xFF8A6E2F),
                fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        }
    }
}

/* ---------------- 自动写作实时卡片 ---------------- */

@Composable
private fun AutoWriteCard(aw: com.lele.novelmaster.data.AutoWriteManager.Progress, onStop: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF6D6)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color(0xFF8A6E2F), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("自动写作中", fontWeight = FontWeight.Bold, color = Color(0xFF8A6E2F), fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onStop) { Text("停止", color = Color(0xFFB91C1C), fontSize = 13.sp) }
            }
            Text("当前：${aw.currentChapter}", fontSize = 13.sp, color = TextMain)
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .background(Color(0x33AA8A4F), RoundedCornerShape(3.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (aw.total == 0) 0f else (aw.done.toFloat() / aw.total).coerceIn(0f, 1f))
                        .height(6.dp)
                        .background(Color(0xFF8A6E2F), RoundedCornerShape(3.dp))
                )
            }
            Spacer(Modifier.height(4.dp))
            Text("${aw.done}/${aw.total} 章", fontSize = 12.sp, color = TextSub)
            if (aw.logs.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                aw.logs.take(2).forEach { Text("• $it", fontSize = 11.sp, color = TextSub, maxLines = 1) }
            }
        }
    }
}

/* ---------------- 快捷指令 ---------------- */

@Composable
private fun QuickChipsRow(onPick: (String) -> Unit) {
    val items = listOf(
        "✍️ 写下一章",
        "🗂 列出设定卡",
        "🔍 注入预览",
        "🧭 补全大纲",
        "🚀 自动写作 1 到 300",
        "⏹ 停止写作"
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(items) { label ->
            AssistChip(
                onClick = { onPick(label.substringAfter(" ").trim()) },
                label = { Text(label, fontSize = 12.sp) },
                colors = AssistChipDefaults.assistChipColors(containerColor = Color.White, labelColor = BrandTop),
                border = null
            )
        }
    }
}

private fun timeFmt(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
