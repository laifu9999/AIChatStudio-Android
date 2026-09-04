package com.lele.mobipaint.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lele.mobipaint.BatchEngine
import com.lele.mobipaint.ChatEngine
import com.lele.mobipaint.Config
import com.lele.mobipaint.Db

@Composable
fun ChatScreen() {
    val pid = Nav.pid
    val ctx = LocalContext.current
    BackHandler { Nav.screen = Screen.Shelf }

    val ui by ChatEngine.state(pid).collectAsState()
    val batch by BatchEngine.state(pid).collectAsState()

    var input by remember { mutableStateOf("") }
    var showBatch by remember { mutableStateOf(false) }
    var readerChapter by remember { mutableStateOf<Db.Chapter?>(null) }

    val listState = rememberLazyListState()
    // 自动滚动到底（新消息或流式内容每增长 300 字跟一次；用户上滑时也简单跟随，保持和聊天 App 一致）
    LaunchedEffect(ui.msgs.size, ui.streaming.length / 300) {
        val count = ui.msgs.size + if (ui.streaming.isNotEmpty()) 1 else 0
        if (count > 0) listState.scrollToItem(count - 1)
    }

    fun doSend(text: String) {
        val cfg = Config.load(ctx)
        ChatEngine.send(pid, text, cfg)
        input = ""
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF6F7FB))) {
        TopBar(
            title = Nav.bookTitle.ifEmpty { "对话创作" },
            onBack = { Nav.screen = Screen.Shelf }
        ) {
            TextButtonSmall("⚙", { Nav.screen = Screen.Settings })
        }

        // 全功能快捷行（与电脑端一一对应，横向滑动查看全部）
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { TextButtonSmall("🗂 设定", { Nav.screen = Screen.SettingMgr }) }
            item { TextButtonSmall("✍ 写作台", { Nav.screen = Screen.Editor }) }
            item { TextButtonSmall("📑 章节", { Nav.screen = Screen.Chapters }) }
            item { TextButtonSmall("📖 阅读器", { Nav.screen = Screen.Reader }) }
            item { TextButtonSmall("🚀 导出", { Nav.screen = Screen.Publish }) }
            item { TextButtonSmall("🧠 记忆", {
                val cfg = Config.load(ctx)
                ChatEngine.refreshMemory(pid, cfg)
            }) }
            item { TextButtonSmall("🤖 连写", { showBatch = true }, WarnOrange) }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            items(ui.msgs) { m ->
                ChatBubble(m.role, m.text)
            }
            if (ui.streaming.isNotEmpty()) {
                item { ChatBubble("assistant", ui.streaming) }
            }
        }

        StatusLine(ui.error, ui.info)
        if (batch.progress.isNotEmpty()) {
            Text(
                batch.progress,
                color = WarnOrange,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val nextNo = remember(ui.msgs.size) { Db.maxChapterNo(pid) + 1 }
            TextButtonSmall("✍️ 写第${nextNo}章", {
                doSend("请直接写第${nextNo}章正文，约2000字（不低于1500字，不要刻意注水）。" +
                    "严格衔接剧情记忆与前情，结尾留钩子。")
            })
            TextButtonSmall("➡️ 继续往下写", {
                doSend("请接着最新剧情往下写一章，约2000字（不低于1500字）。")
            })
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 120.dp),
                placeholder = { Text("和 AI 聊灵感、下写作指令…", fontSize = 13.sp) },
                shape = RoundedCornerShape(12.dp)
            )
            val busy = ui.busy
            Button(
                onClick = {
                    if (busy) {
                        ChatEngine.stop(pid)
                        ChatEngine.reload(pid)
                    } else {
                        doSend(input)
                    }
                },
                modifier = Modifier.padding(start = 6.dp)
            ) {
                Text(if (busy) "停止" else "发送")
            }
        }
    }

    if (showBatch) {
        BatchDialog(
            defaultFrom = Db.maxChapterNo(pid) + 1,
            onDismiss = { showBatch = false },
            onStart = { from, to ->
                showBatch = false
                val cfg = Config.load(ctx)
                BatchEngine.start(pid, from, to, cfg)
            },
            running = batch.running,
            onStop = { BatchEngine.stop(pid) })
    }

    readerChapter?.let { ch ->
        AlertDialog(
            onDismissRequest = { readerChapter = null },
            title = { Text("第${ch.no}章 ${ch.title}") },
            text = {
                Text(
                    ch.content,
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    color = InkDark,
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { readerChapter = null }) { Text("关闭") }
            })
    }
}

@Composable
fun BatchDialog(
    defaultFrom: Int,
    onDismiss: () -> Unit,
    onStart: (Int, Int) -> Unit,
    running: Boolean,
    onStop: () -> Unit
) {
    var fromText by remember { mutableStateOf(defaultFrom.toString()) }
    var toText by remember { mutableStateOf((defaultFrom + 9).toString()) }
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("🤖 自动连写") },
        text = {
            Column {
                Text(
                    "从第 X 章连写到第 Y 章（999 章都行）：每章带全量上下文，" +
                        "每 20 章自动刷新剧情记忆；已生成章节实时入库，" +
                        "切走页面/锁屏都不打断，回来查看进度。",
                    color = Color(0xFF6B7186), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = fromText, onValueChange = { fromText = it },
                        label = { Text("从第几章") },
                        modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = toText, onValueChange = { toText = it },
                        label = { Text("写到第几章") },
                        modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            if (running) {
                Button(onClick = onStop) { Text("停止连写") }
            } else {
                Button(onClick = {
                    val from = fromText.toIntOrNull()
                    val to = toText.toIntOrNull()
                    if (from != null && to != null && to >= from) onStart(from, to)
                }) { Text("开始连写") }
            }
        },
        dismissButton = {
            if (!running) TextButton(onClick = onDismiss) { Text("取消") }
        })
}
