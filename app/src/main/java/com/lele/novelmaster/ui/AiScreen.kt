package com.lele.novelmaster.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lele.novelmaster.data.ApiConfig
import com.lele.novelmaster.data.AiClient
import com.lele.novelmaster.data.AiProviders
import com.lele.novelmaster.data.InjectPrefs
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
                                    "${cfg.model.ifBlank { "未选模型" }}" +
                                        when (cfg.thinkMode) {
                                            "none" -> " · 无思考"
                                            "low" -> " · 低思考"
                                            "high" -> " · 高思考"
                                            else -> ""
                                        } +
                                        " · ${cfg.baseUrl}",
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
            // v6.9.41：任务专用模型——大纲/体检/打磨可各自绑定不同 AI，未绑定走默认
            item {
                Spacer(Modifier.height(10.dp))
                Text(
                    "🎯 任务专用模型（可选，全局默认）\n给不同任务分配不同 AI：省钱的设定卡/体检用便宜模型，打磨用高质量模型。未指定的任务走「本书绑定模型」或全局启用模型。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                ElevatedCard(Modifier.fillMaxWidth()) {
                    // v6.9.46：六类任务全部可指定模型；选择先进草稿，点「💾 保存」才落库（用户要求显式保存按钮）
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        val draft = remember { androidx.compose.runtime.mutableStateMapOf<String, Long>() }
                        com.lele.novelmaster.data.TaskModels.ALL.forEachIndexed { i, task ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.5.dp)
                            TaskModelRow(com.lele.novelmaster.data.TaskModels.label(task), task, configs,
                                currentId = draft[task] ?: com.lele.novelmaster.data.TaskModels.boundId(ctx, task),
                                onSelect = { id -> draft[task] = id })
                        }
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.Button(
                            onClick = {
                                com.lele.novelmaster.data.TaskModels.ALL.forEach { t ->
                                    draft[t]?.let { com.lele.novelmaster.data.TaskModels.set(ctx, t, it) }
                                }
                                draft.clear()
                                android.widget.Toast.makeText(ctx, "✅ 任务模型已保存", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("💾 保存") }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            // v6.9.47：后台功能开关——每章自动体检（点击开启/关闭，即时生效）
            item {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                var autoChk by remember { mutableStateOf(InjectPrefs.autoCheck(ctx)) }
                Spacer(Modifier.height(10.dp))
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(14.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("🩺 每章自动体检", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "写完/重写/润色每章后自动对照设定查矛盾并修正。关闭后写作更快更省；手动「单章自检/全书体检」不受影响。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = autoChk, onCheckedChange = { on ->
                            autoChk = on
                            InjectPrefs.setAutoCheck(ctx, on)
                        })
                    }
                }
            }
            // v6.9.54：后台功能开关——全书自检修时同步润色去AI味（点击开启/关闭，即时生效）
            item {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                var polChk by remember { mutableStateOf(InjectPrefs.polishWithCheck(ctx)) }
                Spacer(Modifier.height(10.dp))
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(14.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("✨ 自检修同步润色去AI味", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "「全书自检修」每章查完矛盾后顺手按发布级文风整章润色（剧情/对话不变，只改文字表达），跑完即可导出发布。关闭更省 token；每次润色前自动备份。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = polChk, onCheckedChange = { on ->
                            polChk = on
                            InjectPrefs.setPolishWithCheck(ctx, on)
                        })
                    }
                }
            }
        }
    }

    if (showAdd) ApiEditDialog(null) { showAdd = false }
    editCfg?.let { ApiEditDialog(it) { editCfg = null } }
}

/** v6.9.41：单行任务模型绑定——点击弹出下拉；v6.9.46：只改草稿由「💾 保存」统一落库 */
@Composable
private fun TaskModelRow(label: String, task: String, configs: List<ApiConfig>, currentId: Long, onSelect: (Long) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val boundName = configs.firstOrNull { it.id == currentId }?.name
    Row(
        modifier = Modifier.fillMaxWidth().clickable { menu = true }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            boundName ?: "跟随默认 ▾",
            style = MaterialTheme.typography.bodySmall,
            color = if (boundName != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("跟随默认（本书绑定/全局启用）") },
                onClick = {
                    menu = false
                    onSelect(0L)
                }
            )
            configs.forEach { c ->
                DropdownMenuItem(
                    text = { Text("${c.name} · ${c.model.ifBlank { "未选模型" }}") },
                    onClick = {
                        menu = false
                        onSelect(c.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun ApiEditDialog(initial: ApiConfig?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var provider by remember { mutableStateOf(initial?.provider ?: "openai") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    // v6.9.47：思考强度——""=模型默认 "none"=无思考 "low"=低强度 "high"=高强度
    var thinkMode by remember { mutableStateOf(initial?.thinkMode ?: "") }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var presetMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    fun tempCfg() = ApiConfig(
        name = name.ifBlank { "测试" }, provider = provider,
        baseUrl = baseUrl.trim(), apiKey = apiKey.trim(), model = model.trim(),
        thinkMode = thinkMode
    )

    // v6.9.49：思考档位选中后自动连接 AI 验证是否生效
    var thinkTesting by remember { mutableStateOf(false) }
    var thinkResult by remember { mutableStateOf<String?>(null) }
    fun runThinkTest() {
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            thinkResult = "⚠️ 请先填好 API地址 / 密钥 / 模型，再验证思考模式"
            return
        }
        thinkTesting = true
        thinkResult = null
        scope.launch(Dispatchers.IO) {
            try {
                val msg = AiClient.testThinking(tempCfg())
                launch(Dispatchers.Main) { thinkTesting = false; thinkResult = msg }
            } catch (e: Exception) {
                launch(Dispatchers.Main) { thinkTesting = false; thinkResult = "❌ ${e.message?.take(200)}" }
            }
        }
    }

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
                // v6.9.47：思考强度单选，适配所有模型；v6.9.49：短字档位 + 选中即自动验证
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "🧠 思考强度：各家模型自动适配，不认识思考参数的自动按默认运行。选中即自动验证是否生效。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        val modes = listOf(
                            "" to "默认", "none" to "无", "low" to "低", "high" to "高"
                        )
                        modes.forEach { (v, label) ->
                            val sel = thinkMode == v
                            if (sel) {
                                Button(
                                    onClick = {
                                        thinkMode = v
                                        thinkResult = null
                                        if (v.isNotBlank()) runThinkTest()
                                        else thinkResult = "↩️ 已切回「默认」：不发送思考参数，跟随模型自带行为。点右下「保存」生效。"
                                    },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)
                                ) { Text(label, style = MaterialTheme.typography.labelLarge) }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        thinkMode = v
                                        thinkResult = null
                                        if (v.isNotBlank()) runThinkTest()
                                        else thinkResult = "↩️ 已切回「默认」：不发送思考参数，跟随模型自带行为。点右下「保存」生效。"
                                    },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)
                                ) {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    if (thinkTesting) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text("正在连接 AI 验证思考模式…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    thinkResult?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                it.startsWith("✅") -> MaterialTheme.colorScheme.secondary
                                it.startsWith("↩️") -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.error
                            }
                        )
                    }
                    if (thinkMode.isNotBlank() && !thinkTesting) {
                        TextButton(
                            enabled = busy == null,
                            onClick = { runThinkTest() },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                        ) { Text("🔄 重新验证", style = MaterialTheme.typography.labelMedium) }
                    }
                }
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
                    isActive = initial?.isActive ?: false,
                    thinkMode = thinkMode
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
