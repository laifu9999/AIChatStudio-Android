package com.lele.novelmaster.ui

// v6.9.72：设定卡导入/导出的平台文件桥。
// Android 端用 SAF（保存到任意位置 / 从任意位置选文件）；PC 端有同名文件用 JFileChooser 实现。
// CardsScreen.kt 双端保持同源，只依赖本文件里的这两个函数。

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 导出：返回 (文件名, 内容) -> Unit；弹系统「保存到」面板让用户选位置 */
@Composable
fun rememberCardsExportSaver(): (String, String) -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val text = pending
        pending = null
        if (uri != null && text != null) scope.launch(Dispatchers.IO) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                withContext(Dispatchers.Main) { Toast.makeText(ctx, "已保存到所选位置", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(ctx, "保存失败：${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
    return { name, text -> pending = text; launcher.launch(name) }
}

/** 导入：传入拿到文件后的回调，返回 () -> Unit 触发系统选文件（txt/md/docx） */
@Composable
fun rememberCardsImportPicker(onLoaded: (String, ByteArray) -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            try {
                val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: "导入文件"
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                withContext(Dispatchers.Main) {
                    if (bytes.isNotEmpty()) onLoaded(name, bytes)
                    else Toast.makeText(ctx, "文件是空的", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { Toast.makeText(ctx, "读取文件失败：${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
    return {
        launcher.launch(arrayOf(
            "text/plain", "text/markdown",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword", "application/octet-stream"))
    }
}
