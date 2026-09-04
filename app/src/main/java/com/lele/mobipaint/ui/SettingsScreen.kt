package com.lele.mobipaint.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lele.mobipaint.AiClient
import com.lele.mobipaint.AiConfig
import com.lele.mobipaint.AppScope
import com.lele.mobipaint.Config
import com.lele.mobipaint.Nav
import com.lele.mobipaint.PLATFORMS
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    BackHandler { Nav.screen = if (Nav.pid > 0) Screen.Chat else Screen.Shelf }

    val initial = remember { Config.load(ctx) }
    var platformIdx by remember { mutableStateOf(initial.platformIdx) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl) }
    var model by remember { mutableStateOf(initial.model) }
    var tempText by remember { mutableStateOf(initial.temperature.toString()) }
    var tokensText by remember { mutableStateOf(initial.maxTokens.toString()) }
    var status by remember { mutableStateOf("") }
    var platformMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var modelOptions by remember { mutableStateOf(listOf<String>()) }
    var showKey by remember { mutableStateOf(false) }

    fun buildCfg(): AiConfig? {
        val temp = tempText.toDoubleOrNull() ?: 0.75
        val tokens = tokensText.toIntOrNull() ?: 16384
        return AiConfig(
            platformIdx = platformIdx,
            apiKey = apiKey.trim(),
            baseUrl = baseUrl.trim(),
            model = model.trim(),
            temperature = temp.coerceIn(0.0, 2.0),
            maxTokens = tokens.coerceIn(256, 32768))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        TopBar("⚙️ 设置", onBack = {
            Nav.screen = if (Nav.pid > 0) Screen.Chat else Screen.Shelf
        })

        Text("AI 平台", fontSize = 13.sp, color = Color(0xFF6B7186),
            modifier = Modifier.padding(top = 8.dp))
        Box {
            Text(
                if (platformIdx < PLATFORMS.size) PLATFORMS[platformIdx].name
                else "自定义（手填接口地址）",
                fontSize = 15.sp, color = InkDark,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable { platformMenu = true }
                    .padding(14.dp)
            )
            DropdownMenu(expanded = platformMenu, onDismissRequest = { platformMenu = false }) {
                PLATFORMS.forEachIndexed { i, p ->
                    DropdownMenuItem(
                        text = { Text(p.name, fontSize = 13.sp) },
                        onClick = {
                            platformIdx = i
                            baseUrl = p.url
                            model = p.model
                            platformMenu = false
                        })
                }
                DropdownMenuItem(
                    text = { Text("自定义（手填接口地址）", fontSize = 13.sp) },
                    onClick = {
                        platformIdx = PLATFORMS.size
                        platformMenu = false
                    })
            }
        }
        if (platformIdx < PLATFORMS.size) {
            Text("① ${PLATFORMS[platformIdx].hint}\n② 粘贴 Key 到下方 → 保存 → 测试连接",
                fontSize = 12.sp, color = Color(0xFF8A8FA0),
                modifier = Modifier.padding(vertical = 4.dp))
        }

        OutlinedTextField(
            value = apiKey, onValueChange = { apiKey = it },
            label = { Text("API Key") },
            visualTransformation = if (showKey) androidx.compose.ui.text.input.VisualTransformation.None
                else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                Text(if (showKey) "隐藏" else "显示", fontSize = 12.sp,
                    color = Brand,
                    modifier = Modifier
                        .clickable { showKey = !showKey }
                        .padding(6.dp))
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))

        OutlinedTextField(
            value = baseUrl, onValueChange = { baseUrl = it },
            label = { Text("接口地址（填到 /v1 即可，自动补全）") },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp))

        Box {
            OutlinedTextField(
                value = model, onValueChange = { model = it },
                label = { Text("模型名") },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            if (modelOptions.isNotEmpty()) {
                Text("▼", color = Brand,
                    modifier = Modifier
                        .clickable { modelMenu = true }
                        .padding(top = 24.dp, start = 8.dp))
            }
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                modelOptions.forEach { m ->
                    DropdownMenuItem(text = { Text(m, fontSize = 13.sp) },
                        onClick = {
                            model = m
                            modelMenu = false
                        })
                }
            }
        }

        Row(modifier = Modifier.padding(top = 10.dp)) {
            OutlinedTextField(
                value = tempText, onValueChange = { tempText = it },
                label = { Text("温度(0~2)") },
                modifier = Modifier.weight(1f))
            Box(modifier = Modifier.padding(start = 8.dp))
            OutlinedTextField(
                value = tokensText, onValueChange = { tokensText = it },
                label = { Text("单次最大 token") },
                modifier = Modifier.weight(1f))
        }

        Row(modifier = Modifier.padding(top = 16.dp)) {
            Button(onClick = {
                val cfg = buildCfg() ?: return@Button
                if (cfg.apiKey.isEmpty()) {
                    status = "请先填 API Key。"
                    return@Button
                }
                Config.save(ctx, cfg)
                status = "已保存 ✓"
            }) { Text("💾 保存") }
            Box(modifier = Modifier.padding(start = 8.dp))
            Button(onClick = {
                val cfg = buildCfg() ?: return@Button
                if (cfg.apiKey.isEmpty()) {
                    status = "请先填 API Key。"
                    return@Button
                }
                Config.save(ctx, cfg)
                status = "测试中……"
                AppScope.scope.launch {
                    try {
                        val testCfg = cfg.copy(maxTokens = 64)
                        val reply = AiClient.chatRounds(testCfg, listOf(
                            Pair("user", "请只回复两个字：成功")), maxRounds = 2)
                        status = "✅ 连接成功：${reply.take(40)}"
                    } catch (e: Exception) {
                        status = "❌ ${e.message ?: e.toString()}"
                    }
                }
            }) { Text("🔌 测试连接") }
            Box(modifier = Modifier.padding(start = 8.dp))
            Button(onClick = {
                val cfg = buildCfg() ?: return@Button
                Config.save(ctx, cfg)
                status = "拉取模型列表中……"
                AppScope.scope.launch {
                    try {
                        modelOptions = AiClient.listModels(cfg)
                        status = "✅ 拉到 ${modelOptions.size} 个模型，点模型框右侧▼选择"
                    } catch (e: Exception) {
                        status = "❌ ${e.message ?: e.toString()}"
                    }
                }
            }) { Text("🔄 拉取模型") }
        }

        if (status.isNotEmpty()) {
            Text(status, fontSize = 13.sp, color = WarnOrange,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        }

        Text(
            "· 免费推荐：魔搭社区（每天约2000次）或智谱 glm-4-flash\n" +
                "· 写长文推荐：DeepSeek-V4 / GLM-5.2，质量更高\n" +
                "· 主线剧情记忆 + 设定卡自动沉淀，写到 999 章不跑偏",
            fontSize = 12.sp, color = Color(0xFF8A8FA0),
            modifier = Modifier.padding(top = 16.dp))
    }
}
