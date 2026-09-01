package com.lele.novelmaster.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.lele.novelmaster.data.AutoWriteManager
import com.lele.novelmaster.data.Message
import com.lele.novelmaster.data.Repo
import com.lele.novelmaster.engine.ChatService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/* ---------------- 聊天外观设置（持久化） ---------------- */

data class ChatStyle(
    val theme: Int = 0,   // 0淡紫 1纯白 2米黄 3夜间
    val font: Int = 0,    // 0苹方风 1宋体衬线 2等宽 3手写
    val size: Int = 15    // 正文字号
)

object ChatStylePrefs {
    private const val FILE = "chat_style"
    fun load(ctx: android.content.Context): ChatStyle {
        val sp = ctx.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
        return ChatStyle(sp.getInt("theme", 0), sp.getInt("font", 0), sp.getInt("size", 15))
    }
    fun save(ctx: android.content.Context, s: ChatStyle) {
        ctx.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme", s.theme).putInt("font", s.font).putInt("size", s.size).apply()
    }
}

data class ChatThemeColors(
    val pageBgTop: Color, val pageBgBottom: Color,
    val userBubble: Color, val userText: Color,
    val aiBubble: Color, val aiText: Color
)
val ChatThemes = listOf(
    ChatThemeColors(Color(0xFFF7F5FC), Color(0xFFEFEBF9), Color(0xFF6750A4), Color.White, Color.White, Color(0xFF1F1B2E)),
    ChatThemeColors(Color(0xFFFAFAFA), Color(0xFFF0F0F0), Color(0xFF1976D2), Color.White, Color.White, Color(0xFF1A1A1A)),
    ChatThemeColors(Color(0xFFF8F1E3), Color(0xFFF1E6CE), Color(0xFF8D6E3A), Color.White, Color.White, Color(0xFF3E3222)),
    ChatThemeColors(Color(0xFF14141C), Color(0xFF0E0E14), Color(0xFF5B4B9E), Color.White, Color(0xFF1F1F2A), Color(0xFFD5D5E0))
)
val ChatFontNames = listOf("苹方风格(默认)", "宋体·衬线", "等宽", "手写体")
fun chatFontFamily(i: Int) = when (i.coerceIn(0, 3)) {
    1 -> FontFamily.Serif
    2 -> FontFamily.Monospace
    3 -> FontFamily.Cursive
    else -> FontFamily.Default
}

private val BrandTop = Color(0xFF6750A4)
private val BrandBottom = Color(0xFF8B5CF6)
private val TextSub = Color(0xFF8A8698)

/** v5.6：跨页面记住当前会话，从文件管理等页面返回时不再自动切到最新会话 */
object ChatSessionMemory {
    var lastPid: Long = 0L
}

