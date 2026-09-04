package com.lele.mobipaint.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lele.mobipaint.Db
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 发布平台标准（与 PC 端 exporter.PLATFORMS 同源）。 */
data class PubPlatform(val name: String, val minWords: Int, val tip: String)

val PUB_PLATFORMS = listOf(
    PubPlatform("番茄", 2000, "番茄小说建议单章 ≥2000 字，章节标题用「第N章 标题」。"),
    PubPlatform("飞卢", 2000, "飞卢建议单章 2000~3000 字，节奏快、爆点多，标题可带爽点关键词。"),
    PubPlatform("起点", 2000, "起点建议单章 2000~4000 字，VIP 章节计费按字数，注意质量。"))

/**
 * 发布导出（对应 PC 端「🚀 发布导出」）：
 * 平台体检报告 + 一键导出 TXT（保存到手机 + 直接分享给番茄助手/微信/QQ 等）。
 */
@Composable
fun PublishScreen() {
    val pid = Nav.pid
    val ctx = LocalContext.current
    BackHandler { Nav.screen = Screen.Chat }

    var platformIdx by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var exportAll by remember { mutableStateOf(false) }
    var exportOne by remember { mutableStateOf<Long?>(null) }
    var toast by remember { mutableStateOf("") }

    val platform = PUB_PLATFORMS[platformIdx]
    val proj = remember(tick) { Db.project(pid) }
    val chapters = remember(tick) { Db.listChapters(pid) }
    val totalWords = chapters.sumOf { it.words }
    val okCount = chapters.count { it.words >= platform.minWords }
    val draftCount = chapters.count { it.status == "草稿" }

    fun shareText(title: String, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        ctx.startActivity(Intent.createChooser(send, "分享«$title»"))
    }

    fun saveFile(fname: String, content: String): String {
        val dir = File(ctx.getExternalFilesDir(null), "导出").apply { mkdirs() }
        val f = File(dir, fname)
        f.writeText(content, Charsets.UTF_8)
        return f.absolutePath
    }

    fun bookText(): String {
        val sb = StringBuilder()
        sb.append("书名：${proj?.title ?: ""}\n作者：____\n题材：${proj?.genre ?: ""}\n")
        if (!proj?.brief.isNullOrBlank()) sb.append("简介：${proj?.brief}\n")
        sb.append("=".repeat(40) + "\n\n")
        for (ch in chapters) {
            sb.append("第${ch.no}章 ${ch.title.ifEmpty { "未命名" }}\n\n")
            sb.append(ch.content.trim() + "\n\n\n")
        }
        return sb.toString()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar("🚀 发布导出", onBack = { Nav.screen = Screen.Chat })

        // 平台选择
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            PUB_PLATFORMS.forEachIndexed { i, p ->
                val selected = i == platformIdx
                Text(p.name,
                    color = if (selected) Color.White else Brand,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Brand else Brand.copy(alpha = 0.08f))
                        .clickable { platformIdx = i }
                        .padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
        Text(platform.tip, color = Color(0xFF8A8FA0), fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 12.dp))

        // 统计卡
        Row(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            StatCard("总章数", "${chapters.size}", Modifier.weight(1f))
            StatCard("总字数", "$totalWords", Modifier.weight(1f))
            StatCard("达标章节", "$okCount/${chapters.size}", Modifier.weight(1f))
            StatCard("草稿章节", "$draftCount", Modifier.weight(1f))
        }

        // 逐章体检
        if (chapters.isEmpty()) {
            Text("本书还没有章节，先去生成章节再来体检。",
                color = Color(0xFF8A8FA0), fontSize = 13.sp,
                modifier = Modifier.padding(24.dp))
        } else {
            LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                items(chapters, key = { it.id }) { ch ->
                    val ok = ch.words >= platform.minWords
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { exportOne = ch.id }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text("第${ch.no}章 ${ch.title.ifEmpty { "未命名" }}",
                            color = InkDark, fontSize = 13.sp,
                            maxLines = 1, modifier = Modifier.weight(1f))
                        Text("${ch.words}字", color = Color(0xFF8A8FA0), fontSize = 12.sp)
                        Text(if (ok) " ✅" else " ❌偏短", fontSize = 12.sp)
                    }
                }
            }
        }

        // 导出按钮行
        Row(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            TextButtonSmall("📦 导出全本 TXT", {
                if (chapters.isEmpty()) {
                    toast = "本书还没有任何章节，无法导出。"
                } else {
                    exportAll = true
                }
            })
            Text("点章节行可导出单章", color = Color(0xFF8A8FA0), fontSize = 12.sp,
                modifier = Modifier.padding(start = 10.dp, top = 8.dp))
        }
        if (toast.isNotEmpty()) {
            Text(toast, color = WarnOrange, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        }
    }

    // ---------- 全本导出结果 ----------
    if (exportAll) {
        val fname = ("${proj?.title ?: "未命名"}_${platform.name}版_"
            + SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(Date()) + ".txt")
        val path = saveFile(fname, bookText())
        AlertDialog(
            onDismissRequest = { exportAll = false },
            title = { Text("✅ 全本导出完成") },
            text = {
                Text("共 ${chapters.size} 章、$totalWords 字。\n\n已保存到手机：\n$path\n\n" +
                    "也可以直接分享给番茄作家助手 / 网易蜂窝 / 微信传书等。",
                    fontSize = 13.sp, lineHeight = 20.sp)
            },
            confirmButton = {
                TextButton(onClick = {
                    shareText(fname.removeSuffix(".txt"), bookText())
                    exportAll = false
                }) { Text("📤 分享 TXT") }
            },
            dismissButton = {
                TextButton(onClick = { exportAll = false }) { Text("完成") }
            })
    }

    // ---------- 单章导出结果 ----------
    exportOne?.let { cid ->
        val ch = Db.chapterById(cid)
        if (ch != null) {
            val fname = "第%04d章_%s.txt".format(ch.no, ch.title.take(20).ifEmpty { "未命名" })
            val content = "第${ch.no}章 ${ch.title}\n\n${ch.content.trim()}\n"
            val path = saveFile(fname, content)
            AlertDialog(
                onDismissRequest = { exportOne = null },
                title = { Text("✅ 单章导出完成") },
                text = {
                    Text("第${ch.no}章（${ch.words}字）\n\n已保存到：\n$path\n\n也可直接分享文本。",
                        fontSize = 13.sp, lineHeight = 20.sp)
                },
                confirmButton = {
                    TextButton(onClick = {
                        shareText(fname.removeSuffix(".txt"), content)
                        exportOne = null
                    }) { Text("📤 分享") }
                },
                dismissButton = {
                    TextButton(onClick = { exportOne = null }) { Text("完成") }
                })
        }
    }
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 10.dp)
    ) {
        Text(value, color = Brand, fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 8.dp))
        Text(label, color = Color(0xFF8A8FA0), fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp))
    }
}
