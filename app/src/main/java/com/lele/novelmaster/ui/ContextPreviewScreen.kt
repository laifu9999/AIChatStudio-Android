package com.lele.novelmaster.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.lele.novelmaster.data.Project
import com.lele.novelmaster.data.Repo
import com.lele.novelmaster.tools.ToolResult
import com.lele.novelmaster.tools.Tools
import kotlinx.coroutines.flow.firstOrNull

/**
 * 上下文注入预览 —— 实时查看「写下一章」时 AI 将收到什么：
 * 必发设定卡 + 未回收伏笔 + 前5章摘要 + 上一章结尾600字 + 相邻大纲 + token 估算。
 * 与 Prompts.buildChapterMessages 完全同源，所见即所发。
 */
@Composable
fun ContextPreviewScreen(nav: NavController, pid: Long) {
    val project by produceState<Project?>(null, pid) { value = Repo.dao.project(pid) }
    var result by remember { mutableStateOf<ToolResult?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(pid, project?.id) {
        // 当前会话优先；没有会话时自动取第一本（不让用户再选）
        val target = if (pid > 0L) pid else Repo.dao.projectsFlow().firstOrNull()?.firstOrNull()?.id ?: 0L
        if (target > 0L) {
            loading = true
            result = Tools.contextPreview(target)
            loading = false
        } else {
            loading = false
            result = com.lele.novelmaster.tools.ToolResult(false, "还没有任何小说。回聊天页发一个灵感即可自动建书。")
        }
    }

    AppScaffold("上下文注入预览", onBack = { nav.popBackStack() }) { pv ->
        Column(
            Modifier
                .padding(pv)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text(
                "写每一章前，App 只把这些内容发给 AI —— 用摘要代替全文，token 消耗恒定，300/600 章也不会膨胀。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            when {
                pid <= 0L -> Text("请先选择一本小说（回聊天页切换会话）。")
                loading -> Text("正在计算注入内容…", color = MaterialTheme.colorScheme.primary)
                result != null -> {
                    val r = result!!
                    if (!r.ok) {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFDE8E8))) {
                            Text(
                                r.summary,
                                color = Color(0xFFB91C1C),
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    } else {
                        val lines = (r.summary + "\n" + r.detail).split("\n")
                        var currentTitle = ""
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(lines.size) { i ->
                                val line = lines[i]
                                val isHeader = line.startsWith("【") || line.startsWith("▶")
                                if (isHeader) {
                                    currentTitle = line
                                    Text(
                                        line,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                                    )
                                } else if (line.isNotBlank()) {
                                    Text(
                                        line,
                                        fontSize = 13.sp,
                                        lineHeight = 19.sp,
                                        color = Color(0xFF333333),
                                        modifier = Modifier.padding(vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