/**
 * 主界面 v5.3 —— 顶栏实色、全部功能收进「功能」面板、无快捷条、AI 无头像、文字居中、
 * 可选聊天主题/字体/字号，AI 回复不限字数。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentPid by remember { mutableStateOf(ChatSessionMemory.lastPid) }
    var drawerOpen by remember { mutableStateOf(false) }
    var showPanel by remember { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }
    // v6.9.56：本书AI设置弹窗 + 全书自检修启动确认弹窗（含「同时去AI味并润色」开关）
    var showBookAiDlg by remember { mutableStateOf(false) }
    var showSelfCheckDlg by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var style by remember { mutableStateOf(ChatStylePrefs.load(ctx)) }
    // v5.5：打字机效果当前显示到的位置（key=message id）
    var creatingSession by remember { mutableStateOf(false) }
    // v6.9.35：忙闲/流式文本/计时全部来自 ChatEngine 单例——生成流不再挂在界面组合上，
    // 点功能按钮、跳转页面、切会话都不会中断生成，回来还能看到实时进度。
    // busy 只在「本次生成所属的会话」里显示，其他会话照常可操作。
    val cs = com.lele.novelmaster.engine.ChatEngine.state.collectAsState().value[currentPid]
        ?: com.lele.novelmaster.engine.ChatEngine.Ui(pid = currentPid)
    val busy = cs.busy && cs.pid == currentPid
    val streamingText = if (cs.pid == currentPid) cs.streamingText else null
    val elapsedSec = cs.elapsedSec

    val projects by Repo.dao.projectsFlow().collectAsState(initial = emptyList())
    val messages by Repo.dao.messagesFlow(currentPid).collectAsState(initial = emptyList())
    val aw by AutoWriteManager.state.collectAsState()
    val listState = rememberLazyListState()

    val th = ChatThemes[style.theme.coerceIn(0, 3)]
    val msgFont = chatFontFamily(style.font)
    val msgSize = style.size.coerceIn(12, 30)

    LaunchedEffect(projects) {
        // v6.9.50：幽灵会话守卫——currentPid 指向已删除的项目时立刻回正
        // （此前删除会话后 lastPid 未复位，恢复进来整段对话跑在幽灵 pid 上：标题栏显示兜底的
        //  「新会话」，写章报「项目不存在/不在骨架中」，卡片则变成看不见的孤儿数据）
        if (currentPid != 0L && projects.none { it.id == currentPid }) {
            currentPid = projects.firstOrNull()?.id ?: 0L
        }
        if (currentPid == 0L && ChatSessionMemory.lastPid == 0L && projects.isNotEmpty()) {
            currentPid = projects.first().id
        }
        if (currentPid == 0L && projects.isEmpty()) {
            val exist = Repo.dao.messagesFlow(0).first()
            if (exist.isEmpty()) {
                Repo.dao.insertMessage(
                    Message(projectId = 0, role = "assistant", content = ChatService.Welcome, kind = "text")
                )
            }
        }
    }
    LaunchedEffect(currentPid) { ChatSessionMemory.lastPid = currentPid }

    // v5.8：空内容的消息一律不显示（不会再出现没有文字的空白模块）
    val visibleMsgs = remember(messages) { messages.filter { it.content.isNotBlank() } }

    // v6.0：reverseLayout —— 最新消息固定在发送框正上方，新内容从底部出来把旧内容往上推（信息自下而上流动）；
    //       index 0 = 最底部（最新），lastIndex = 最顶部（最旧）
    LaunchedEffect(currentPid) {
        if (visibleMsgs.isNotEmpty() || streamingText != null) listState.scrollToItem(0)
    }
    LaunchedEffect(visibleMsgs.size) {
        if (listState.firstVisibleItemIndex <= 1) listState.scrollToItem(0)
    }
    // v5.9/v6.0：AI 输出期间无条件实时跟随，界面始终能看到最新生成的字
    LaunchedEffect(streamingText) { listState.scrollToItem(0) }

    // v6.0：手指滑动屏幕时显示悬浮箭头，停手即隐藏
    val showJumpBtns by remember { androidx.compose.runtime.derivedStateOf { listState.isScrollInProgress } }

    // v6.9.35：计时移到 ChatEngine（离开界面也连续）；这里只负责生成结束时滚回底部
    LaunchedEffect(cs.busy) {
        if (!cs.busy) listState.scrollToItem(0)
    }

    fun newDefaultSession() {
        if (creatingSession) return
        creatingSession = true
        scope.launch {
            val r = com.lele.novelmaster.tools.Tools.createProject(
                title = "未命名会话", genre = "", desc = "",
                totalCh = 1, chWords = 1800, force = true
            )
            r.newProjectId?.let { currentPid = it }
            creatingSession = false
        }
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        // v6.9.35：本书 busy 时不再静默忽略，也不打断正在进行的生成——提示用户
        // v6.9.44：只看本书；其他书在生成不影响本会话发送（多本书可同时各聊各的）
        if (com.lele.novelmaster.engine.ChatEngine.busy(currentPid)) {
            android.widget.Toast.makeText(ctx, "正在生成中，请等当前回复完成，或点发送键停止", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        input = ""
        // v6.9.45：欢迎页（pid=0）没有真实会话，此前直接把消息发进 pid=0，
        // 弱模型在无会话上下文里容易跑偏、回复还会落在一个"不存在于会话列表"的虚拟页里——
        // 表现为"默认打开应用不能聊天，要新建个会话才能聊"。现在发送前先自动建好会话再发。
        if (currentPid == 0L) {
            if (creatingSession) return
            creatingSession = true
            scope.launch {
                val r = runCatching {
                    com.lele.novelmaster.tools.Tools.createProject(
                        title = "未命名会话", genre = "", desc = "",
                        totalCh = 1, chWords = 1800, force = true
                    )
                }.getOrNull()
                val np = r?.newProjectId ?: 0L
                creatingSession = false
                if (np > 0L) {
                    currentPid = np
                    com.lele.novelmaster.engine.ChatEngine.send(ctx, np, t) { newPid -> currentPid = newPid }
                } else {
                    // 建会话失败（极端情况）：走原路径兜底
                    com.lele.novelmaster.engine.ChatEngine.send(ctx, 0L, t) { newPid -> currentPid = newPid }
                }
            }
            return
        }
        // 生成在 ChatEngine 单例里跑：任何界面操作/跳转都不会取消它
        com.lele.novelmaster.engine.ChatEngine.send(ctx, currentPid, t) { newPid -> currentPid = newPid }
    }

    fun onSendClick() {
        // v6.3：发送键=主动停止（唯一会中断生成的方式）
        // v6.9.44：只停本书的生成——其他书在生成时本会话照常发送，严禁误停别人的
        if (com.lele.novelmaster.engine.ChatEngine.busy(currentPid)) {
            com.lele.novelmaster.engine.ChatEngine.stop(currentPid)
            // v6.9.34：只停当前这本书的自动写作（其他并行书不受影响）
            if (AutoWriteManager.isRunning(currentPid)) AutoWriteManager.stop(currentPid)
            return
        }
        send(input)
    }

    val currentTitle = projects.firstOrNull { it.id == currentPid }?.title

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        // ============ 顶栏（v5.5：紫色只覆盖内容区，状态栏区域不再染紫） ============
        Box(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.horizontalGradient(listOf(BrandTop, BrandBottom)))
                    .statusBarsPadding()
                    .height(40.dp)
                    .padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { drawerOpen = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Menu, "会话列表", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Text(
                    currentTitle ?: "新会话",
                    modifier = Modifier.weight(1f).padding(horizontal = 2.dp),
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                // v6.9.56：本书AI设置——就在会话名旁边，点开直接选这本书用哪个模型
                //（原来藏在「项目仪表盘」菜单里，用户始终找不到）
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.22f),
                    modifier = Modifier.clickable { showBookAiDlg = true }
                ) {
                    Text("⚙本书", color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                }
                Spacer(Modifier.width(2.dp))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.22f),
                    modifier = Modifier.clickable { nav.navigate("ai") }
                ) {
                    Text("🤖AI", color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                }
                Spacer(Modifier.width(2.dp))
                IconButton(onClick = { newDefaultSession() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Add, "新会话", tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.22f),
                    modifier = Modifier.clickable { showPanel = true }
                ) {
                    Text("⠿功能", color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                }
                Spacer(Modifier.width(4.dp))
            }
        }

        // ============ 主体（v5.9：contentWindowInsets 清零，聊天区顶到功能栏正下方，没有任何空隙） ============
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!drawerOpen && !showPanel) {
                    InputBar(input, busy, { input = it }, { onSendClick() })
                }
            }
        ) { pad ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .background(Brush.verticalGradient(listOf(th.pageBgTop, th.pageBgBottom)))
            ) {
                // v6.9.34：多书并行——只显示当前这本书的写作卡，停止也只停本书
                val awTask = aw.tasks[currentPid]
                if (awTask?.running == true) AutoWriteCard(awTask) { AutoWriteManager.stop(currentPid) }

                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = true,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        // v6.0：与功能栏零间距；reverseLayout 下 bottom padding 是视觉顶部
                        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 2.dp)
                    ) {
                        // index 0 = 最底部（最新）：流式气泡贴着发送框上方逐字生长
                        val st = streamingText
                        if (st != null) {
                            item(key = "streaming") {
                                if (st.isNotBlank()) {
                                    MessageBubble(
                                        Message(projectId = currentPid, role = "assistant", content = st, kind = "text"),
                                        th, msgFont, msgSize
                                    )
                                } else {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = th.userBubble)
                                        Spacer(Modifier.width(8.dp))
                                        // v6.9.54：思考型模型（如 glm-5 系始终思考）正文出来前可能静默数分钟——明确告知是思考/排队不是卡死
                                        Text(if (elapsedSec > 60) "乐乐正在深度思考中…（复杂任务思考可达数分钟，非卡死；已用时 ${elapsedSec}s，点发送键可停止）" else if (elapsedSec > 0) "乐乐正在输入…（已用时 ${elapsedSec}s，点发送键可停止）" else "乐乐正在输入…（再点发送键可停止）", color = TextSub, fontSize = 13.sp)
                                    }
                                }
                            }
                        } else if (busy) {
                            item(key = "thinking") {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp)) {
                                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp), color = th.userBubble)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (elapsedSec > 0) "乐乐正在工作…（已用时 ${elapsedSec}s，点发送键可停止）" else "乐乐正在思考…（再点发送键可停止）", color = TextSub, fontSize = 13.sp)
                                }
                            }
                        }
                        items(visibleMsgs.asReversed(), key = { it.id }) { m ->
                            MessageBubble(m, th, msgFont, msgSize)
                        }
                    }

                    // v6.0：悬浮箭头——手指滑动时出现（↑回顶部 / ↓回底部），停手即隐藏
                    if (showJumpBtns && visibleMsgs.size > 3) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 10.dp, bottom = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            JumpButton("↑", th) { scope.launch { listState.scrollToItem(visibleMsgs.lastIndex) } }
                            Spacer(Modifier.height(8.dp))
                            JumpButton("↓", th) { scope.launch { listState.scrollToItem(0) } }
                        }
                    }
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
            onNewSession = { drawerOpen = false; newDefaultSession() }
        )
    }
    if (showPanel) {
        ModalBottomSheet(onDismissRequest = { showPanel = false }) {
            FeaturePanel(
                onRun = { cmd -> showPanel = false; send(cmd) },
                onNav = { r -> showPanel = false; nav.navigate(r.replace("{pid}", currentPid.toString())) },
                onStyle = { showPanel = false; showStyle = true },
                onSelfCheck = { showPanel = false; showSelfCheckDlg = true },
                onPerm = {
                    showPanel = false
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                        )
                    }
                }
            )
        }
    }
    if (showStyle) {
        ModalBottomSheet(onDismissRequest = { showStyle = false }) {
            ChatStylePanel(style) { style = it; ChatStylePrefs.save(ctx, it) }
        }
    }
    // v6.9.56：本书AI设置弹窗（顶栏「⚙本书」按钮）
    if (showBookAiDlg) {
        BookAiPickDialog(pid = currentPid, onDismiss = { showBookAiDlg = false })
    }
    // v6.9.56：全书自检修启动确认弹窗（含「同时去AI味并润色」开关，点启动才开跑）
    if (showSelfCheckDlg) {
        SelfCheckStartDialog(
            onDismiss = { showSelfCheckDlg = false },
            onStart = { showSelfCheckDlg = false; send("全书自检修复") }
        )
    }
}

/* ---------------- 聊天样式设置面板 ---------------- */

