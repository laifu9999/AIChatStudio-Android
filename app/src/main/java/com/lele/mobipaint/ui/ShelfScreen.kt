package com.lele.mobipaint.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lele.mobipaint.Db

@Composable
fun ShelfScreen() {
    var tick by remember { mutableStateOf(0) }
    var showCreate by remember { mutableStateOf(false) }
    var deletePid by remember { mutableStateOf<Long?>(null) }

    val projects = remember(tick) { Db.listProjects() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar("📖 墨笔写小说") {
            TextButtonSmall("＋ 新建书", { showCreate = true })
        }
        if (projects.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("还没有书。点右上角「＋ 新建书」开始你的第一部小说！",
                    color = Color(0xFF6B7186), fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                items(projects, key = { it.id }) { p ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable {
                                Nav.pid = p.id
                                Nav.bookTitle = p.title
                                Nav.screen = Screen.Chat
                            }
                            .padding(14.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.title,
                                color = com.lele.mobipaint.ui.InkDark,
                                fontSize = 16.sp,
                                modifier = Modifier.weight(1f),
                                maxLines = 1)
                            Text("🗑", color = Color(0xFFB0B4C0), fontSize = 14.sp,
                                modifier = Modifier
                                    .clickable { deletePid = p.id }
                                    .padding(6.dp))
                        }
                        val words = Db.totalWords(p.id)
                        val genre = p.genre.ifEmpty { "题材未定" }
                        Text("$genre · 已写 $words 字",
                            color = Color(0xFF8A8FA0), fontSize = 12.sp)
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateBookDialog(
            onDismiss = { showCreate = false },
            onCreate = { title, genre, brief ->
                val pid = Db.createProject(title, genre, brief)
                showCreate = false
                tick++
                if (pid > 0) {
                    Nav.pid = pid
                    Nav.bookTitle = title
                    Nav.screen = Screen.Chat
                }
            })
    }

    deletePid?.let { pid ->
        AlertDialog(
            onDismissRequest = { deletePid = null },
            title = { Text("删除这本书？") },
            text = { Text("书档案、章节、设定与对话将全部删除，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    Db.deleteProject(pid)
                    deletePid = null
                    tick++
                }) { Text("删除", color = Color(0xFFC62828)) }
            },
            dismissButton = {
                TextButton(onClick = { deletePid = null }) { Text("取消") }
            })
    }
}

@Composable
fun CreateBookDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("") }
    var brief by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建一本书") },
        text = {
            Column {
                Text("题材只作登记参考，实际类型以聊天内容为准。",
                    color = Color(0xFF8A8FA0), fontSize = 12.sp)
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("书名（必填）") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = genre, onValueChange = { genre = it },
                    label = { Text("题材（可留空，如：仙侠）") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = brief, onValueChange = { brief = it },
                    label = { Text("简介 / 灵感（可留空）") },
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = {
                if (title.isNotBlank()) onCreate(title.trim(), genre.trim(), brief.trim())
            }) { Text("创建并开写") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        })
}
