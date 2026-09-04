package com.lele.mobipaint.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lele.mobipaint.Db
import kotlinx.coroutines.delay

/** 设定类别（与 AI 自动沉淀分类一致）。 */
val SETTING_CATS = listOf("人物设定", "世界观", "大纲", "随记")

/**
 * 设定管理页（对应 PC 端「🗂 设定管理」）：
 * 人物/世界观/大纲/随记 四类，左列表右编辑，内容实时自动保存。
 */
@Composable
fun SettingManagerScreen() {
    val pid = Nav.pid
    BackHandler { Nav.screen = Screen.Chat }

    var catIdx by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var deleteId by remember { mutableStateOf<Long?>(null) }

    val rows = remember(catIdx, tick) { Db.listSettings(pid, SETTING_CATS[catIdx]) }
    val current = remember(rows, selectedId) {
        rows.firstOrNull { it.id == selectedId } ?: rows.firstOrNull()
    }

    // 编辑草稿（切换条目时重置）
    var edTitle by remember(current?.id) { mutableStateOf(current?.title ?: "") }
    var edContent by remember(current?.id) { mutableStateOf(current?.content ?: "") }

    // 实时自动保存（防抖 600ms，与 PC 端「边写边存」一致）
    LaunchedEffect(current?.id, edTitle, edContent) {
        val sid = current?.id ?: return@LaunchedEffect
        if (edTitle == current.title && edContent == current.content) return@LaunchedEffect
        delay(600)
        Db.updateSetting(sid, title = edTitle.trim().ifEmpty { "(未命名)" },
            content = edContent)
        tick++
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar("🗂 设定管理", onBack = { Nav.screen = Screen.Chat }) {
            TextButtonSmall("＋ 新增", {
                val id = Db.addSetting(pid, SETTING_CATS[catIdx], "新条目", "")
                selectedId = id
                tick++
            })
        }

        // 类别切换
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
            SETTING_CATS.forEachIndexed { i, cat ->
                val selected = i == catIdx
                Text(cat,
                    color = if (selected) Color.White else Brand,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Brand else Brand.copy(alpha = 0.08f))
                        .clickable { catIdx = i; selectedId = null }
                        .padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }

        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
            // 左：条目列表
            Column(modifier = Modifier.weight(0.38f).fillMaxSize()) {
                if (rows.isEmpty()) {
                    Text("这一类还没有条目，点右上角「＋ 新增」。\n\n设定越细，AI 写得越好：\n· 人物：外貌/性格/金手指/目标\n· 世界观：力量体系/势力地图\n· 大纲：第N章|标题|剧情要点",
                        color = Color(0xFF8A8FA0), fontSize = 12.sp,
                        modifier = Modifier.padding(top = 12.dp))
                } else {
                    LazyColumn {
                        items(rows, key = { it.id }) { r ->
                            val sel = r.id == (current?.id ?: -1L)
                            Text(
                                (r.title.ifEmpty { "(未命名)" }) +
                                    if (r.content.isNotEmpty()) "" else "  ␀",
                                color = if (sel) Color.White else InkDark,
                                fontSize = 13.sp,
                                maxLines = 1,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (sel) Brand else MaterialTheme.colorScheme.surface)
                                    .clickable { selectedId = r.id }
                                    .padding(horizontal = 10.dp, vertical = 9.dp))
                        }
                    }
                }
            }

            // 右：编辑器
            Column(modifier = Modifier.weight(0.62f).fillMaxSize().padding(start = 10.dp)) {
                if (current == null) {
                    Text("")
                } else {
                    OutlinedTextField(
                        value = edTitle, onValueChange = { edTitle = it },
                        label = { Text("条目标题") },
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = edContent, onValueChange = { edContent = it },
                        label = { Text("详细内容（自动保存）") },
                        textStyle = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp).weight(1f))
                    Row(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text("已保存 ✓", color = Color(0xFF8A8FA0), fontSize = 12.sp,
                            modifier = Modifier.weight(1f).padding(top = 10.dp))
                        Text("🗑 删除", color = Color(0xFFC62828), fontSize = 13.sp,
                            modifier = Modifier
                                .clickable { deleteId = current.id }
                                .padding(8.dp))
                    }
                }
            }
        }
    }

    deleteId?.let { sid ->
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text("删除设定条目？") },
            text = { Text("「${current?.title ?: ""}」将被删除，且无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    Db.deleteSetting(sid)
                    deleteId = null
                    selectedId = null
                    tick++
                }) { Text("删除", color = Color(0xFFC62828)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteId = null }) { Text("取消") }
            })
    }
}
