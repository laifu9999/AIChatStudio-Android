package com.lele.mobipaint.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lele.mobipaint.AiClient
import com.lele.mobipaint.AppScope
import com.lele.mobipaint.Config
import com.lele.mobipaint.Db
import com.lele.mobipaint.Prompts
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 章节管理页（对应 PC 端「📑 章节管理」）：
 * 章节列表 + AI 大纲规划 + 删除章节；点章节可阅读 / 进写作台修改。
 */
@Composable
fun ChaptersScreen() {
    val pid = Nav.pid
    val ctx = LocalContext.current
    BackHandler { Nav.screen = Screen.Chat }

    var tick by remember { mutableStateOf(0) }
    val chapters = remember(tick) { Db.listChapters(pid) }
    var reader by remember { mutableStateOf<Db.Chapter?>(null) }
    var deleteCh by remember { mutableStateOf<Db.Chapter?>(null) }
    var showOutline by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar("📑 ${Nav.bookTitle.ifEmpty { "章节" }}（${chapters.size}章）",
            onBack = { Nav.screen = Screen.Chat }) {
            TextButtonSmall("🧠 AI大纲", { showOutline = true })
        }
        if (chapters.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(32.dp)) {
                Text("还没有章节。回对话页点「✍️ 写第1章」或用「🤖 连写」；也可以点上方「🧠 AI大纲」先规划再写。",
                    color = Color(0xFF6B7186), fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                items(chapters, key = { it.id }) { ch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { reader = ch }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("第${ch.no}章 ${ch.title.ifEmpty { "未命名" }}",
                                color = InkDark, fontSize = 15.sp, maxLines = 1)
                            Text("${ch.status} · ${ch.words}字",
                                color = Color(0xFF8A8FA0), fontSize = 11.sp)
                        }
                        Text("✏", fontSize = 15.sp,
                            modifier = Modifier
                                .clickable {
                                    Nav.chapterNo = ch.no
                                    Nav.screen = Screen.Editor
                                }
                                .padding(6.dp))
                        Text("🗑", color = Color(0xFFB0B4C0), fontSize = 14.sp,
                            modifier = Modifier
                                .clickable { deleteCh = ch }
                                .padding(6.dp))
                    }
                }
            }
        }
    }

    // ---------- 阅读 / 修改 ----------
    reader?.let { ch ->
        AlertDialog(
            onDismissRequest = { reader = null },
            title = { Text("第${ch.no}章 ${ch.title}") },
            text = {
                Text(
                    ch.content.ifBlank { "（本章还没有内容，可能只有大纲要点）" },
                    fontSize = 15.sp, lineHeight = 23.sp, color = InkDark,
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()))
            },
            confirmButton = {
                TextButton(onClick = {
                    Nav.chapterNo = ch.no
                    Nav.screen = Screen.Editor
                    reader = null
                }) { Text("✏ 进写作台修改") }
            },
            dismissButton = {
                TextButton(onClick = { reader = null }) { Text("关闭") }
            })
    }

    // ---------- 删除章节 ----------
    deleteCh?.let { ch ->
        AlertDialog(
            onDismissRequest = { deleteCh = null },
            title = { Text("删除第${ch.no}章？") },
            text = { Text("「${ch.title.ifEmpty { "未命名" }}」正文将删除，且无法恢复。后面章节号不变。") },
            confirmButton = {
                TextButton(onClick = {
                    Db.deleteChapter(ch.id)
                    deleteCh = null
                    tick++
                }) { Text("删除", color = Color(0xFFC62828)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCh = null }) { Text("取消") }
            })
    }

    if (showOutline) {
        OutlineDialog(pid = pid, ctx = ctx, onDismiss = { showOutline = false },
            onApplied = { showOutline = false; tick++ })
    }
}

/** AI 大纲规划对话框（对应 PC 端 OutlineDialog：生成 → 预览 → 应用到章节表）。 */
@Composable
fun OutlineDialog(
    pid: Long,
    ctx: android.content.Context,
    onDismiss: () -> Unit,
    onApplied: () -> Unit
) {
    val maxNo = remember { Db.maxChapterNo(pid) }
    var fromText by remember { mutableStateOf((maxNo + 1).toString()) }
    var toText by remember { mutableStateOf((maxNo + 10).toString()) }
    var direction by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var stopFlag by remember { mutableStateOf(AtomicBoolean(false)) }

    fun generate() {
        val from = fromText.toIntOrNull()
        val to = toText.toIntOrNull()
        if (from == null || to == null || to < from) {
            status = "请填写正确的章节范围。"
            return
        }
        val cfg = Config.load(ctx)
        if (cfg.apiKey.isEmpty()) {
            status = "尚未配置 API Key，请到【设置】页填写。"
            return
        }
        busy = true; status = "AI 正在规划第${from}~${to}章大纲……"; preview = ""
        val flag = AtomicBoolean(false)
        stopFlag = flag
        job = AppScope.scope.launch {
            try {
                val prompt = Prompts.outlinePrompt(pid, from, to, direction)
                val reply = AiClient.chatRounds(cfg, Prompts.writerMessages(pid, prompt),
                    cancelled = { flag.get() })
                preview = reply.trim()
                status = if (preview.isEmpty()) "AI 没有返回内容，请重试。" else "生成完成，检查无误后点「应用」。"
                busy = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                status = "已停止。"
                busy = false
            } catch (e: Exception) {
                status = "❌ ${e.message ?: e.toString()}"
                busy = false
            }
        }
    }

    fun apply() {
        val lines = preview.lines()
            .map { it.trim() }
            .filter { it.startsWith("第") && it.contains("|") }
        if (lines.isEmpty()) {
            status = "没有可应用的大纲行（格式需为：第N章|标题|要点）。"
            return
        }
        var created = 0
        for (line in lines) {
            // 第N章|标题|剧情要点
            val noPart = line.substringBefore("|").removePrefix("第").trim()
            val no = noPart.filter { it.isDigit() }.toIntOrNull() ?: continue
            val rest = line.substringAfter("|")
            val title = rest.substringBefore("|").trim()
            val points = if (rest.contains("|")) rest.substringAfter("|").trim() else ""
            val exists = Db.chapter(pid, no)
            if (exists == null) {
                Db.upsertChapter(pid, no, title,
                    if (points.isEmpty()) "" else "【大纲】$points")
                created++
            }
        }
        status = "✅ 已应用：新建 $created 章（已存在的章节不动）。"
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("🧠 AI 规划章节大纲") },
        text = {
            Column {
                Row {
                    OutlinedTextField(
                        value = fromText, onValueChange = { fromText = it },
                        label = { Text("从第") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = toText, onValueChange = { toText = it },
                        label = { Text("到第") },
                        modifier = Modifier.weight(1f).padding(start = 8.dp))
                }
                OutlinedTextField(
                    value = direction, onValueChange = { direction = it },
                    label = { Text("接下来想写什么？（可留空，AI 按总纲推进）") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                TextButton(onClick = { generate() }, enabled = !busy) {
                    Text("① 生成大纲")
                }
                Text(
                    preview.ifEmpty { "生成的大纲会显示在这里，格式：第N章|标题|剧情要点" },
                    color = InkDark,
                    style = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()))
                Text(status, color = WarnOrange, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            if (busy) {
                TextButton(onClick = {
                    stopFlag.set(true); job?.cancel()
                }) { Text("■ 停止") }
            } else {
                TextButton(onClick = { apply() }, enabled = preview.isNotBlank()) {
                    Text("② 应用到章节表")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!busy) onDismiss() }) { Text("关闭") }
        })
}
