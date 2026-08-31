package com.lele.novelmaster.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.lele.novelmaster.data.Project
import com.lele.novelmaster.data.Repo
import com.lele.novelmaster.data.WriterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ProjectScreen(nav: NavController, id: Long) {
    // v6.9.34：modelTick——绑定模型后重新查询项目（produceState 不会自动感知 copy 更新）
    var modelTick by remember { mutableStateOf(0) }
    val project by produceState<Project?>(null, id, modelTick) { value = Repo.dao.project(id) }
    val chapters by Repo.dao.chaptersFlow(id).collectAsState(initial = emptyList())
    val cards by Repo.dao.cardsFlow(id).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var confirmDel by remember { mutableStateOf(false) }
    var showModelPick by remember { mutableStateOf(false) }
    // v6.9.34：全部 AI 配置（用于「本书AI模型」绑定展示与选择）
    val apis by Repo.dao.apiConfigsFlow().collectAsState(initial = emptyList())

    val p = project ?: return
    val written = chapters.sumOf { it.wordCount }
    val done = chapters.count { it.content.isNotBlank() }
    val openForeshadow = cards.count { it.category == "伏笔钩子" && it.status != "已回收" }
    // v6.9.18：自检覆盖统计（来自本地「自检记录」）
    val context = LocalContext.current
    var scMap by remember(id) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    LaunchedEffect(id, done) {
        scMap = withContext(Dispatchers.IO) { WriterEngine.readSelfCheckRecord(id, context) }
    }
    val writtenChs = chapters.filter { it.content.isNotBlank() }
    val scChecked = writtenChs.count { scMap[it.chapterIndex] in setOf("pass", "fixed", "suspect") }
    val scSuspect = writtenChs.count { scMap[it.chapterIndex] == "suspect" }

    AppScaffold(p.title, onBack = { nav.popBackStack() }) { pv ->
        Column(
            Modifier
                .padding(pv)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox("章节进度", "$done/${chapters.size}", Modifier.weight(1f))
                StatBox("总字数", "$written", Modifier.weight(1f))
                StatBox("未回收伏笔", "$openForeshadow", Modifier.weight(1f))
            }
            Spacer(Modifier.height(14.dp))

            MenuButton("章节列表", "${chapters.size}章 · 点开阅读/编辑/AI续写") { nav.navigate("chapters/${p.id}") }
            Spacer(Modifier.height(8.dp))
            Text(
                "🔬 自检覆盖：$scChecked/$done 章" + (if (scSuspect > 0) "（⚠️ $scSuspect 章疑似矛盾待复核）" else "") + " · 说「全书自检」可补检修复",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            MenuButton("设定卡 · 灵感分析", "${cards.size}项设定：世界观/人物/大纲/伏笔…") { nav.navigate("cards/${p.id}") }
            Spacer(Modifier.height(8.dp))
            MenuButton("自动写作", "AI自动写完所有章节，逐章自动保存；多本书可并行") { nav.navigate("autowrite/${p.id}") }
            Spacer(Modifier.height(8.dp))
            // v6.9.34：本书独立 AI 模型绑定（并行写多本书时各书可用不同 API/平台/模型）
            val activeApi = apis.firstOrNull { it.isActive }
            val bound = apis.firstOrNull { it.id == p.apiConfigId }
            MenuButton(
                "本书AI模型",
                if (p.apiConfigId == 0L)
                    "跟随全局启用（${activeApi?.let { "${it.name} · ${it.model.ifBlank { "未选模型" }}" } ?: "尚未启用任何接口"}）"
                else
                    (bound?.let { "已绑定：${it.name} · ${it.model.ifBlank { "未选模型" }}" } ?: "绑定已失效，跟随全局启用")
            ) { showModelPick = true }
            Spacer(Modifier.height(8.dp))
            MenuButton("导出与发布", "导出TXT/Markdown，发布平台指南") { nav.navigate("export/${p.id}") }
            Spacer(Modifier.height(8.dp))
            MenuButton("AI模型设置", "对接各大AI（含免费模型），自动获取模型") { nav.navigate("ai") }

            Spacer(Modifier.height(20.dp))
            Text(
                "💡 保证300章不跑题的机制：每章开写前，AI只会收到「每章必发的设定 + 未回收伏笔 + 前5章剧情摘要 + 上一章结尾 + 相邻章节大纲」。设定请维护好优先级（重要设定设为必发），伏笔写完后会被自动标记回收。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { confirmDel = true }, modifier = Modifier.fillMaxWidth()) {
                Text("删除本书", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    // v6.9.34：本书 AI 模型选择弹窗
    if (showModelPick) {
        val p0 = project ?: return
        AlertDialog(
            onDismissRequest = { showModelPick = false },
            title = { Text("本书使用哪个AI模型") },
            text = {
                Column {
                    Text(
                        "为这本书单独指定 AI 接口：并行写多本书时，各书可分别用不同的 API/平台/模型，互不影响。不指定则跟随「AI模型设置」里已启用的接口。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    val active = apis.firstOrNull { it.isActive }
                    ModelOption(
                        name = "跟随全局启用" + (active?.let { "（${it.name} · ${it.model.ifBlank { "未选模型" }}）" } ?: "（尚未启用任何接口）"),
                        selected = p0.apiConfigId == 0L
                    ) {
                        scope.launch(Dispatchers.IO) { Repo.dao.updateProject(p0.copy(apiConfigId = 0L)) }
                        modelTick++
                        showModelPick = false
                    }
                    apis.forEach { a ->
                        ModelOption(
                            name = "${a.name} · ${a.model.ifBlank { "未选模型" }}" + (if (a.isActive) "（全局启用中）" else ""),
                            selected = p0.apiConfigId == a.id
                        ) {
                            scope.launch(Dispatchers.IO) { Repo.dao.updateProject(p0.copy(apiConfigId = a.id)) }
                            modelTick++
                            showModelPick = false
                        }
                    }
                    if (apis.isEmpty()) {
                        Text(
                            "还没有添加任何 AI 配置，先到「AI模型设置」添加。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModelPick = false }) { Text("关闭") } }
        )
    }

    if (confirmDel) {
        AlertDialog(
            onDismissRequest = { confirmDel = false },
            title = { Text("删除《${p.title}》？") },
            text = { Text("所有章节与设定将被永久删除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDel = false
                    scope.launch(Dispatchers.IO) {
                        // v6.9.40：删除前停掉该书全部关联任务（自动写作/聊天生成/页面长任务），防白烧 token
                        com.lele.novelmaster.data.AutoWriteManager.stopProjectTasks(p.id)
                        Repo.dao.deleteChaptersOf(p.id)
                        Repo.dao.deleteCardsOf(p.id)
                        Repo.dao.deleteProject(p)
                    }
                    nav.popBackStack()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDel = false }) { Text("取消") } }
        )
    }
}

/** v6.9.34：模型绑定选项行 */
@Composable
private fun ModelOption(name: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (selected) "🔵" else "⚪", fontSize = 15.sp)
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium)
    }
}
