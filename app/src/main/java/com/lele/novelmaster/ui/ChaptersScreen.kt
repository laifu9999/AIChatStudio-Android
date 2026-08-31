package com.lele.novelmaster.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lele.novelmaster.data.Chapter
import com.lele.novelmaster.data.Repo
import com.lele.novelmaster.data.WriterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChaptersScreen(nav: NavController, pid: Long) {
    val chapters by Repo.dao.chaptersFlow(pid).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    // v6.9.37：补大纲忙闲从 AppTasks 全局取——离开本页任务照常完成，回来自动显示进度/结果
    val appTasks by com.lele.novelmaster.engine.AppTasks.state.collectAsState()
    val generating = "outline:$pid" in appTasks.running
    var genMsg by remember { mutableStateOf("") }
    // v6.9.17：自检状态徽标（✅通过/🔧修复过/⚠️疑似/无=未自检），数据来自本地「自检记录」
    val context = LocalContext.current
    var selfCheckMap by remember(pid) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    LaunchedEffect(pid, chapters.size) {
        selfCheckMap = withContext(Dispatchers.IO) { WriterEngine.readSelfCheckRecord(pid, context) }
    }

    AppScaffold(
        "章节列表",
        onBack = { nav.popBackStack() },
        actions = {
            TextButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    val maxIdx = Repo.dao.chapters(pid).maxOfOrNull { it.chapterIndex } ?: 0
                    Repo.dao.insertChapter(Chapter(projectId = pid, chapterIndex = maxIdx + 1))
                }
            }) { Icon(Icons.Default.Add, contentDescription = "添加章节") }
            TextButton(onClick = {
                genMsg = ""
                // v6.9.37：跑在 AppTasks 单例——中途离开本页，补大纲照常完成并落库
                // v6.9.38：key 与设定卡页「AI补全缺失大纲」统一为 outline:pid，两处入口互斥
                com.lele.novelmaster.engine.AppTasks.launch("outline:$pid") {
                    val err = WriterEngine.ensureOutlines(pid)
                    withContext(Dispatchers.Main) {
                        if (err != null) genMsg = err
                    }
                }
            }, enabled = !generating) { Text("生成缺失大纲") }
        }
    ) { pv ->
        Box(Modifier.padding(pv).fillMaxSize()) {
            Column {
                if (generating) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (genMsg.isNotEmpty()) {
                    Text(
                        genMsg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                LazyColumn(Modifier.fillMaxSize()) {
                    items(chapters, key = { it.id }) { c ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { nav.navigate("editor/${c.id}") }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "第${c.chapterIndex}章 ${c.title.ifBlank { "（未命名）" }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                Text(
                                    if (c.outline.isNotBlank()) c.outline else "大纲未生成",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                            val (statusText, statusColor) = when (c.status) {
                                1 -> "AI稿" to MaterialTheme.colorScheme.primary
                                2 -> "已编辑" to MaterialTheme.colorScheme.secondary
                                else -> "待写" to MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            // v6.9.17：自检状态徽标
                            val scBadge = when (selfCheckMap[c.chapterIndex]) {
                                "pass" -> "✅"
                                "fixed" -> "🔧"
                                "suspect" -> "⚠️"
                                else -> ""
                            }
                            if (scBadge.isNotEmpty()) {
                                Text(
                                    scBadge,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Text(
                                statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = statusColor
                            )
                            Text(
                                " ${c.wordCount}字",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) { Repo.dao.deleteChapter(c) }
                            }) {
                                Icon(
                                    Icons.Default.Delete, contentDescription = "删除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
            if (generating) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.padding(0.dp), strokeWidth = 2.dp)
                    Text("AI正在生成分章大纲…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
