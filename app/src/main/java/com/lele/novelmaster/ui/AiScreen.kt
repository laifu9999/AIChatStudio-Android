package com.lele.novelmaster.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.lele.novelmaster.data.ApiConfig
import com.lele.novelmaster.data.AiClient
import com.lele.novelmaster.data.AiProviders
import com.lele.novelmaster.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun AiScreen(nav: NavController) {
    val configs by Repo.dao.apiConfigsFlow().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var editCfg by remember { mutableStateOf<ApiConfig?>(null) }

    AppScaffold(
        "AI模型设置",
        onBack = { nav.popBackStack() },
        actions = { TextButton(onClick = { showAdd = true }) { Text("＋添加") } }
    ) { pv ->
        LazyColumn(Modifier.padding(pv).fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
            item {
                Text(
                    "对接所有主流AI：填入 API 地址与密钥，点「获取模型列表」自动拉取全部模型，点「测试连接」验证。配置好后「设为启用」即可开始写作。\n\n免费推荐：智谱 glm-4-flash（免费）、硅基流动免费模型、Gemini 免费额度、OpenRouter 的 :free 模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            items(configs, key = { it.id }) { cfg ->
                ElevatedCard(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    cfg.name + if (cfg.isActive) "  ✓ 已启用" else "",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (cfg.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    "${cfg.model.ifBlank { "未选模型" }} · ${cfg.baseUrl}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { editCfg = cfg }) {
                                Icon(Icons.Default.Edit, contentDescription = "编辑", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                scope.launch(Dispatchers.IO) { Repo.dao.deleteApi(cfg) }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (!cfg.isActive) {
                            OutlinedButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    Repo.dao.clearActiveApi()
                                    Repo.dao.updateApi(cfg.copy(isActive = true))
                                }
                            }, modifier = Modifier.padding(top = 6.dp)) { Text("设为启用") }
                        }
                    }
                }
            }
        }
    }

    if (showAdd) ApiEditDialog(null) { showAdd = false }
    editCfg?.let { ApiEditDialog(it) { editCfg = null } }
}

@Composable
private fun ApiEditDialog(initial: ApiConfig?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var provider by remember { mutableStateOf(initial?.provider ?: "openai") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var presetMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    fun tempCfg() = ApiConfig(
        name = name.ifBlank { "测试" }, provider = provider,
        baseUrl = baseUrl.trim(), apiKey = apiKey.trim(), model = model.trim()
    )

    AlertDialog(
        onDismissRequest = { if (busy == null) onDismiss() },
        title = { Text(if (initial == null) "添加AI配置" else "编辑AI配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { presetMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("选择服务商预设（自动填地址）")
                    }
                    DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                        AiProviders.presets.forEach { p ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(p.name, style = MaterialTheme.typography.bodyMedium)
                                        if (p.freeNote.isNotBlank()) {
                                            Text(
                                                p.freeNote,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.tertiary
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    provider = p.provider
                                    baseUrl = p.baseUrl
                                    if (name.isBlank()) name = p.name.substringBefore("（")
                                    if (model.isBlank() && p.suggestedModel.isNotBlank()) model = p.suggestedModel
                                    presetMenu = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    name, { name = it }, label = { Text("配置名称") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    baseUrl, { baseUrl = it }, label = { Text("API地址 Base URL") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    apiKey, { apiKey = it }, label = { Text("API密钥 Key") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { if (models.isNotEmpty()) modelMenu = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = busy == null
                        ) { Text(model.ifBlank { "选择模型" }, maxLines = 1) }
                        DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                            models.take(60).forEach { m ->
                                DropdownMenuItem(text = { Text(m, style = MaterialTheme.typography.bodySmall) },
                                    onClick = { model = m; modelMenu = false })
                            }
                        }
                    }
                    OutlinedButton(
                        enabled = busy == null && baseUrl.isNotBlank() && apiKey.isNotBlank(),
                        onClick = {
                            busy = "获取模型列表…"
                            result = null
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val list = AiClient.listModels(tempCfg())
                                    launch(Dispatchers.Main) {
                                        models = list
                                        busy = null
                                        result = if (list.isEmpty()) "未获取到模型，请检查地址/密钥" else "已获取 ${list.size} 个模型"
                                    }
                                } catch (e: Exception) {
                                    launch(Dispatchers.Main) { busy = null; result = "获取失败：${e.message?.take(200)}" }
                                }
                            }
                        }
                    ) { Text("获取模型") }
                }
                Button(
                    enabled = busy == null,
                    onClick = {
                        busy = "测试连接中…"
                        result = null
                        scope.launch(Dispatchers.IO) {
                            try {
                                val reply = AiClient.testConnection(tempCfg())
                                launch(Dispatchers.Main) { busy = null; result = "✅ 连接成功，AI回复：$reply" }
                            } catch (e: Exception) {
                                launch(Dispatchers.Main) { busy = null; result = "❌ ${e.message?.take(250)}" }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("测试连接") }
                if (busy != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(busy!!, style = MaterialTheme.typography.bodySmall)
                    }
                }
                result?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (it.startsWith("✅")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cfg0 = ApiConfig(
                    id = initial?.id ?: 0, name = name.ifBlank { "未命名" },
                    provider = provider, baseUrl = baseUrl.trim(),
                    apiKey = apiKey.trim(), model = model.trim(),
                    isActive = initial?.isActive ?: false
                )
                scope.launch(Dispatchers.IO) {
                    if (initial == null) Repo.dao.insertApi(cfg0) else Repo.dao.updateApi(cfg0)
                }
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = { if (busy == null) onDismiss() }) { Text("取消") } }
    )
}
