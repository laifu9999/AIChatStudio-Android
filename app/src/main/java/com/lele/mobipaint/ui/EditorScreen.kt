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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lele.mobipaint.AiClient
import com.lele.mobipaint.AppScope
import com.lele.mobipaint.Config
import com.lele.mobipaint.Db
import com.lele.mobipaint.Prompts
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

val CHAPTER_STATUS = listOf("草稿", "已完成", "已发布")

/**
 * 写作台（对应 PC 端「✍ 写作台」）：
 * 选章节写正文 + AI 助手（续写/润色/扩写/自定义指令），内容停手自动保存。
 */
@Composable
fun EditorScreen() {
    val pid = Nav.pid
    val ctx = LocalContext.current
    BackHandler { Nav.screen = Screen.Chat }

    var chapters by remember { mutableStateOf(Db.listChapters(pid)) }
    var curId by remember { mutableStateOf(0L) }
    var showPicker by remember { mutableStateOf(false) }
    var showStatus by remember { mutableStateOf(false) }

    // 定位章节：从阅读器「修改本章」进来带 Nav.chapterNo，否则定位最新一章
    LaunchedEffect(Unit) {
        if (curId == 0L) {
            val no = if (Nav.chapterNo > 0) Nav.chapterNo else Db.maxChapterNo(pid)
            curId = chapters.firstOrNull { it.no == no }?.id
                ?: chapters.lastOrNull()?.id ?: 0L
            Nav.chapterNo = 0
        }
    }

    val cur = chapters.firstOrNull { it.id == curId }
    var text by remember(curId) {
        mutableStateOf(TextFieldValue(cur?.content ?: ""))
    }

    // 停手 1.5 秒自动保存（防抖）
    LaunchedEffect(text, curId) {
        val ch = chapters.firstOrNull { it.id == curId } ?: return@LaunchedEffect
        if (text.text == ch.content) return@LaunchedEffect
        delay(1500)
        Db.updateChapter(curId, content = text.text)
        chapters = Db.listChapters(pid)
    }

    // 离开页面兜底保存
    val latestText = rememberUpdatedState(text)
    val latestCurId = rememberUpdatedState(curId)
    DisposableEffect(Unit) {
        onDispose {
            val cid = latestCurId.value
            if (cid > 0) Db.updateChapter(cid, content = latestText.value.text)
        }
    }

    // ---------- AI 助手 ----------
    var aiOut by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var aiInput by remember { mutableStateOf("") }
    var aiJob by remember { mutableStateOf<Job?>(null) }
    var stopFlag by remember { mutableStateOf(AtomicBoolean(false)) }

    fun runAi(prompt: String) {
        if (aiBusy) return
        if (prompt.isBlank()) return
        val cfg = Config.load(ctx)
        if (cfg.apiKey.isEmpty()) {
            aiError = "尚未配置 API Key，请到【设置】页填写。"
            return
        }
        // 跑 AI 前先保存当前正文
        if (curId > 0) Db.updateChapter(curId, content = text.text)
        aiBusy = true; aiOut = ""; aiError = null
        val flag = AtomicBoolean(false)
        stopFlag = flag
        aiJob = AppScope.scope.launch {
            try {
                val buf = StringBuilder()
                val reply = AiClient.chatRounds(cfg, Prompts.writerMessages(pid, prompt),
                    onChunk = { p ->
                        buf.append(p)
                        aiOut = buf.toString()
                    }, cancelled = { flag.get() })
                aiOut = reply
                aiBusy = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                aiBusy = false
            } catch (e: Exception) {
                aiError = "❌ ${e.message ?: e.toString()}"
                aiBusy = false
            }
        }
    }

    fun stopAi() {
        stopFlag.set(true)
        aiJob?.cancel()
    }

    fun selectionText(): String? {
        val s = text.selection
        if (s.collapsed) return null
        val a = minOf(s.min, s.max); val b = maxOf(s.min, s.max)
        return text.text.substring(a, b).trim().ifEmpty { null }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar("✍ 写作台", onBack = { Nav.screen = Screen.Chat }) {
            TextButtonSmall("🗂", { Nav.screen = Screen.SettingMgr })
        }

        // 章节选择行
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                if (cur != null) "第${cur.no}章 ${cur.title.ifEmpty { "未命名" }} ▾"
                else "（还没有章节，先去对话页生成）",
                color = Brand, fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Brand.copy(alpha = 0.08f))
                    .clickable { showPicker = true }
                    .padding(horizontal = 10.dp, vertical = 8.dp))
            Text("${text.text.length} 字", color = Color(0xFF8A8FA0), fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp))
            Text(
                (cur?.status ?: "草稿") + " ▾",
                color = WarnOrange, fontSize = 13.sp,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(WarnOrange.copy(alpha = 0.08f))
                    .clickable { if (cur != null) showStatus = true }
                    .padding(horizontal = 8.dp, vertical = 8.dp))
        }

        // 正文编辑
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            enabled = cur != null,
            placeholder = { Text("在这里写正文…\n\n· 写到一半卡住就点下方「AI 续写」\n· 选中一段文字可「润色/扩写」\n· 内容停手自动保存",
                fontSize = 13.sp, color = Color(0xFF9AA0B2)) },
            textStyle = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, color = InkDark),
            modifier = Modifier.fillMaxWidth().weight(1.1f).padding(horizontal = 10.dp, vertical = 4.dp))

        // AI 指令 + 动作按钮
        OutlinedTextField(
            value = aiInput, onValueChange = { aiInput = it },
            placeholder = { Text("给 AI 下指令：如「让反派登场制造冲突」", fontSize = 12.sp) },
            textStyle = TextStyle(fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp))
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                TextButtonSmall("发送指令", {
                    if (aiInput.isNotBlank()) { runAi(Prompts.editorChatPrompt(pid, curId, aiInput)); aiInput = "" }
                }, WarnOrange)
            }
            item { TextButtonSmall("✍ 续写1500字", {
                if (cur != null && text.text.isNotBlank())
                    runAi(Prompts.continuePrompt(pid, text.text, 1500))
            }) }
            item { TextButtonSmall("✍ 续写3000字", {
                if (cur != null && text.text.isNotBlank())
                    runAi(Prompts.continuePrompt(pid, text.text, 3000))
            }) }
            item { TextButtonSmall("✨ 润色选中", {
                val sel = selectionText()
                if (sel != null) runAi(Prompts.polishPrompt(sel))
                else aiError = "请先在正文中长按选中要润色的文字。"
            }) }
            item { TextButtonSmall("📝 扩写选中", {
                val sel = selectionText()
                if (sel != null) runAi(Prompts.expandPrompt(sel))
                else aiError = "请先在正文中长按选中要扩写的文字。"
            }) }
        }

        // AI 输出区
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
            Text(
                when {
                    aiBusy -> (aiOut.ifEmpty { "AI 正在生成……" })
                    aiError != null -> aiError ?: ""
                    aiOut.isNotEmpty() -> aiOut
                    else -> "AI 输出显示在这里。生成后可「追加到正文」或「替换选中文本」。"
                },
                color = if (aiError != null) Color(0xFFC62828) else InkDark,
                fontSize = 13.sp, lineHeight = 20.sp,
                maxLines = if (aiOut.isNotEmpty() || aiError != null) Int.MAX_VALUE else 2,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            TextButtonSmall("⬇ 追加到正文", {
                if (aiOut.isNotBlank() && cur != null) {
                    val base = text.text.trimEnd()
                    text = TextFieldValue(
                        text = (if (base.isEmpty()) "" else base + "\n\n") + aiOut,
                        selection = TextRange((if (base.isEmpty()) 0 else base.length + 2) + aiOut.length))
                }
            })
            Text(" 替换选中", color = Brand, fontSize = 13.sp,
                modifier = Modifier
                    .clickable {
                        val s = text.selection
                        if (!s.collapsed && aiOut.isNotBlank()) {
                            val a = minOf(s.min, s.max); val b = maxOf(s.min, s.max)
                            val newText = text.text.substring(0, a) + aiOut + text.text.substring(b)
                            val newCursor = a + aiOut.length
                            text = TextFieldValue(newText, TextRange(newCursor))
                        } else {
                            aiError = "请先选中要替换的文字，再点击替换。"
                        }
                    }
                    .padding(8.dp))
            if (aiBusy) {
                Text("■ 停止", color = Color(0xFFC62828), fontSize = 13.sp,
                    modifier = Modifier.clickable { stopAi() }.padding(8.dp))
            }
        }
    }

    // ---------- 章节选择对话框 ----------
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("选择章节（${chapters.size}章）") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(chapters, key = { it.id }) { ch ->
                        Text(
                            "第${ch.no}章 ${ch.title.ifEmpty { "未命名" }}（${ch.words}字）",
                            color = if (ch.id == curId) Color.White else InkDark,
                            fontSize = 14.sp,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (ch.id == curId) Brand
                                    else MaterialTheme.colorScheme.surface)
                                .clickable {
                                    // 切章前保存当前章节
                                    if (curId > 0 && text.text != cur?.content) {
                                        Db.updateChapter(curId, content = text.text)
                                        chapters = Db.listChapters(pid)
                                    }
                                    curId = ch.id
                                    showPicker = false
                                }
                                .padding(horizontal = 10.dp, vertical = 9.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) { Text("关闭") }
            })
    }

    // ---------- 状态切换对话框 ----------
    if (showStatus && cur != null) {
        AlertDialog(
            onDismissRequest = { showStatus = false },
            title = { Text("章节状态") },
            text = {
                Column {
                    CHAPTER_STATUS.forEach { st ->
                        Text(
                            st + if (cur.status == st) "  ✓" else "",
                            color = if (cur.status == st) Brand else InkDark,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    Db.updateChapter(cur.id, status = st)
                                    chapters = Db.listChapters(pid)
                                    showStatus = false
                                }
                                .padding(vertical = 10.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showStatus = false }) { Text("取消") }
            })
    }
}
