package com.lele.novelmaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lele.novelmaster.data.AutoWriteManager
import com.lele.novelmaster.data.Message
import com.lele.novelmaster.data.Repo
import com.lele.novelmaster.engine.ChatService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
 * 主界面 v5 —— 豆包式。
 * 顶栏实色渐变（☰ 会话 / 书名 / AI模型 / ＋ / ⠇功能面板），全部可见。
 * 功能面板 = 卡片网格弹出层，展示不完的功能全在这里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentPid by remember { mutableStateOf(0L) }
    var drawerOpen by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var showPanel by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var chatJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

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
        chatJob = scope.launch {
            ChatService.handle(ctx, currentPid, t) { newPid -> if (newPid != 0L) currentPid = newPid }
            busy = false
        }
    }

    fun onSendClick() {
        if (busy) {
            chatJob?.cancel()
            if (AutoWriteManager.state.value.running) AutoWriteManager.stop()
            busy = false
            return
        }
        send(input)
    }

    val currentTitle = projects.firstOrNull { it.id == currentPid }?.title

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BrandTop, BrandBottom)))
    ) {
        // ============ 顶栏（实色渐变，全部按钮可见） ============
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { drawerOpen = true }) {
                Icon(Icons.Filled.Menu, "会话列表", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    currentTitle ?: "新会话",
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (currentPid == 0L) "发一个灵感，自动开一本新书" else "会话独立 · 资料在项目文件夹",
                    color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp, maxLines = 1
                )
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.22f),
                modifier = Modifier.clickable { nav.navigate("ai") }
            ) {
                Text(
                    "🤖 对接AI",
                    color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = { showCreate = true }) {
                Icon(Icons.Filled.Add, "新会话", tint = Color.White)
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color.White.copy(alpha = 0.22f),
                modifier = Modifier.clickable { showPanel = true }
            ) {
                Text(
                    "⠿ 功能",
                    color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        // ============ 主体 ============
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                // 不在会话窗口时（抽屉/面板/对话框打开）隐藏输入框
                if (!drawerOpen && !showPanel && !showCreate) {
                    InputBar(input, busy, { input = it }, { onSendClick() })
                }
            }
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
                                Text("乐乐正在处理…（再点发送键可停止）", color = TextSub, fontSize = 13.sp)
                            }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
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
    // 功能卡片弹出面板
    if (showPanel) {
        ModalBottomSheet(onDismissRequest = { showPanel = false }) {
            FeaturePanel(
                onRun = { cmd -> showPanel = false; send(cmd) },
                onNav = { r -> showPanel = false; nav.navigate(r.replace("{pid}", currentPid.toString())) }
            )
        }
    }
}

/* ---------------- 功能卡片面板（所有功能一目了然） ---------------- */

