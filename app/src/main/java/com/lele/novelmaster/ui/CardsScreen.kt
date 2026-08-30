package com.lele.novelmaster.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lele.novelmaster.data.CardCategories
import com.lele.novelmaster.data.Repo
import com.lele.novelmaster.data.SettingCard
import com.lele.novelmaster.data.WriterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CardsScreen(nav: NavController, pid: Long) {
    val cards by Repo.dao.cardsFlow(pid).collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("全部") }
    var showAdd by remember { mutableStateOf(false) }
    var editCard by remember { mutableStateOf<SettingCard?>(null) }
    var showInspire by remember { mutableStateOf(false) }

    // v6.9.22：「分章大纲」独立页签与「全书大纲」并列（分章大纲卡 category=全书大纲、name=分章大纲，由系统自动同步）
    val cats = listOf("全部", "全书大纲", "分章大纲") + CardCategories.all.filter { it != "全书大纲" }
    val list = when (tab) {
        "全部" -> cards
        "分章大纲" -> cards.filter { it.name == "分章大纲" || it.category == "分章大纲" }
        "全书大纲" -> cards.filter { it.category == "全书大纲" && it.name != "分章大纲" }
        else -> cards.filter { it.category == tab }
    }

    AppScaffold(
        "设定卡",
        onBack = { nav.popBackStack() },
        actions = {
            TextButton(onClick = { showInspire = true }) { Text("灵感分析") }
            TextButton(onClick = { showAdd = true }) { Text("＋新增") }
        }
    ) { pv ->
        Column(Modifier.padding(pv).fillMaxSize()) {
            ScrollableTabRow(selectedTabIndex = cats.indexOf(tab), edgePadding = 8.dp) {
                cats.forEach { cat ->
                    Tab(selected = tab == cat, onClick = { tab = cat }, text = { Text(cat) })
                }
            }
            if (list.isEmpty()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (tab == "分章大纲")
                            "还没有分章大纲卡。\n\n生成章节大纲后会自动同步到这里；也可以点下面按钮，从已有章节直接重建。"
                        else
                            "该分类还没有设定卡。\n\n推荐：点右上角「灵感分析」，把你的灵感告诉AI，它会自动生成整套设定。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (tab == "分章大纲") {
                        Button(
                            onClick = { scope.launch(Dispatchers.IO) { WriterEngine.syncChapterOutlineCard(pid, Repo.app) } },
                            modifier = Modifier.padding(top = 12.dp)
                        ) { Text("🔄 从章节重建大纲卡") }
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
                items(list, key = { it.id }) { card ->
                    ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(card.name, style = MaterialTheme.typography.titleSmall)
                                    if (card.category == "伏笔钩子" && card.status.isNotBlank()) {
                                        Text(
                                            "  ${card.status}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (card.status == "已回收") MaterialTheme.colorScheme.secondary
                                            else MaterialTheme.colorScheme.tertiary
                                        )
                                    }
                                    if (card.priority == 2) {
                                        Text(
                                            "  必发",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                                Text(
                                    card.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (card.name == "分章大纲") 12 else 3
                                )
                            }
                            IconButton(onClick = { editCard = card }) {
                                Icon(Icons.Default.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) { Repo.dao.deleteCard(card) }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) CardEditDialog(null, pid) { showAdd = false }
    editCard?.let { CardEditDialog(it, pid) { editCard = null } }
    if (showInspire) InspireDialog(pid) { showInspire = false }
}

@Composable
private fun CardEditDialog(initial: SettingCard?, pid: Long, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var cat by remember { mutableStateOf(initial?.category ?: "世界观") }
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var content by remember { mutableStateOf(initial?.content ?: "") }
    var pri by remember { mutableStateOf(initial?.priority ?: 1) }
    var status by remember {
        mutableStateOf(initial?.status ?: if ((initial?.category ?: "世界观") == "伏笔钩子") "埋设中" else "")
    }
    var catMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增设定卡" else "编辑设定卡") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { catMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("分类：$cat")
                    }
                    DropdownMenu(expanded = catMenu, onDismissRequest = { catMenu = false }) {
                        CardCategories.all.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c) },
                                onClick = {
                                    cat = c
                                    if (c == "伏笔钩子" && status.isBlank()) status = "埋设中"
                                    catMenu = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    name, { name = it },
                    label = { Text("名称（如：主角·林凡 / 大伏笔·身世之谜）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    content, { content = it },
                    label = { Text("内容（设定详情）") },
                    modifier = Modifier.fillMaxWidth().height(140.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "低频", 1 to "常规", 2 to "每章必发").forEach { (v, label) ->
                        if (v == pri) {
                            Button(
                                onClick = { pri = v },
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) { Text(label) }
                        } else {
                            OutlinedButton(
                                onClick = { pri = v },
                                contentPadding = PaddingValues(horizontal = 10.dp)
                            ) { Text(label) }
                        }
                    }
                }
                Text(
                    "优先级说明：必发=每章都会发给AI；常规=与本章大纲相关时才发（省token）；低频=基本不发。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (cat == "伏笔钩子") {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("埋设中", "已回收").forEach { s ->
                            if (s == status) {
                                Button(onClick = { status = s }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(s) }
                            } else {
                                OutlinedButton(onClick = { status = s }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text(s) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) return@TextButton
                val c0 = cat; val n0 = name.trim(); val ct0 = content.trim()
                val p0 = pri; val s0 = status
                scope.launch(Dispatchers.IO) {
                    if (initial == null) {
                        Repo.dao.insertCard(
                            SettingCard(projectId = pid, category = c0, name = n0, content = ct0, priority = p0, status = s0)
                        )
                    } else {
                        Repo.dao.updateCard(initial.copy(category = c0, name = n0, content = ct0, priority = p0, status = s0))
                    }
                }
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun InspireDialog(pid: Long, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("灵感分析 → 自动生成设定") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "把你的灵感、需求、脑洞写给AI。它会自动生成：世界观、人物设定、主线剧情、支线任务、伏笔钩子、核心冲突、设定圣经、全书大纲等设定卡并保存。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    text, { text = it },
                    label = { Text("我的灵感与需求…（比如：主角能听见死者遗言，想写悬疑+都市异能，100万字长篇）") },
                    modifier = Modifier.fillMaxWidth().height(160.dp)
                )
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("AI正在分析生成…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                msg?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && text.trim().length >= 5,
                onClick = {
                    busy = true
                    val inspiration = text.trim()
                    scope.launch(Dispatchers.IO) {
                        val err = WriterEngine.generateCardsFromInspire(pid, inspiration)
                        withContext(Dispatchers.Main) {
                            busy = false
                            if (err == null) onDismiss() else msg = err
                        }
                    }
                }
            ) { Text("AI生成设定卡") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("关闭") } }
    )
}
