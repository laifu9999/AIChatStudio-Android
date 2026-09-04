package com.lele.novelmaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.lele.novelmaster.data.Chapter
import com.lele.novelmaster.data.Project
import com.lele.novelmaster.data.Repo
import kotlinx.coroutines.flow.first

/* ---------------- 阅读设置（持久化到 SharedPreferences） ---------------- */
object ReaderPrefs {
    const val FILE = "reader_settings"
    fun load(ctx: android.content.Context): ReaderSettings {
        val sp = ctx.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE)
        return ReaderSettings(
            theme = sp.getInt("theme", 1),
            font = sp.getInt("font", 0),
            size = sp.getInt("size", 18),
            paging = sp.getInt("paging", 0)
        )
    }
    fun save(ctx: android.content.Context, s: ReaderSettings) {
        ctx.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).edit()
            .putInt("theme", s.theme).putInt("font", s.font).putInt("size", s.size).putInt("paging", s.paging)
            .apply()
    }
}
data class ReaderSettings(val theme: Int, val font: Int, val size: Int, val paging: Int)

data class ReaderTheme(val bg: Color, val text: Color, val name: String)
val ReaderThemes = listOf(
    ReaderTheme(Color(0xFFFFFFFF), Color(0xFF222222), "经典白"),
    ReaderTheme(Color(0xFFF5ECD9), Color(0xFF4A3B22), "羊皮纸"),
    ReaderTheme(Color(0xFFCCE8CF), Color(0xFF1F3A24), "护眼绿"),
    ReaderTheme(Color(0xFF141414), Color(0xFF9E9E9E), "夜间黑")
)
val ReaderFonts = listOf("默认黑体", "衬线宋体", "等宽")

/* ================= 书架（自动生成：每个会话=一本书） ================= */

@Composable
fun BookshelfScreen(nav: NavController) {
    val projects by Repo.dao.projectsFlow().collectAsState(initial = emptyList())
    var stats by remember { mutableStateOf<Map<Long, Pair<Int, Int>>>(emptyMap()) }
    LaunchedEffect(projects) {
        val m = mutableMapOf<Long, Pair<Int, Int>>()
        for (p in projects) {
            val chs = Repo.dao.chapters(p.id)
            m[p.id] = chs.count { it.content.isNotBlank() } to chs.sumOf { it.wordCount }
        }
        stats = m
    }

    AppScaffold("📚 我的书架", onBack = { nav.popBackStack() }) { pv ->
        if (projects.isEmpty()) {
            Column(
                Modifier.padding(pv).padding(24.dp).fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(80.dp))
                Text("书架还是空的", fontSize = 16.sp, color = Color(0xFF8A8698))
                Spacer(Modifier.height(8.dp))
                Text("回聊天页发一个灵感，AI 会自动建书并开写", fontSize = 13.sp, color = Color(0xFF8A8698))
            }
        } else {
            LazyColumn(
                Modifier.padding(pv).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
            ) {
                items(projects, key = { it.id }) { p ->
                    val (done, words) = stats[p.id] ?: (0 to 0)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(14.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { nav.navigate("reader/${p.id}") }
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            // 书封
                            Box(
                                modifier = Modifier
                                    .size(width = 56.dp, height = 76.dp)
                                    .background(
                                        Brush.verticalGradient(listOf(Color(0xFF6750A4), Color(0xFF8B5CF6))),
                                        RoundedCornerShape(8.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    p.title.take(1),
                                    color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("《${p.title}》", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF1F1B2E), maxLines = 1)
                                Spacer(Modifier.height(4.dp))
                                Text("${p.genre} · 已写 $done/${p.targetChapters} 章 · $words 字", fontSize = 12.sp, color = Color(0xFF8A8698))
                                Spacer(Modifier.height(8.dp))
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(5.dp)
                                        .background(Color(0x1A6750A4), RoundedCornerShape(3.dp))
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(
                                                if (p.targetChapters == 0) 0f
                                                else (done.toFloat() / p.targetChapters).coerceIn(0f, 1f)
                                            )
                                            .height(5.dp)
                                            .background(Color(0xFF6750A4), RoundedCornerShape(3.dp))
                                    )
                                }
                            }
                            Text("›", fontSize = 22.sp, color = Color(0xFFB0AACC))
                        }
                    }
                }
            }
        }
    }
}

