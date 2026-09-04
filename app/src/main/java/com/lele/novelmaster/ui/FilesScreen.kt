package com.lele.novelmaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.lele.novelmaster.tools.FileTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 项目文件管理 —— 每个会话一个独立文件夹（{filesDir}/novels/{pid}/files）。
 * 浏览/进入文件夹、点文件阅读编辑保存、删除文件/文件夹、新建文件/文件夹。
 */
@Composable
fun FilesScreen(nav: NavController, pid: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val base = FileTools.baseDir(ctx, pid)

    var cwd by remember { mutableStateOf(base) }          // 当前目录
    var refresh by remember { mutableStateOf(0) }         // 刷新键
    var items by remember { mutableStateOf<List<File>>(emptyList()) }
    var editing by remember { mutableStateOf<File?>(null) } // 打开的文件
    var editBody by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newIsFolder by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<File?>(null) }

    fun load() {
        items = cwd.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: emptyList()
    }
    LaunchedEffect(cwd, refresh) { load() }

    fun rel(f: File): String = f.absolutePath.removePrefix(base.absolutePath).trimStart('/').ifBlank { "." }

    // ---- 文件编辑视图 ----
    if (editing != null) {
        val f = editing!!
        AppScaffold(
            title = f.name,
            onBack = {
                if (dirty) {
                    // 有未保存修改，直接返回（用户可自行保存）；简单处理：提示保存按钮
                }
                editing = null
            }
        ) { pv ->
            Column(
                Modifier
                    .padding(pv)
                    .padding(12.dp)
                    .fillMaxSize()
            ) {
                Text(
                    "路径：${rel(f)}  ·  ${editBody.length} 字",
                    fontSize = 11.sp, color = Color(0xFF8A8698)
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = editBody,
                    onValueChange = { editBody = it; dirty = true },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(10.dp))
                Row {
                    Button(
                        enabled = dirty,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                f.writeText(editBody, Charsets.UTF_8)
                                kotlinx.coroutines.withContext(Dispatchers.Main) { dirty = false; refresh++ }
                            }
                        }
                    ) { Text(if (dirty) "保存修改" else "已保存") }
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = { deleteTarget = f }) {
                        Text("删除文件", color = Color(0xFFB91C1C))
                    }
                }
            }
        }
        return
    }

    // ---- 目录浏览视图 ----
    AppScaffold(
        title = "项目文件",
        onBack = {
            if (cwd != base && cwd.parentFile != null) cwd = cwd.parentFile!! else nav.popBackStack()
        }
    ) { pv ->
        Column(
            Modifier
                .padding(pv)
                .padding(horizontal = 12.dp)
                .fillMaxSize()
        ) {
            Text(
                "会话独立目录：novels/$pid/files/\n${rel(cwd)}",
                fontSize = 11.sp, color = Color(0xFF8A8698), lineHeight = 15.sp
            )
            Spacer(Modifier.height(8.dp))

            Row {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                    onClick = { newIsFolder = false; newName = ""; showNew = true }
                ) { Text("＋ 文件", fontSize = 13.sp) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { newIsFolder = true; newName = ""; showNew = true }) {
                    Text("＋ 文件夹", fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(8.dp))

            if (items.isEmpty()) {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF1EDFA))) {
                    Text(
                        "空目录。AI 在聊天里会自动把设定资料、时间线等分类存到这里；也可以点上方按钮手动新建。",
                        fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFF4A4560),
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(items.size) { i ->
                    val f = items[i]
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (f.isDirectory) cwd = f
                                else {
                                    editing = f
                                    editBody = runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("")
                                    dirty = false
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 4.dp)
                    ) {
                        Text(if (f.isDirectory) "📁" else "📄", fontSize = 18.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontSize = 15.sp, color = Color(0xFF1F1B2E))
                            if (f.isFile) Text("${f.length()} 字", fontSize = 11.sp, color = Color(0xFF8A8698))
                            else Text("${f.listFiles()?.size ?: 0} 项", fontSize = 11.sp, color = Color(0xFF8A8698))
                        }
                        TextButton(onClick = { deleteTarget = f }) { Text("删除", color = Color(0xFFB91C1C), fontSize = 12.sp) }
                    }
                }
                item { Spacer(Modifier.height(60.dp)) }
            }
        }
    }

    // ---- 新建对话框 ----
    if (showNew) {
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text(if (newIsFolder) "新建文件夹" else "新建文件") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, label = { Text("名称（可带子目录，如 设定/人物.md）") }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        val target = File(cwd, newName.trim())
                        scope.launch(Dispatchers.IO) {
                            runCatching {
                                if (newIsFolder) target.mkdirs() else {
                                    target.parentFile?.mkdirs()
                                    target.writeText("", Charsets.UTF_8)
                                }
                            }
                            kotlinx.coroutines.withContext(Dispatchers.Main) { showNew = false; refresh++ }
                        }
                    }
                ) { Text("创建", color = Color(0xFF6750A4)) }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("取消") } }
        )
    }

    // ---- 删除确认 ----
    if (deleteTarget != null) {
        val f = deleteTarget!!
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除确认") },
            text = { Text(if (f.isDirectory) "删除文件夹「${f.name}」及其全部内容？" else "删除文件「${f.name}」？") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        runCatching { if (f.isDirectory) f.deleteRecursively() else f.delete() }
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            if (editing == f) editing = null
                            deleteTarget = null; refresh++
                        }
                    }
                }) { Text("删除", color = Color(0xFFB91C1C)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}