@Composable
private fun ChatStylePanel(style: ChatStyle, onChange: (ChatStyle) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 30.dp)
    ) {
        Text("聊天样式", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF1F1B2E))
        Spacer(Modifier.height(14.dp))
        Text("主题", fontSize = 13.sp, color = TextSub)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("淡紫", "纯白", "米黄", "夜间").forEachIndexed { i, name ->
                StyleChip(name, style.theme == i) { onChange(style.copy(theme = i)) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("字体（安卓无苹方时自动用最接近的默认字体）", fontSize = 13.sp, color = TextSub)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatFontNames.forEachIndexed { i, name ->
                StyleChip(name, style.font == i) { onChange(style.copy(font = i)) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("字号：${style.size.coerceIn(12, 30)}sp", fontSize = 13.sp, color = TextSub)
        Slider(
            value = style.size.coerceIn(12, 30).toFloat(),
            onValueChange = { onChange(style.copy(size = it.toInt())) },
            valueRange = 12f..30f, steps = 8
        )
    }
}

@Composable
private fun StyleChip(name: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (selected) Color(0xFF6750A4) else Color(0xFFF1EDFA),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, if (selected) Color(0xFF6750A4) else Color(0x22000000)
        ),
        modifier = Modifier.clickable { onClick() }
    ) {
        Text(
            name, fontSize = 12.sp,
            color = if (selected) Color.White else Color(0xFF44406A),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

/* ---------------- v6.9.56：本书AI设置弹窗 + 全书自检修启动确认弹窗 ---------------- */

/** v6.9.56：本书AI设置——顶栏「⚙本书」点开即选；与「项目仪表盘」里的绑定是同一份数据（Project.apiConfigId） */
@Composable
private fun BookAiPickDialog(pid: Long, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val apis by Repo.dao.apiConfigsFlow().collectAsState(initial = emptyList())
    val projects by Repo.dao.projectsFlow().collectAsState(initial = emptyList())
    val p = projects.firstOrNull { it.id == pid }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本书使用哪个AI模型") },
        text = {
            Column {
                if (p == null) {
                    Text("还没有真实会话，先新建一个会话再来设置本书模型。")
                } else {
                    Text(
                        "为这本书单独指定 AI 接口：并行写多本书时，各书可分别用不同的 API/平台/模型，互不影响。不指定则跟随「AI模型设置」里已启用的接口。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    val active = apis.firstOrNull { it.isActive }
                    ModelOptionRow(
                        name = "跟随全局启用" + (active?.let { "（${it.name} · ${it.model.ifBlank { "未选模型" }}）" } ?: "（尚未启用任何接口）"),
                        selected = p.apiConfigId == 0L
                    ) {
                        scope.launch(Dispatchers.IO) { Repo.dao.updateProject(p.copy(apiConfigId = 0L)) }
                        onDismiss()
                    }
                    apis.forEach { a ->
                        ModelOptionRow(
                            name = "${a.name} · ${a.model.ifBlank { "未选模型" }}" + (if (a.isActive) "（全局启用中）" else ""),
                            selected = p.apiConfigId == a.id
                        ) {
                            scope.launch(Dispatchers.IO) { Repo.dao.updateProject(p.copy(apiConfigId = a.id)) }
                            onDismiss()
                        }
                    }
                    if (apis.isEmpty()) {
                        Text(
                            "还没有添加任何 AI 配置，点右上角「🤖AI」去添加。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

/** 模型绑定选项行（ChatScreen 内用，与 ProjectScreen 的 ModelOption 同款） */
@Composable
private fun ModelOptionRow(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (selected) "🔵" else "⚪", fontSize = 15.sp)
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * v6.9.56：全书自检修启动确认弹窗——点「全书自检修」不再直接开跑，
 * 先确认「是否同时去AI味并润色」，再点启动才开跑（开关即时落库，与AI模型设置页的开关同一份配置）。
 */
@Composable
private fun SelfCheckStartDialog(onDismiss: () -> Unit, onStart: () -> Unit) {
    val ctx = LocalContext.current
    var polish by remember { mutableStateOf(com.lele.novelmaster.data.InjectPrefs.polishWithCheck(ctx)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🩺 全书自检修") },
        text = {
            Column {
                Text(
                    "逐章检查剧情矛盾并自动修正；每章改前自动备份，说「撤销第N章自检修改」可恢复。",
                    fontSize = 13.sp, color = Color(0xFF1F1B2E)
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = polish,
                        onCheckedChange = {
                            polish = it
                            com.lele.novelmaster.data.InjectPrefs.setPolishWithCheck(ctx, it)
                        }
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "同时去AI味并润色（发布级文风，剧情/对话/人物不变）",
                        fontSize = 13.sp, modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "💡 耗时提示：检查约每章0.2章正文的量，开启润色则每章相当于重写一遍；长书会跑一阵，进度会实时播报在聊天里，期间可正常使用其他功能。",
                    fontSize = 12.sp, color = TextSub
                )
            }
        },
        confirmButton = { TextButton(onClick = onStart) { Text("🚀 启动") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/* ---------------- 功能卡片面板（全部功能唯一入口） ---------------- */

private sealed class FAction
private data class FRun(val cmd: String) : FAction()
private data class FNav(val route: String) : FAction()
private object FSelfCheck : FAction()   // v6.9.56：全书自检修——不直接开跑，先弹启动确认窗

@Composable
private fun FeaturePanel(onRun: (String) -> Unit, onNav: (String) -> Unit, onStyle: () -> Unit, onSelfCheck: () -> Unit, onPerm: () -> Unit) {
    val funcs: List<Triple<String, String, FAction>> = listOf(
        Triple("✍️", "写下一章", FRun("写下一章")),
        Triple("🚀", "自动写作", FNav("autowrite/{pid}")),
        Triple("🧭", "补全大纲", FRun("补全大纲")),
        Triple("🔍", "注入预览", FNav("preview/{pid}")),
        Triple("🗂", "设定卡", FNav("cards/{pid}")),
        Triple("📖", "章节列表", FNav("chapters/{pid}")),
        Triple("📁", "项目文件", FNav("files/{pid}")),
        Triple("📚", "小说书架", FNav("shelf")),
        Triple("📊", "项目仪表盘", FNav("project/{pid}")),
        Triple("💎", "全书体检", FRun("全书体检")),
        Triple("🔬", "自检最新章", FRun("自检最新章")),
        Triple("🩺", "全书自检修", FSelfCheck),
        Triple("✨", "去AI味全书", FRun("全书去AI味润色")),
        Triple("🪝", "伏笔体检", FRun("伏笔体检")),
        Triple("🧵", "支线体检", FRun("支线体检")),
        Triple("🧾", "设定体检", FRun("设定体检")),
        Triple("✂️", "设定瘦身", FRun("设定瘦身")),
        Triple("💡", "剧情推演", FRun("推演后续剧情")),
        Triple("🌈", "润色最新章", FRun("润色最新章")),
        Triple("🚀", "发布打磨", FRun("发布打磨最新章")),
        Triple("💬", "生成金句", FRun("生成金句")),
        Triple("📝", "简介书名", FRun("生成简介和书名")),
        Triple("🏷", "起名器", FRun("起8个人物名")),
        Triple("📤", "导出发布", FNav("export/{pid}")),
        Triple("🎨", "聊天样式", FAction2(onStyle)),
        Triple("🔐", "存储授权", FAction2(onPerm)),
        Triple("🤖", "AI 模型", FNav("ai"))
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(bottom = 24.dp)
    ) {
        Text("全部功能", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color(0xFF1F1B2E))
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
                                    is FSelfCheck -> onSelfCheck()
                                    is FAction2 -> action.go()
                                }
                            }
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(icon, fontSize = 22.sp)
                            Spacer(Modifier.height(4.dp))
                            Text(name, fontSize = 11.sp, color = Color(0xFF1F1B2E), maxLines = 1)
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

private open class FAction2(val go: () -> Unit) : FAction()

/* ---------------- 新建会话对话框 ---------------- */

@Composable
private fun CreateSessionDialog(onDismiss: () -> Unit, onCreated: (Long) -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    // v6.9.57：不再预填默认风格/章数——风格按灵感自动定，章数按作者要求
    var genre by remember { mutableStateOf("") }
    var total by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建会话（新小说）", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("风格和章数都可以留空：风格会按你发的灵感自动定，章数按你在聊天里要求的章数。", fontSize = 12.sp, color = TextSub)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("书名") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = genre, onValueChange = { genre = it }, modifier = Modifier.fillMaxWidth(), label = { Text("类型（可留空）") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = total, onValueChange = { total = it.filter { c -> c.isDigit() }.take(3) },
                    modifier = Modifier.fillMaxWidth(), label = { Text("目标章数（1~600，可留空默认300）") }, singleLine = true
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
                            totalCh = (total.toIntOrNull() ?: 300).coerceIn(1, 600), chWords = 2500,
                            force = true
                        )
                        onCreated(r.newProjectId ?: 0L)
                    }
                }
            ) { Text(if (creating) "创建中…" else "创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/* ---------------- 输入栏 ---------------- */

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
                    placeholder = { Text("随便聊，或丢一个灵感开新书…", color = TextSub, fontSize = 14.sp) },
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
                    else Icon(Icons.AutoMirrored.Filled.Send, "发送/停止", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/* ---------------- v6.0：悬浮跳转按钮 ---------------- */

@Composable
private fun JumpButton(label: String, th: ChatThemeColors, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = th.userBubble,
        shadowElevation = 3.dp,
        modifier = Modifier
            .size(34.dp)
            .clickable { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(label, color = th.userText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/* ---------------- 消息气泡（无头像、全屏宽、AI 居中） ---------------- */

@Composable
private fun MessageBubble(m: Message, th: ChatThemeColors, font: FontFamily, size: Int) {
    val screenW = LocalConfiguration.current.screenWidthDp.dp
    val bubbleMax = screenW - 20.dp
    when (m.role) {
        "user" -> UserBubble(m, bubbleMax, th, font, size)
        // v6.9.46：kind=selfcheck_fix = 单章自检「疑似矛盾」——带「确认修复」按钮，点击按问题清单+大纲重写本章
        "tool" -> when {
            m.kind == "report" -> ReportBubble(m, bubbleMax, th, font, size)
            m.kind == "selfcheck_fix" -> SelfCheckFixBubble(m, bubbleMax, th, font, size)
            else -> ToolBubble(m, bubbleMax, th, font, size)
        }
        "system" -> SystemBubble(m, bubbleMax, font, size)
        else -> AiBubble(m, bubbleMax, th, font, size)
    }
}

/* ---------------- v6.9.41 完整报告：折叠卡片 + 点击开独立窗口看全文 ---------------- */

@Composable
private fun ReportBubble(m: Message, bubbleMax: androidx.compose.ui.unit.Dp, th: ChatThemeColors, font: FontFamily, size: Int) {
    var showFull by remember { mutableStateOf(false) }
    val lines = m.content.split("\n")
    val title = lines.firstOrNull()?.take(60) ?: "任务报告"
    // 预览只取正文前几行，正文默认折叠
    val preview = lines.drop(1).filter { it.isNotBlank() }.take(3).joinToString("\n")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = th.aiBubble),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = bubbleMax)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text("📄 $title", color = th.aiText, fontWeight = FontWeight.Bold,
                    fontSize = (size - 1).sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                if (preview.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(preview + "\n…", color = th.aiText.copy(alpha = 0.7f),
                        fontFamily = font, fontSize = (size - 2).sp, lineHeight = ((size - 2) * 1.5).sp,
                        maxLines = 4, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(6.dp))
                Text("👆 点击查看完整报告（已更新保存）", color = MaterialTheme.colorScheme.primary,
                    fontSize = (size - 2).sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clickable { showFull = true }
                        .fillMaxWidth(), textAlign = TextAlign.Center)
                // v6.9.42：体检没改卡时——一键「确认修复」，按报告推理改卡并自动保存到项目文件夹
                ReportRepairButton(m, th, size)
            }
        }
    }
    if (showFull) ReportDialog(m, th, font, size) { showFull = false }
}

/** v6.9.42：体检报告「确认修复」按钮——只在没有修改任何卡时出现。
 *  点击后读最近一份体检报告，AI 按报告逐张给出修复内容，自动改库+写回项目文件。
 *  v6.9.42b：迁移到 AppTasks 进程级宿主——铁律「AI 长任务不放 Composable scope」，
 *  此前跑在 rememberCoroutineScope 里，点完切走页面修复会被静默取消。 */
@Composable
private fun ReportRepairButton(m: Message, th: ChatThemeColors, size: Int, onDone: () -> Unit = {}) {
    if (!m.content.contains("未修改任何卡")) return
    val appTasks by com.lele.novelmaster.engine.AppTasks.state.collectAsState()
    val key = "cardsRepair:${m.projectId}"
    val running = key in appTasks.running
    val label = when {
        running -> appTasks.progress[key] ?: "⏳ 正在按体检报告修复设定卡，请稍候…（可离开页面，修复照常进行）"
        else -> "🔧 确认根据体检推理修改设定卡（自动修复并保存）"
    }
    Text(
        label,
        color = if (running) th.aiText.copy(alpha = 0.6f) else MaterialTheme.colorScheme.primary,
        fontSize = (size - 1).sp, fontWeight = FontWeight.Bold,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !running) {
                // AppTasks.launch 同名任务已在跑会返回 null（防重复点击）；scope=进程级 IO，切页面不取消
                com.lele.novelmaster.engine.AppTasks.launch(key) {
                    val r = com.lele.novelmaster.tools.Tools.cardsApplyRepair(m.projectId)
                    ChatService.appendToolResult(m.projectId, r)
                }
                onDone()
            }
            .padding(vertical = 6.dp),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun ReportDialog(m: Message, th: ChatThemeColors, font: FontFamily, size: Int, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            color = th.pageBgTop,
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(0.dp)
        ) {
            Column(Modifier.fillMaxSize()) {
                // 顶栏：标题 + 关闭
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("完整报告", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = th.aiText,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Text("关闭", color = MaterialTheme.colorScheme.primary, fontSize = 15.sp,
                        modifier = Modifier.clickable { onDismiss() }.padding(8.dp))
                }
                HorizontalDivider(color = th.aiText.copy(alpha = 0.12f), thickness = 0.5.dp)
                // 全文可滚动查看；点击正文区域可在「折叠/展开全部」间切换（默认展开）
                var expanded by remember { mutableStateOf(true) }
                val text = m.content
                val shown = if (expanded) text else text.take(800) + "\n\n…（已折叠，点击展开全文）"
                Text(
                    shown,
                    color = th.aiText,
                    fontFamily = font,
                    fontSize = (size - 1).sp,
                    lineHeight = ((size - 1) * 1.7).sp,
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                        .clickable { expanded = !expanded }
                )
                HorizontalDivider(color = th.aiText.copy(alpha = 0.12f), thickness = 0.5.dp)
                // v6.9.42：体检没改卡时——完整报告窗口底部同样给「确认修复」按钮，点完自动关窗
                ReportRepairButton(m, th, size + 1) { onDismiss() }
                Text("提示：点击正文可折叠/展开 · 内容已完整保存到项目文件夹",
                    fontSize = 11.sp, color = th.aiText.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun UserBubble(m: Message, bubbleMax: androidx.compose.ui.unit.Dp, th: ChatThemeColors, font: FontFamily, size: Int) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = bubbleMax)) {
            Box(
                modifier = Modifier
                    .background(th.userBubble, RoundedCornerShape(18.dp).copy(bottomEnd = RoundedCornerShape(4.dp).bottomEnd))
            ) {
                // v6.9.50：长按可选中/复制聊天文字
                SelectionContainer {
                    Text(m.content, color = th.userText, fontFamily = font, fontSize = size.sp, lineHeight = (size * 1.7).sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
            Text(timeFmt(m.createdAt), color = TextSub, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp, end = 4.dp))
        }
    }
}

@Composable
private fun AiBubble(m: Message, bubbleMax: androidx.compose.ui.unit.Dp, th: ChatThemeColors, font: FontFamily, size: Int) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = bubbleMax)) {
            Box(
                modifier = Modifier
                    .background(th.aiBubble, RoundedCornerShape(18.dp).copy(bottomStart = RoundedCornerShape(4.dp).bottomStart))
            ) {
                // v6.9.50：长按可选中/复制聊天文字
                SelectionContainer {
                    Text(m.content, color = th.aiText, fontFamily = font, fontSize = size.sp,
                        lineHeight = (size * 1.7).sp, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                }
            }
            Text(timeFmt(m.createdAt), color = TextSub, fontSize = 10.sp, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun ToolBubble(m: Message, bubbleMax: androidx.compose.ui.unit.Dp, th: ChatThemeColors, font: FontFamily, size: Int) {
    // v6.0：工具气泡跟随聊天主题（浅色主题=白底黑字），不再用绿色
    val lines = m.content.split("\n")
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = th.aiBubble),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = bubbleMax)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // v6.9.50：长按可选中/复制
                SelectionContainer {
                    Column {
                        Text(lines.firstOrNull() ?: "", color = th.aiText, fontWeight = FontWeight.Bold,
                            fontSize = (size - 1).sp, textAlign = TextAlign.Center)
                        if (lines.size > 1) {
                            Spacer(Modifier.height(4.dp))
                            Text(lines.drop(1).joinToString("\n"), color = th.aiText.copy(alpha = 0.85f),
                                fontFamily = font, fontSize = (size - 2).sp, lineHeight = ((size - 2) * 1.6).sp,
                                textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

/** v6.9.46：单章自检「疑似矛盾」气泡——内容 + 「确认修复」按钮。
 *  点击后 AppTasks 进程级宿主跑 chapterSelfCheckFix：按问题清单+本章大纲重写正文、备份原文、自动保存并复检。
 *  章号从消息内容解析（消息由 WriterEngine.selfCheckChapter suspect 路径落库）。 */
@Composable
private fun SelfCheckFixBubble(m: Message, bubbleMax: androidx.compose.ui.unit.Dp, th: ChatThemeColors, font: FontFamily, size: Int) {
    val lines = m.content.split("\n")
    val chIdx = Regex("第(\\d+)章自检发现").find(m.content)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val appTasks by com.lele.novelmaster.engine.AppTasks.state.collectAsState()
    val key = "selfcheckFix:${m.projectId}:$chIdx"
    val running = key in appTasks.running
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = th.aiBubble),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = bubbleMax)
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(lines.firstOrNull() ?: "", color = th.aiText, fontWeight = FontWeight.Bold,
                    fontSize = (size - 1).sp, textAlign = TextAlign.Center)
                if (lines.size > 1) {
                    Spacer(Modifier.height(4.dp))
                    Text(lines.drop(1).joinToString("\n"), color = th.aiText.copy(alpha = 0.85f),
                        fontFamily = font, fontSize = (size - 2).sp, lineHeight = ((size - 2) * 1.6).sp,
                        textAlign = TextAlign.Center)
                }
                if (chIdx > 0) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (running) appTasks.progress[key] ?: "⏳ 正在按自检问题重写本章，请稍候…（可离开页面，修复照常进行）"
                        else "🔧 确认修复：按问题清单+大纲重写本章（自动保存）",
                        color = if (running) th.aiText.copy(alpha = 0.6f) else MaterialTheme.colorScheme.primary,
                        fontSize = (size - 1).sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !running) {
                                com.lele.novelmaster.engine.AppTasks.launch(key) {
                                    val r = com.lele.novelmaster.tools.Tools.chapterSelfCheckFix(m.projectId, chIdx)
                                    ChatService.appendToolResult(m.projectId, r)
                                }
                            }
                            .padding(vertical = 4.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun SystemBubble(m: Message, bubbleMax: androidx.compose.ui.unit.Dp, font: FontFamily, size: Int) {
    val isErr = m.kind == "error"
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = if (isErr) Color(0xFFFDE8E8) else Color(0xFFFFF6D6)),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = bubbleMax)
        ) {
            // v6.9.50：长按可选中/复制
            SelectionContainer {
                Text(m.content, color = if (isErr) Color(0xFFB91C1C) else Color(0xFF8A6E2F),
                    fontFamily = font, fontSize = (size - 2).sp, lineHeight = ((size - 2) * 1.6).sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

/* ---------------- 自动写作实时卡片 ---------------- */

@Composable
private fun AutoWriteCard(aw: com.lele.novelmaster.data.AutoWriteManager.TaskState, onStop: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF6D6)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color(0xFF8A6E2F), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("自动写作中", fontWeight = FontWeight.Bold, color = Color(0xFF8A6E2F), fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text("停止", color = Color(0xFFB91C1C), fontSize = 13.sp,
                    modifier = Modifier.clickable { onStop() }.padding(4.dp))
            }
            Text("当前：${aw.currentChapter}", fontSize = 13.sp, color = Color(0xFF1F1B2E))
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

private fun timeFmt(ms: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.CHINA).format(java.util.Date(ms))
