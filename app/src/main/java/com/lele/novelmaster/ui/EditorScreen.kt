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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lele.novelmaster.data.Chapter
import com.lele.novelmaster.data.Repo
import com.lele.novelmaster.data.WriterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun EditorScreen(nav: NavController, chapterId: Long) {
    val chapter by produceState<Chapter?>(null, chapterId) { value = Repo.dao.chapter(chapterId) }
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
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

    fun save() {
        scope.launch(Dispatchers.IO) {
            val latest = Repo.dao.chapter(chapterId) ?: return@launch
            Repo.dao.updateChapter(
                latest.copy(
                    title = title,
                    content = text,
                    wordCount = text.length,
                    status = if (text.isBlank()) 0 else 2,
                    updatedAt = System.currentTimeMillis()
                )
            )
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
                    busy = "AI续写中…"
                    err = null
                    scope.launch(Dispatchers.IO) {
                        try {
                            val newText = WriterEngine.continueChapter(chapterId, text)
                            text = newText
                            save()
                            withContext(Dispatchers.Main) { busy = null }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { busy = null; err = e.message?.take(200) }
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
                    busy = "AI重写中…"
                    err = null
                    scope.launch(Dispatchers.IO) {
                        try {
                            val error = WriterEngine.rewriteChapter(chapterId)
                            val fresh = Repo.dao.chapter(chapterId)
                            withContext(Dispatchers.Main) {
                                busy = null
                                if (fresh != null) {
                                    text = fresh.content
                                    title = fresh.title
                                }
                                if (error != null) err = error
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) { busy = null; err = e.message?.take(200) }
                        }
                    }
                }) { Text("重写") }
            },
            dismissButton = { TextButton(onClick = { confirmRewrite = false }) { Text("取消") } }
        )
    }
}
