package com.lele.novelmaster.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.lele.novelmaster.data.Project
import com.lele.novelmaster.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ExportScreen(nav: NavController, pid: Long) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val project by produceState<Project?>(null, pid) { value = Repo.dao.project(pid) }
    var pendingText by remember { mutableStateOf<String?>(null) }
    var showGuide by remember { mutableStateOf(false) }

    val p = project ?: return

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = pendingText
        if (uri != null && text != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    withContext(Dispatchers.Main) { Toast.makeText(ctx, "已保存到所选位置", Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { Toast.makeText(ctx, "保存失败：${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    suspend fun buildBook(markdown: Boolean): Pair<String, String> {
        val chapters = Repo.dao.chapters(pid).filter { it.content.isNotBlank() }
        val sb = StringBuilder()
        if (markdown) sb.appendLine("# ${p.title}") else sb.appendLine("《${p.title}》")
        if (p.description.isNotBlank()) sb.appendLine(p.description)
        sb.appendLine()
        chapters.forEach { c ->
            if (markdown) {
                sb.appendLine("## 第${c.chapterIndex}章 ${c.title}")
                sb.appendLine()
            } else {
                sb.appendLine("第${c.chapterIndex}章 ${c.title}")
            }
            sb.appendLine(c.content)
            sb.appendLine()
        }
        return Pair(p.title, sb.toString())
    }

    fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "book" }

    AppScaffold("导出与发布", onBack = { nav.popBackStack() }) { pv ->
        Column(
            Modifier
                .padding(pv)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Button(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val (t, txt) = buildBook(false)
                        pendingText = txt
                        withContext(Dispatchers.Main) { exportLauncher.launch("${safeName(t)}.txt") }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("导出全书 TXT（到手机任意位置）") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val (t, txt) = buildBook(true)
                        pendingText = txt
                        withContext(Dispatchers.Main) { exportLauncher.launch("${safeName(t)}.md") }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("导出全书 Markdown") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val (_, txt) = buildBook(false)
                        withContext(Dispatchers.Main) {
                            clipboard.setText(AnnotatedString(txt))
                            Toast.makeText(ctx, "全书已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("复制全书到剪贴板") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val (_, txt) = buildBook(false)
                        withContext(Dispatchers.Main) {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TITLE, "《${p.title}》")
                                putExtra(Intent.EXTRA_TEXT, txt.take(90000))
                            }
                            ctx.startActivity(Intent.createChooser(send, "分享/发布《${p.title}》"))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("分享到其他APP（作家助手/备忘录等）") }
            Spacer(Modifier.height(8.dp))
            Button(onClick = { showGuide = true }, modifier = Modifier.fillMaxWidth()) {
                Text("发布平台指南")
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "说明：所有平台（起点/番茄/七猫等）都没有面向个人的自动发布API，因此发布采用「导出TXT + 平台后台粘贴」或「系统分享」方式，这也是各写作APP的通用做法。文件保存使用系统文件选择器，无需申请存储权限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text("发布平台指南") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        """
                        支持发布的主流平台：
                        · 起点中文网 / QQ阅读（作家助手APP）
                        · 番茄小说（番茄作家助手）
                        · 七猫免费小说（作家助手）
                        · 晋江文学城（作者后台）
                        · 知乎盐选（创作者中心）
                        · 飞卢小说网 / 纵横中文网

                        建议流程：
                        1. 在目标平台注册作者并创建作品；
                        2. 用本APP「导出全书TXT」或「复制到剪贴板」；
                        3. 在平台作家后台/作家助手APP里逐章粘贴发布；
                        4. 也可以用「分享到其他APP」直接把文本分享到备忘录、邮箱或平台APP。

                        小技巧：发布前建议每章通读一遍并用「编辑」功能润色，平台对AI生成内容的判定越来越严格，加入个人风格会更安全。
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showGuide = false }) { Text("知道了") } }
        )
    }
}
