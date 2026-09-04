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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lele.mobipaint.Db

@Composable
fun ChaptersScreen() {
    val pid = Nav.pid
    BackHandler { Nav.screen = Screen.Chat }

    val chapters = remember(pid) { Db.listChapters(pid) }
    var reader by remember { mutableStateOf<Db.Chapter?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar("📖 ${Nav.bookTitle.ifEmpty { "章节" }}（${chapters.size}章）",
            onBack = { Nav.screen = Screen.Chat })
        if (chapters.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp)
            ) {
                Text("还没有章节。回对话页点「✍️ 写第1章」或用「🤖 连写」。",
                    color = Color(0xFF6B7186), fontSize = 14.sp)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                items(chapters, key = { it.id }) { ch ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .clickable { reader = ch }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text("第${ch.no}章 ${ch.title}",
                            color = InkDark, fontSize = 15.sp,
                            modifier = Modifier.weight(1f), maxLines = 1)
                        Text("${ch.words}字", color = Color(0xFF8A8FA0), fontSize = 12.sp)
                    }
                }
            }
        }
    }

    reader?.let { ch ->
        AlertDialog(
            onDismissRequest = { reader = null },
            title = { Text("第${ch.no}章 ${ch.title}") },
            text = {
                Text(
                    ch.content,
                    fontSize = 15.sp,
                    lineHeight = 23.sp,
                    color = InkDark,
                    modifier = Modifier
                        .widthIn(max = 480.dp)
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { reader = null }) { Text("关闭") }
            })
    }
}