@Composable
private fun FeaturePanel(onRun: (String) -> Unit, onNav: (String) -> Unit) {
    val funcs = listOf(
        Triple("✍️", "写下一章", FRun("写下一章")),
        Triple("🚀", "自动写作", FNav("autowrite/{pid}")),
        Triple("🧭", "补全大纲", FRun("补全大纲")),
        Triple("🔍", "注入预览", FNav("preview/{pid}")),
        Triple("🗂", "设定卡", FNav("cards/{pid}")),
        Triple("📖", "章节列表", FNav("chapters/{pid}")),
        Triple("📁", "项目文件", FNav("files/{pid}")),
        Triple("📊", "项目仪表盘", FNav("project/{pid}")),
        Triple("💎", "全书体检", FRun("全书体检")),
        Triple("💡", "剧情推演", FRun("推演后续剧情")),
        Triple("✨", "润色最新章", FRun("润色最新章")),
        Triple("💬", "生成金句", FRun("生成金句")),
        Triple("📝", "简介书名", FRun("生成简介和书名")),
        Triple("🏷", "起名器", FRun("起8个人物名")),
        Triple("📚", "小说书架", FNav("shelf")),
        Triple("📤", "导出发布", FNav("export/{pid}")),
        Triple("🤖", "AI 模型", FNav("ai"))
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(bottom = 24.dp)
    ) {
        Text("全部功能", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = TextMain)
        Spacer(Modifier.height(12.dp))
        for (row in funcs.chunked(4)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (f in row) {
                    val (icon, name, action) = f
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F0FB)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                when (action) {
                                    is FRun -> onRun(action.cmd)
                                    is FNav -> onNav(action.route)
                                }
                            }
                    ) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(icon, fontSize = 22.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(name, fontSize = 11.sp, color = TextMain, maxLines = 1)
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private sealed class FAction
private data class FRun(val cmd: String) : FAction()
private data class FNav(val route: String) : FAction()

/* ---------------- 新建会话对话框 ---------------- */

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
                    scope.launch {
                        val r = com.lele.novelmaster.tools.Tools.createProject(
                            title = title.trim(), genre = genre.trim(), desc = "",
                            totalCh = (total.toIntOrNull() ?: 300).coerceIn(1, 600), chWords = 2500
                        )
                        onCreated(r.newProjectId ?: 0L)
                    }
                }
            ) { Text(if (creating) "创建中…" else "创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/* ---------------- 输入栏（发送=停止；imePadding 防遮挡） ---------------- */

@Composable
private fun InputBar(input: String, busy: Boolean, onChange: (String) -> Unit, onSend: () -> Unit) {
    Surface(color = Color.White) {
        Column(
            modifier = Modifier
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
                    placeholder = { Text("丢一个灵感，剩下的交给乐乐…", color = TextSub, fontSize = 14.sp) },
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
                        .clickable { onSend() },
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
    // 全屏显示，两侧只留小间距
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val bubbleMax = screenW - 28.dp
    when (m.role) {
        "user" -> UserBubble(m, bubbleMax)
        "tool" -> ToolBubble(m, bubbleMax)
        "system" -> SystemBubble(m, bubbleMax)
        else -> AiBubble(m, bubbleMax)
    }
}

@Composable
private fun UserBubble(m: Message, bubbleMax: androidx.compose.ui.unit.Dp) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = bubbleMax)) {
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
private fun AiBubble(m: Message, bubbleMax: androidx.compose.ui.unit.Dp) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(Brush.verticalGradient(listOf(BrandTop, BrandBottom)), CircleShape),
            contentAlignment = Alignment.Center
        ) { Text("乐", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.Start, modifier = Modifier.widthIn(max = bubbleMax)) {
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
private fun ToolBubble(m: Message, bubbleMax: androidx.compose.ui.unit.Dp) {
    val lines = m.content.split("\n")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = BubbleTool),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = bubbleMax)
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
private fun SystemBubble(m: Message, bubbleMax: androidx.compose.ui.unit.Dp) {
    val isErr = m.kind == "error"
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = if (isErr) BubbleErr else Color(0xFFFFF6D6)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = bubbleMax)
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
                Text("点发送键可停止", fontSize = 11.sp, color = Color(0xFF8A6E2F))
                Spacer(Modifier.width(8.dp))
                Text("停止", color = Color(0xFFB91C1C), fontSize = 13.sp,
                    modifier = Modifier.clickable { onStop() }.padding(4.dp))
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
        }
    }
}

/* ---------------- 快捷指令 ---------------- */

@Composable
private fun QuickChipsRow(onPick: (String) -> Unit) {
    val items = listOf(
        "✍️ 写下一章",
        "🔍 注入预览",
        "🚀 自动写作 1 到 300",
        "💎 全书体检"
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(items) { label ->
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, BrandTop.copy(alpha = 0.5f)),
                modifier = Modifier.clickable { onPick(label.substringAfter(" ").trim()) }
            ) {
                Text(
                    label, fontSize = 12.sp, color = BrandTop, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }
        }
    }
}

private fun timeFmt(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
