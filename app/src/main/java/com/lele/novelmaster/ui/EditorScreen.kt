package com.lele.novelmaster.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lele.novelmaster.data.Chapter
import com.lele.novelmaster.data.Repo
import com.lele.novelmaster.data.WriterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EditorScreen(nav: NavController, chapterId: Long) {
    val ctx = LocalContext.current
    val chapter by produceState<Chapter?>(null, chapterId) { value = Repo.dao.chapter(chapterId) }
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    // v6.9.37：AI续写/AI重写忙闲从 AppTasks 全局取——离开编辑页任务照常完成并落库，回来自动显示进度/结果
    val appTasks by com.lele.novelmaster.engine.AppTasks.state.collectAsState()
    val busy = when {
        "continue:$chapterId" in appTasks.running -> "AI续写中…"
        "rewrite:$chapterId" in appTasks.running -> "AI重写中…"
        else -> null
    }
    var savedAt by remember { mutableStateOf<Long?>(null) }
    var confirmRewrite by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("HH:mm", Locale.CHINA) }

    val c = chapter ?: return
    if (!loaded) {
        title = c.title
        text = c.content
        loaded = true
        editing = c.content.isBlank()
    }

    // v6.9.37：落库逻辑抽成可在任意协程调用的 persist，save() 与 AI续写共用
    suspend fun persist(content: String, t: String) {
        val latest = Repo.dao.chapter(chapterId) ?: return
        Repo.dao.updateChapter(
            latest.copy(
                title = t,
                content = content,
                wordCount = content.length,
                status = if (content.isBlank()) 0 else 2,
                updatedAt = System.currentTimeMillis()
            )
        )
        // v5.5：同步更新项目文件里的本章 txt，书架阅读看到的是最新内容
        runCatching {
            val base = File(ctx.filesDir, "novels/${latest.projectId}/files/正文")
            base.mkdirs()
            val safeTitle = t.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40).ifBlank { "未命名" }
            File(base, "第${latest.chapterIndex}章-$safeTitle.txt")
                .writeText(content + "\n", Charsets.UTF_8)
        }
    }

    fun save() {
        scope.launch(Dispatchers.IO) {
            persist(text, title)
            savedAt = System.currentTimeMillis()
        }
    }

    AppScaffold(
        "第${c.chapterIndex}章",
        onBack = { nav.popBackStack() },
        actions = {
            TextButton(onClick = { editing = !editing }) { Text(if (editing) "阅读" else "编辑") }
            TextButton(
                enabled = busy == null,
                onClick = {
                    err = null
                    val srcText = text
                    // v6.9.37：跑在 AppTasks 单例——离开编辑页续写照常完成并落库，回来正文自动刷新
                    com.lele.novelmaster.engine.AppTasks.launch("continue:$chapterId") {
                        try {
                            val newText = WriterEngine.continueChapter(chapterId, srcText)
                            persist(newText, title)
                            withContext(Dispatchers.Main) {
                                text = newText
                                savedAt = System.currentTimeMillis()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { err = e.message?.take(200) }
                        }
                    }
                }
            ) { Text("AI续写") }
            TextButton(enabled = busy == null, onClick = { confirmRewrite = true }) { Text("AI重写") }
            TextButton(onClick = { save() }) { Text("保存") }
        }
    ) { pv ->
        Column(Modifier.padding(pv).padding(12.dp).fillMaxSize()) {
            if (editing) {
                OutlinedTextField(
                    title, { title = it },
                    label = { Text("章节标题") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    text, { text = it },
                    label = { Text("正文（支持手动编辑，也可用AI续写/重写）") },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            } else {
                Text(title.ifBlank { "（未命名）" }, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                if (text.isBlank()) {
                    Text(
                        "本章还没有内容。右上角切到「编辑」手写，或用 AI续写 / 自动写作。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            if (busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${text.length}字", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                savedAt?.let {
                    Text(
                        "已保存 ${fmt.format(Date(it))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                busy?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                err?.let { Text("出错了：$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (confirmRewrite) {
        AlertDialog(
            onDismissRequest = { confirmRewrite = false },
            title = { Text("AI重写本章？") },
            text = { Text("将按本章大纲与设定重新生成本章正文，替换现有内容（建议先手动保存备份）。继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRewrite = false
                    err = null
                    // v6.9.37：跑在 AppTasks 单例——离开编辑页重写照常完成并落库，回来正文自动刷新
                    com.lele.novelmaster.engine.AppTasks.launch("rewrite:$chapterId") {
                        try {
                            val error = WriterEngine.rewriteChapter(chapterId)
                            val fresh = Repo.dao.chapter(chapterId)
                            withContext(Dispatchers.Main) {
                                if (fresh != null) {
                                    text = fresh.content
                                    title = fresh.title
                                }
                                if (error != null) err = error
                                savedAt = System.currentTimeMillis()
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { err = e.message?.take(200) }
                        }
                    }
                }) { Text("重写") }
            },
            dismissButton = { TextButton(onClick = { confirmRewrite = false }) { Text("取消") } }
        )
    }
}
