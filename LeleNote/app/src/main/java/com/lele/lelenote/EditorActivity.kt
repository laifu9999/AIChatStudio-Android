package com.lele.lelenote

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * 半透明速记页：悬浮球点开时视频已暂停；这里记文字 / 截图，保存或关闭后自动续播。
 * 截图前把自己（含遮罩）整窗隐藏，抓完再恢复——截到的是底下的视频画面。
 */
class EditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(
            this, Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_HIDE)
        )
        val fromBall = intent.getBooleanExtra("fromBall", false)
        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        val existing = if (noteId > 0) {
            NoteStore.load(this).firstOrNull { it.id == noteId }
        } else null

        setContent {
            EditorUI(
                existingId = existing?.id ?: 0L,
                initialText = existing?.text ?: "",
                initialImages = existing?.images ?: emptyList(),
                initialCreatedAt = existing?.createdAt ?: 0L,
                fromBall = fromBall
            )
        }
    }

    override fun onDestroy() {
        // 不管怎么退出都把悬浮球请回来
        try {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
        } catch (_: Exception) { }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
    }
}

@Composable
private fun EditorUI(
    existingId: Long,
    initialText: String,
    initialImages: List<String>,
    initialCreatedAt: Long,
    fromBall: Boolean
) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val rootView = LocalView.current

    var text by remember { mutableStateOf(initialText) }
    val images = remember { mutableStateListOf<String>().apply { addAll(initialImages) } }
    var status by remember { mutableStateOf("") }

    fun close(resume: Boolean) {
        if (resume && fromBall) MediaCtl.play(ctx)
        activity?.finish()
    }

    // 截图完成广播
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val path = i?.getStringExtra("path") ?: return
                rootView.visibility = View.VISIBLE
                if (path.isNotBlank() && !images.contains(path)) {
                    images.add(path)
                    status = "已加入截图 ${images.size} 张"
                } else if (path.isBlank()) {
                    status = "截图失败，请重试"
                }
            }
        }
        ContextCompat.registerReceiver(
            ctx, receiver, IntentFilter(CaptureService.ACTION_DONE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { try { ctx.unregisterReceiver(receiver) } catch (_: Exception) { } }
    }

    // MediaProjection 授权
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == Activity.RESULT_OK && res.data != null) {
            status = "正在截图…"
            rootView.visibility = View.INVISIBLE
            ContextCompat.startForegroundService(
                ctx, Intent(ctx, CaptureService::class.java)
                    .putExtra("code", res.resultCode)
                    .putExtra("data", res.data)
            )
        } else {
            status = "未授权屏幕录制，无法截图"
        }
    }

    BackHandler { close(resume = true) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x33000000))
            .clickable { close(resume = true) },
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .navigationBarsPadding()
                .imePadding()
                .clickable { },
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFDFDFE)),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "📝 速记",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF20223A),
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { close(resume = true) }) { Text("取消") }
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 300.dp),
                    placeholder = { Text("记录这一刻…", color = Color(0xFF9A97AC)) },
                    maxLines = 12
                )

                if (images.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(images) { p ->
                            Box {
                                val bmp = remember(p) { NoteStore.decodeThumb(p, 256) }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "截图",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(76.dp)
                                            .background(
                                                Color(0xFFECEAF5),
                                                RoundedCornerShape(10.dp)
                                            )
                                    )
                                } else {
                                    Box(
                                        Modifier
                                            .size(76.dp)
                                            .background(
                                                Color(0xFFECEAF5),
                                                RoundedCornerShape(10.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) { Text("?", color = Color(0xFF9A97AC)) }
                                }
                                Text(
                                    "×",
                                    fontSize = 13.sp,
                                    color = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(3.dp)
                                        .background(
                                            Color(0xB3000000),
                                            RoundedCornerShape(9.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                        .clickable { images.remove(p) }
                                )
                            }
                        }
                    }
                }

                if (status.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(status, fontSize = 12.sp, color = Color(0xFF6B7A99))
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val mpm =
                            ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                                as? android.media.projection.MediaProjectionManager
                        if (mpm != null) {
                            projectionLauncher.launch(mpm.createScreenCaptureIntent())
                        } else {
                            status = "此系统不支持截图"
                        }
                    }) { Text("📷 截图") }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(onClick = { close(resume = true) }) {
                        Text("取消")
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = {
                            val now = System.currentTimeMillis()
                            val note = if (existingId > 0) {
                                Note(
                                    id = existingId,
                                    text = text,
                                    images = images.toList(),
                                    createdAt = if (initialCreatedAt > 0) initialCreatedAt else now,
                                    updatedAt = now
                                )
                            } else {
                                Note(
                                    id = now,
                                    text = text,
                                    images = images.toList(),
                                    createdAt = now,
                                    updatedAt = now
                                )
                            }
                            NoteStore.upsert(ctx, note)
                            close(resume = true)
                        },
                        enabled = text.isNotBlank() || images.isNotEmpty()
                    ) {
                        Text("保存并继续播放")
                    }
                }
            }
        }
    }
}