/* ================= 阅读器（整本书连续阅读） ================= */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(nav: NavController, pid: Long) {
    val ctx = LocalContext.current
    val project by produceState<Project?>(null, pid) { value = Repo.dao.project(pid) }
    val chapters by Repo.dao.chaptersFlow(pid).collectAsState(initial = emptyList())
    var settings by remember { mutableStateOf(ReaderPrefs.load(ctx)) }
    var showSettings by remember { mutableStateOf(false) }

    val theme = ReaderThemes[settings.theme.coerceIn(0, 3)]
    val fontFamily = when (settings.font.coerceIn(0, 2)) {
        1 -> FontFamily.Serif
        2 -> FontFamily.Monospace
        else -> FontFamily.Default
    }
    val fontSize = settings.size.coerceIn(12, 34)
    val textStyle = TextStyle(fontFamily = fontFamily, fontSize = fontSize.sp, lineHeight = (fontSize * 1.75).sp, color = theme.text)
    val written = chapters.filter { it.content.isNotBlank() }

    AppScaffold(
        title = "阅读 · ${project?.title ?: ""}",
        onBack = { nav.popBackStack() },
        actions = {
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Filled.Settings, "阅读设置")
            }
        }
    ) { pv ->
        if (written.isEmpty()) {
            Column(Modifier.padding(pv).padding(24.dp).fillMaxSize().background(theme.bg)) {
                Spacer(Modifier.height(80.dp))
                Text("这本书还没有正文。回聊天页说「写下一章」让 AI 开写。", fontSize = 14.sp, color = Color(0xFF8A8698))
            }
            return@AppScaffold
        }

        if (settings.paging == 0) {
            // 上下滚动
            LazyColumn(
                Modifier.padding(pv).fillMaxSize().background(theme.bg),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp)
            ) {
                items(written, key = { it.id }) { ch ->
                    ChapterBlock(ch, textStyle, theme, onEdit = { nav.navigate("editor/${ch.id}") })
                }
            }
        } else {
            // 左右翻页（每章一页，章内可滚动）
            val pagerState = rememberPagerState { written.size }
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                modifier = Modifier.padding(pv).fillMaxSize().background(theme.bg)
            ) { page ->
                val ch = written[page]
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
                ) {
                    ChapterBlockContent(ch, textStyle, theme, onEdit = { nav.navigate("editor/${ch.id}") })
                    Spacer(Modifier.height(30.dp))
                    Text(
                        "${page + 1} / ${written.size}  ·  左右滑动翻页",
                        fontSize = 11.sp, color = theme.text.copy(alpha = 0.5f),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
            }
        }

        if (showSettings) {
            ModalBottomSheet(onDismissRequest = { showSettings = false }) {
                Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 30.dp)) {
                    Text("阅读设置", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Spacer(Modifier.height(14.dp))
                    Text("主题", fontSize = 13.sp, color = Color(0xFF8A8698))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ReaderThemes.forEachIndexed { i, t ->
                            AssistChip(
                                onClick = { settings = settings.copy(theme = i); ReaderPrefs.save(ctx, settings) },
                                label = { Text(t.name, color = if (settings.theme == i) Color(0xFF6750A4) else Color(0xFF555555)) },
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, if (settings.theme == i) Color(0xFF6750A4) else Color(0x33000000)
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("字体", fontSize = 13.sp, color = Color(0xFF8A8698))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderFonts.forEachIndexed { i, f ->
                            AssistChip(
                                onClick = { settings = settings.copy(font = i); ReaderPrefs.save(ctx, settings) },
                                label = { Text(f, color = if (settings.font == i) Color(0xFF6750A4) else Color(0xFF555555)) },
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp, if (settings.font == i) Color(0xFF6750A4) else Color(0x33000000)
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("字号：${fontSize}sp", fontSize = 13.sp, color = Color(0xFF8A8698))
                    Slider(
                        value = fontSize.toFloat(),
                        onValueChange = { settings = settings.copy(size = it.toInt()); ReaderPrefs.save(ctx, settings) },
                        valueRange = 12f..34f, steps = 10
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("翻页方式", fontSize = 13.sp, color = Color(0xFF8A8698))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { settings = settings.copy(paging = 0); ReaderPrefs.save(ctx, settings) },
                            label = { Text("上下滚动", color = if (settings.paging == 0) Color(0xFF6750A4) else Color(0xFF555555)) },
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, if (settings.paging == 0) Color(0xFF6750A4) else Color(0x33000000)
                            )
                        )
                        AssistChip(
                            onClick = { settings = settings.copy(paging = 1); ReaderPrefs.save(ctx, settings) },
                            label = { Text("左右翻页", color = if (settings.paging == 1) Color(0xFF6750A4) else Color(0xFF555555)) },
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp, if (settings.paging == 1) Color(0xFF6750A4) else Color(0x33000000)
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterBlock(ch: Chapter, textStyle: TextStyle, theme: ReaderTheme, onEdit: () -> Unit) {
    Column(Modifier.padding(bottom = 28.dp)) {
        ChapterBlockContent(ch, textStyle, theme, onEdit)
    }
}

@Composable
private fun ChapterBlockContent(ch: Chapter, textStyle: TextStyle, theme: ReaderTheme, onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "第${ch.chapterIndex}章  ${ch.title}",
            style = textStyle.copy(fontWeight = FontWeight.Bold, fontSize = (textStyle.fontSize.value * 1.2).sp),
            modifier = Modifier.padding(bottom = 14.dp)
        )
        Text(ch.content, style = textStyle)
        Spacer(Modifier.height(14.dp))
        Button(
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
            onClick = onEdit
        ) { Text("✏️ 编辑本章（修改后自动保存）", fontSize = 13.sp) }
        Spacer(Modifier.height(20.dp))
    }
}
