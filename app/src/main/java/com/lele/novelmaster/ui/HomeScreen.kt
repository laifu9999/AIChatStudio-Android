package com.lele.novelmaster.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lele.novelmaster.data.Chapter
import com.lele.novelmaster.data.Project
import com.lele.novelmaster.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(nav: NavController) {
    val projects by Repo.dao.projectsFlow().collectAsState(initial = emptyList())
    var showCreate by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
                    importTxt(text)
                    withContext(Dispatchers.Main) { Toast.makeText(ctx, "导入成功", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(ctx, "导入失败：${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    AppScaffold("乐乐写小说", actions = {
        TextButton(onClick = { nav.navigate("ai") }) { Text("AI模型") }
    }) { pv ->
        Column(Modifier.padding(pv).padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { showCreate = true }, modifier = Modifier.weight(1f)) { Text("＋ 新建小说") }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("text/*")) },
                    modifier = Modifier.weight(1f)
                ) { Text("导入TXT建书") }
            }
            Spacer(Modifier.height(12.dp))
            if (projects.isEmpty()) {
                Text(
                    "还没有作品。点「新建小说」创建，或导入TXT。\n\n推荐流程：新建 → 设定卡里用「灵感分析」一键生成世界观/人物/大纲 → 自动写作。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(projects, key = { it.id }) { p ->
                    ElevatedCard(Modifier.fillMaxWidth().clickable { nav.navigate("project/${p.id}") }) {
                        Row(
                            Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${p.genre} · 目标${p.targetChapters}章 · 每章约${p.chapterWordTarget}字",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) { Repo.dao.deleteProject(p) }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateProjectDialog(
            onDismiss = { showCreate = false },
            onCreate = { title, genre, desc, target, perChapter ->
                showCreate = false
                scope.launch(Dispatchers.IO) {
                    val pid = Repo.dao.insertProject(
                        Project(
                            title = title, genre = genre, description = desc,
                            targetChapters = target, chapterWordTarget = perChapter
                        )
                    )
                    Repo.dao.insertChapters((1..target).map { Chapter(projectId = pid, chapterIndex = it) })
                }
            }
        )
    }
}

@Composable
private fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String, Int, Int) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("都市") }
    var desc by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("300") }
    var per by remember { mutableStateOf("2500") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建小说") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    title, { title = it },
                    label = { Text("书名（必填）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    genre, { genre = it },
                    label = { Text("类型：都市/玄幻/悬疑/言情…") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    desc, { desc = it },
                    label = { Text("简介/一句话灵感（可留空，稍后用灵感分析）") },
                    modifier = Modifier.fillMaxWidth().height(90.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        target, { target = it },
                        label = { Text("目标章数") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        per, { per = it },
                        label = { Text("每章字数") },
                        singleLine = true, modifier = Modifier.weight(1f)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isNotBlank()) {
                    onCreate(
                        title.trim(), genre.trim(), desc.trim(),
                        target.toIntOrNull() ?: 300, per.toIntOrNull() ?: 2500
                    )
                }
            }) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 导入TXT：按「第X章」自动分章 */
private suspend fun importTxt(text: String) {
    val dao = Repo.dao
    val title = text.lines().firstOrNull { it.isNotBlank() }?.take(30)?.trim() ?: "导入作品"
    val pid = dao.insertProject(Project(title = title, genre = "导入", description = "由TXT导入"))
    val regex = Regex("^\\s*第[0-9一二三四五六七八九十百千万零]{1,10}[章节回]")

    val list = mutableListOf<Triple<String, StringBuilder, Unit>>()
    var curTitle: String? = null
    var curSb = StringBuilder()
    val pre = StringBuilder()
    for (line in text.lines()) {
        val m = regex.find(line)
        if (m != null) {
            if (pre.isNotBlank()) {
                list.add(Triple("开篇", pre, Unit))
                pre.clear()
            }
            if (curTitle != null) list.add(Triple(curTitle!!, curSb, Unit))
            curTitle = line.trim()
            curSb = StringBuilder()
        } else {
            if (curTitle == null) pre.appendLine(line) else curSb.appendLine(line)
        }
    }
    if (curTitle != null) list.add(Triple(curTitle!!, curSb, Unit))
    if (list.isEmpty()) list.add(Triple("全文", StringBuilder(text), Unit))

    dao.insertChapters(
        list.mapIndexed { i, t ->
            Chapter(
                projectId = pid, chapterIndex = i + 1, title = t.first,
                content = t.second.toString(), wordCount = t.second.length, status = 2
            )
        }
    )
}
