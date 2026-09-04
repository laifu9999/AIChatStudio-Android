package com.lele.novelmaster.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.lele.novelmaster.data.InjectPrefs
import com.lele.novelmaster.data.Prompts
import com.lele.novelmaster.data.Repo

/**
 * v6.9.75：系统提示词查看/修改页（功能面板 🧠「系统提示词」入口）。
 *
 *  - 查看：展示「写章/续写」当前生效的系统提示词（自定义优先，否则内置 13 条默认规则）；
 *  - 修改：编辑框内容保存后写章/续写立即使用（整体替换内置规则；设定卡/伏笔等注入块仍自动追加在后）；
 *  - 恢复默认：一键清空自定义，回到内置规则。
 *
 * 说明：本页只管「基础规则」部分——写作时 AI 收到的完整系统提示词 = 这份规则 + 动态注入块
 * （人物/世界观/伏笔/大纲窗口等按本书设定实时组装）。
 */
@Composable
fun SystemPromptScreen(nav: NavController) {
    val appCtx = Repo.app
    val saved = appCtx?.let { InjectPrefs.writerSystemOverride(it) } ?: ""
    val defaultRules = Prompts.writerRules()

    var text by remember { mutableStateOf(saved.ifBlank { defaultRules }) }
    var status by remember { mutableStateOf("") }
    // 进入页面时快照一次：是否正在使用自定义（后续编辑不影响标记）
    var customized by remember { mutableStateOf(saved.isNotBlank()) }

    AppScaffold("🧠 系统提示词", onBack = { nav.popBackStack() }) { pv ->
        Column(
            Modifier
                .padding(pv)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
        ) {
            Spacer(Modifier.height(8.dp))
            Text(
                "这是 AI 写章/续写时收到的系统提示词（基础规则部分）。修改后保存即生效；设定卡、伏笔、大纲窗口等注入内容在其后自动追加，不受影响。",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = Color(0xFF8A8698)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (customized) "当前生效：✍️ 自定义提示词" else "当前生效：内置默认规则",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (customized) Color(0xFFB45309) else Color(0xFF6750A4)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                textStyle = TextStyle(fontFamily = FontFamily.Default, fontSize = 13.sp, lineHeight = 19.sp, color = Color(0xFF1F1B2E)),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF6750A4),
                    unfocusedBorderColor = Color(0xFFD8D2E8),
                    cursorColor = Color(0xFF6750A4)
                ),
                shape = RoundedCornerShape(12.dp)
            )
            if (status.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(status, fontSize = 13.sp, color = Color(0xFF166534), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth()) {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                    onClick = {
                        if (appCtx == null) {
                            status = "⚠️ 系统未就绪，稍后再试"
                        } else {
                            val t = text.trim()
                            if (t.isBlank()) {
                                InjectPrefs.setWriterSystemOverride(appCtx, "")
                                customized = false
                                text = defaultRules
                                status = "✅ 已清空自定义，恢复为内置默认规则"
                            } else {
                                InjectPrefs.setWriterSystemOverride(appCtx, t)
                                customized = true
                                status = if (t == defaultRules.trim()) "✅ 已保存（与默认规则一致，等同默认）" else "✅ 已保存，写章/续写立即使用这份提示词"
                            }
                        }
                    }
                ) { Text("💾 保存", fontSize = 14.sp) }
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = {
                    if (appCtx != null) InjectPrefs.setWriterSystemOverride(appCtx, "")
                    customized = false
                    text = defaultRules
                    status = "↩️ 已恢复内置默认规则（如需自定义请重新编辑并保存）"
                }) { Text("↩️ 恢复默认", fontSize = 14.sp, color = Color(0xFF6750A4)) }
            }
            Spacer(Modifier.height(10.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF4F0FB)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    "💡 提示：清空内容后点「保存」= 恢复默认；写歪了随时点「恢复默认」。建议只做小幅调整（如加强某种文风要求），整段推翻内置的 13 条硬性规则可能让人物/伏笔一致性下降。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = Color(0xFF555168),
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
