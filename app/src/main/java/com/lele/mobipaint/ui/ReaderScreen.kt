package com.lele.mobipaint.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lele.mobipaint.Db

data class ReaderTheme(val name: String, val bg: Color, val fg: Color)

val READER_THEMES = listOf(
    ReaderTheme("☀ 日间", Color(0xFFFFFFFF), Color(0xFF2B2F3A)),
    ReaderTheme("🍀 护眼", Color(0xFFCCE8CF), Color(0xFF2F3A2F)),
    ReaderTheme("🌙 夜间", Color(0xFF1E2233), Color(0xFFC9CEDE)))

/**
 * 阅读器（对应 PC 端「📖 阅读器」）：
 * 像看电子书一样读自己的小说，目录搜索跳章、字号/主题切换、一键进写作台修改。
 */
@Composable
fun ReaderScreen() {
    val pid = Nav.pid
    BackHandler { Nav.screen = Screen.Chat }

    var chapters by remember { mutableStateOf(Db.listChapters(pid)) }
    var no by remember {
        mutableIntStateOf(if (Nav.chapterNo > 0) Nav.chapterNo
            else chapters.firstOrNull()?.no ?: 0)
    }
    var themeIdx by remember { mutableIntStateOf(0) }
    var fontSp by remember { mutableIntStateOf(17) }
    var showToc by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }

    val ch = remember(chapters, no) { chapters.firstOrNull { it.no == no } }
    val theme = READER_THEMES[themeIdx]

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar("📖 阅读器", onBack = { Nav.screen = Screen.Chat }) {
            TextButtonSmall("✏ 修改", {
                Nav.chapterNo = no
                Nav.screen = Screen.Editor
            })
        }

        // 工具行：上一章 / 下一章 / 目录 / 字号 / 主题
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text("◀ 上一章", color = Brand, fontSize = 13.sp,
                modifier = Modifier
                    .clickable {
                        val prev = chapters.lastOrNull { it.no < no }
                        if (prev != null) no = prev.no
                    }
                    .padding(horizontal = 6.dp, vertical = 6.dp))
            Text("目录", color = Brand, fontSize = 13.sp,
                modifier = Modifier
                    .clickable { showToc = true }
                    .padding(horizontal = 6.dp, vertical = 6.dp))
            Text("下一章 ▶", color = Brand, fontSize = 13.sp,
                modifier = Modifier
                    .clickable {
                        val next = chapters.firstOrNull { it.no > no }
                        if (next != null) no = next.no
                    }
                    .padding(horizontal = 6.dp, vertical = 6.dp))
            Text("A-", color = Brand, fontSize = 13.sp,
                modifier = Modifier
                    .clickable { if (fontSp > 12) fontSp-- }
                    .padding(horizontal = 6.dp, vertical = 6.dp))
            Text("A+", color = Brand, fontSize = 13.sp,
                modifier = Modifier
                    .clickable { if (fontSp < 28) fontSp++ }
                    .padding(horizontal = 6.dp, vertical = 6.dp))
            Text(theme.name, color = Brand, fontSize = 13.sp,
                modifier = Modifier
                    .clickable { themeIdx = (themeIdx + 1) % READER_THEMES.size }
                    .padding(horizontal = 6.dp, vertical = 6.dp))
        }

        if (ch == null) {
            Text("本书还没有章节，去【对话创作】或【章节管理】生成吧。",
                color = Color(0xFF8A8FA0), fontSize = 14.sp,
                modifier = Modifier.padding(32.dp))
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(theme.bg)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("第${ch.no}章 ${ch.title.ifEmpty { "未命名" }}",
                    color = theme.fg, fontSize = (fontSp + 3).sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp))
                Text("${ch.status} · ${ch.words} 字 · 第${ch.no}/${chapters.size}章",
                    color = theme.fg.copy(alpha = 0.55f), fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Text("──────", color = theme.fg.copy(alpha = 0.35f), fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                Text(
                    ch.content.ifBlank { "（本章还没有内容）" },
                    color = theme.fg,
                    style = TextStyle(fontSize = fontSp.sp, lineHeight = (fontSp + 9).sp),
                    modifier = Modifier.fillMaxWidth().padding(18.dp))
            }
        }
    }

    // ---------- 目录对话框（带搜索） ----------
    if (showToc) {
        AlertDialog(
            onDismissRequest = { showToc = false },
            title = { Text("目录（${chapters.size}章）") },
            text = {
                Column {
                    OutlinedTextField(
                        value = search, onValueChange = { search = it },
                        placeholder = { Text("🔍 搜索章节名…", fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    val filtered = chapters.filter { c ->
                        val kw = search.trim()
                        kw.isEmpty() || c.title.contains(kw, ignoreCase = true) ||
                            c.no.toString() == kw
                    }
                    LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        items(filtered, key = { it.id }) { c ->
                            Text(
                                "第${c.no}章 ${c.title.ifEmpty { "未命名" }}" +
                                    if (c.words == 0) "  ␀" else "",
                                color = if (c.no == no) Color.White else InkDark,
                                fontSize = 14.sp,
                                maxLines = 1,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (c.no == no) Brand
                                        else MaterialTheme.colorScheme.surface)
                                    .clickable {
                                        no = c.no
                                        showToc = false
                                        search = ""
                                    }
                                    .padding(horizontal = 10.dp, vertical = 9.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showToc = false }) { Text("关闭") }
            })
    }
}
