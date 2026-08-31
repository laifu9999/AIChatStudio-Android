package com.lele.novelmaster.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lele.novelmaster.data.AutoWriteManager
import com.lele.novelmaster.data.Project
import com.lele.novelmaster.data.Repo

@Composable
fun AutoWriteScreen(nav: NavController, pid: Long) {
    val context = LocalContext.current
    val st by AutoWriteManager.state.collectAsState()
    val project by produceState<Project?>(null, pid) { value = Repo.dao.project(pid) }
    var from by remember { mutableStateOf("1") }
    var to by remember { mutableStateOf("") }

    LaunchedEffect(project) {
        val p = project ?: return@LaunchedEffect
        if (to.isBlank()) to = p.targetChapters.toString()
    }

    AppScaffold("自动写作", onBack = { nav.popBackStack() }) { pv ->
        Column(Modifier.padding(pv).padding(16.dp).fillMaxSize()) {
            Text(
                "AI将自动完成：生成缺失大纲 → 逐章写作 → 每章自动保存 → 自动写摘要（供后续章节记住剧情）→ 自动登记进度、标记已回收伏笔。v6.9.33 起支持后台写作：退出界面或息屏都不会中断，通知栏显示进度、可随时停止。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    from, { from = it },
                    label = { Text("从第章") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    to, { to = it },
                    label = { Text("写到第章") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        // v6.9.33：传 context 拉起前台服务——退出界面/息屏后写作不中断，通知栏可停止
                        AutoWriteManager.start(pid, from.toIntOrNull() ?: 1, to.toIntOrNull() ?: 1, context)
                    },
                    enabled = !st.running,
                    modifier = Modifier.weight(1f)
                ) { Text(if (st.running) "写作中…" else "开始自动写作") }
                OutlinedButton(
                    onClick = { AutoWriteManager.stop() },
                    enabled = st.running,
                    modifier = Modifier.weight(1f)
                ) { Text("停止") }
            }
            Spacer(Modifier.height(14.dp))

            if (st.running || st.done > 0) {
                Text(
                    "进度：${st.done}/${st.total}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                LinearProgressIndicator(
                    progress = { if (st.total > 0) st.done.toFloat() / st.total else 0f },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
                if (st.currentChapter.isNotBlank()) {
                    Text(
                        "当前：${st.currentChapter}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(10.dp))
            }

            Text("运行日志", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(st.logs.size) { i ->
                    Text(
                        st.logs[i],
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
