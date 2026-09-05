package com.lele.lelenote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 乐乐速记主界面：笔记列表 / 详情查看 / 编辑 / 分享 / 删除，
 * 悬浮球开关 + 悬浮窗权限引导，全部笔记导出 / 导入（JSON，截图内嵌）。
 */
class MainActivity : ComponentActivity() {

    private val refresh = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { AppUI() }
    }

    override fun onResume() {
        super.onResume()
        // 回到主界面时刷新列表（速记页可能刚加了笔记）+ 悬浮球状态
        refresh.intValue++
    }

    private fun ballPrefOn(): Boolean =
        getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("ballOn", false)

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun AppUI() {
        val ctx = LocalContext.current
        val act = this
        val scope = rememberCoroutineScope()
        var tick by remember { mutableIntStateOf(0) }
        val notes = remember(refresh.intValue, tick) { NoteStore.load(ctx) }
        var detailNote by remember { mutableStateOf<Note?>(null) }
        var deleteNote by remember { mutableStateOf<Note?>(null) }
        var ballOn by remember { mutableStateOf(false) }
        var canOverlay by remember { mutableStateOf(Settings.canDrawOverlays(ctx)) }
        var toast by remember { mutableStateOf("") }
        val df = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

        fun toastMsg(s: String) { toast = s }

        // 导出：创建文档（可存到任何网盘/文件管理器）
        val exportLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    val json = withContext(Dispatchers.IO) {
                        NoteStore.exportJson(ctx, NoteStore.load(ctx))
                    }
                    try {
                        contentResolver.openOutputStream(uri)?.use { os ->
                            os.write(json.toByteArray(Charsets.UTF_8))
                        }
                        toastMsg("已导出 ${NoteStore.load(ctx).size} 条笔记")
                    } catch (e: Exception) {
                        toastMsg("导出失败：${e.message ?: "未知错误"}")
                    }
                }
            }
        }

        // 导入：从网盘/文件管理器选备份 JSON
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    try {
                        val text = withContext(Dispatchers.IO) {
                            contentResolver.openInputStream(uri)?.use { ins ->
                                ins.bufferedReader().readText()
                            } ?: ""
                        }
                        val n = withContext(Dispatchers.IO) { NoteStore.importJson(ctx, text) }
                        tick++
                        toastMsg("已导入 $n 条笔记")
                    } catch (e: Exception) {
                        toastMsg("导入失败：${e.message ?: "文件格式不对"}")
                    }
                }
            }
        }

        fun updateBall(on: Boolean) {
            getSharedPreferences("prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("ballOn", on).apply()
            if (on) {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, OverlayService::class.java)
                )
            } else {
                ctx.stopService(Intent(ctx, OverlayService::class.java))
            }
            ballOn = on
        }

        // 回到前台时校准悬浮球状态（重复启动服务是幂等的，球不会叠加）
        androidx.compose.runtime.LaunchedEffect(refresh.intValue) {
            canOverlay = Settings.canDrawOverlays(ctx)
            ballOn = act.ballPrefOn() && canOverlay
            if (ballOn) {
                ContextCompat.startForegroundService(
                    ctx, Intent(ctx, OverlayService::class.java)
                )
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("📝 乐乐速记", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFFF7F6FB)
                    )
                )
            },
            containerColor = Color(0xFFF7F6FB)
        ) { pv ->
            Column(
                Modifier
                    .padding(pv)
                    .fillMaxSize()
            ) {
                // 悬浮窗权限引导
                if (!canOverlay) {
                    Card(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF6E0))
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "悬浮球需要「显示在其他应用上层」权限",
                                fontSize = 13.sp,
                                color = Color(0xFF6B5A1E),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                try {
                                    startActivity(
                                        Intent(
                                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:$packageName")
                                        )
                                    )
                                } catch (_: Exception) { }
                            }) { Text("去授权") }
                        }
                    }
                }

                // 功能行
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("悬浮球", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(4.dp))
                        Switch(
                            checked = ballOn,
                            enabled = canOverlay,
                            onCheckedChange = { updateBall(it) }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { exportLauncher.launch(suggestName()) }) { Text("📤 导出") }
                    TextButton(onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/plain"))
                    }) { Text("📥 导入") }
                }

                if (notes.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("还没有笔记", fontSize = 16.sp, color = Color(0xFF8A8698))
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "打开悬浮球 → 边看视频边点球速记",
                                fontSize = 13.sp, color = Color(0xFF9A97AC)
                            )
                            Spacer(Modifier.height(14.dp))
                            FilledTonalButton(onClick = {
                                ctx.startActivity(Intent(ctx, EditorActivity::class.java))
                            }) { Text("✍️ 写一条") }
                        }
                    }
                } else {
                    LazyColumn(
                        Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 14.dp, end = 14.dp, top = 4.dp, bottom = 20.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(notes, key = { it.id }) { n ->
                            Card(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { detailNote = n },
                                colors = CardDefaults.cardColors(
                                    containerColor = Color.White
                                )
                            ) {
                                Column(Modifier.padding(14.dp)) {
                                    Text(
                                        df.format(Date(if (n.updatedAt > 0) n.updatedAt else n.createdAt)),
                                        fontSize = 11.sp,
                                        color = Color(0xFF9A97AC)
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        n.text.ifBlank { "（无文字，${n.images.size} 张截图）" },
                                        fontSize = 14.sp,
                                        color = Color(0xFF20223A),
                                        maxLines = 3
                                    )
                                    if (n.images.isNotEmpty()) {
                                        Spacer(Modifier.height(6.dp))
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            items(n.images.take(6)) { p ->
                                                val bmp = remember(p) { NoteStore.decodeThumb(p, 128) }
                                                if (bmp != null) {
                                                    Image(
                                                        bitmap = bmp.asImageBitmap(),
                                                        contentDescription = null,
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier
                                                            .size(52.dp)
                                                            .background(
                                                                Color(0xFFECEAF5),
                                                                RoundedCornerShape(8.dp)
                                                            )
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
            }
        }

        // 详情弹窗
        detailNote?.let { n ->
            Dialog(onDismissRequest = { detailNote = null }) {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White)
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text(
                            df.format(Date(if (n.updatedAt > 0) n.updatedAt else n.createdAt)),
                            fontSize = 11.sp, color = Color(0xFF9A97AC)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            n.text.ifBlank { "（无文字）" },
                            fontSize = 15.sp, color = Color(0xFF20223A),
                            modifier = Modifier.height(160.dp)
                        )
                        if (n.images.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(n.images) { p ->
                                    val bmp = remember(p) { NoteStore.decodeThumb(p, 512) }
                                    if (bmp != null) {
                                        Image(
                                            bitmap = bmp.asImageBitmap(),
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(150.dp)
                                                .background(
                                                    Color(0xFFECEAF5),
                                                    RoundedCornerShape(10.dp)
                                                )
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row {
                            TextButton(onClick = {
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, n.text)
                                }
                                ctx.startActivity(
                                    Intent.createChooser(share, "分享笔记")
                                )
                            }) { Text("📤 分享") }
                            TextButton(onClick = {
                                ctx.startActivity(
                                    Intent(ctx, EditorActivity::class.java)
                                        .putExtra(EditorActivity.EXTRA_NOTE_ID, n.id)
                                )
                                detailNote = null
                            }) { Text("✏️ 编辑") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                deleteNote = n
                                detailNote = null
                            }) { Text("🗑 删除", color = Color(0xFFC62828)) }
                        }
                    }
                }
            }
        }

        // 删除确认
        deleteNote?.let { n ->
            AlertDialog(
                onDismissRequest = { deleteNote = null },
                title = { Text("删除这条笔记？") },
                text = { Text("文字和截图都会删除，且不可恢复。") },
                confirmButton = {
                    TextButton(onClick = {
                        NoteStore.delete(ctx, n.id)
                        n.images.forEach { try { File(it).delete() } catch (_: Exception) { } }
                        deleteNote = null
                        tick++
                    }) { Text("删除", color = Color(0xFFC62828)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteNote = null }) { Text("取消") }
                }
            )
        }

        // 轻提示
        if (toast.isNotBlank()) {
            AlertDialog(
                onDismissRequest = { toast = "" },
                title = { Text(toast) },
                confirmButton = {
                    TextButton(onClick = { toast = "" }) { Text("好") }
                }
            )
        }
    }

    private fun suggestName(): String {
        val df = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
        return "leenote_${df.format(Date())}.json"
    }
}
