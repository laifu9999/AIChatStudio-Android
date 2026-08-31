package com.lele.novelmaster.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lele.novelmaster.MainActivity
import com.lele.novelmaster.data.AutoWriteManager
import com.lele.novelmaster.data.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * v6.9.33 前台保活服务：自动写作期间常驻通知（进度+停止按钮），
 * 关闭界面/息屏后系统不杀进程，生成不中断；任务完成或停止时发系统通知并自动退出。
 * 由 AutoWriteManager.start() 拉起，观察其 state 流驱动通知更新。
 */
class GenerationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        AutoWriteManager.state
            .onEach { st ->
                val nm = getSystemService(NotificationManager::class.java)
                if (st.running) {
                    val title = "✍️ 正在写作《${bookTitle(st.projectId)}》"
                    val text = if (st.total > 0)
                        "${st.currentChapter}（${st.done}/${st.total}）"
                    else st.currentChapter.ifBlank { "准备中…" }
                    nm.notify(NOTI_ID, buildNoti(CHANNEL_PROGRESS, title, text, ongoing = true))
                } else {
                    // 任务结束（完成/失败/手动停止）：发完成通知并退出
                    nm.notify(
                        NOTI_ID + 1,
                        buildNoti(
                            CHANNEL_DONE, "📖《${bookTitle(st.projectId)}》写作任务已结束",
                            "点开查看进度与成果（通知可在系统设置关闭）", ongoing = false
                        )
                    )
                    nm.cancel(NOTI_ID)
                    stopSelf()
                }
            }
            .launchIn(scope)
    }

    private fun bookTitle(pid: Long): String =
        try { Repo.dao.project(pid)?.title ?: "小说" } catch (_: Exception) { "小说" }

    private fun buildNoti(channel: String, title: String, text: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
        if (ongoing) {
            val stop = PendingIntent.getBroadcast(
                this, 1, Intent(this, GenerationStopReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            b.setOngoing(true).addAction(0, "⏹ 停止写作", stop)
        } else {
            b.setAutoCancel(true)
        }
        return b.build()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "写作进度", NotificationManager.IMPORTANCE_LOW).apply {
                description = "自动写作期间的常驻进度通知（不打扰）"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "写作完成提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "每本书写作任务完成/停止时的系统通知"
            }
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_PROGRESS = "generation_progress"
        private const val CHANNEL_DONE = "generation_done"
        private const val NOTI_ID = 1001

        /** 自动写作开始时调用：拉起前台服务（服务观察状态，无任务时自行退出） */
        fun start(context: Context?) {
            if (context == null) return
            try {
                val i = Intent(context, GenerationService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
                else context.startService(i)
            } catch (_: Exception) { }
        }
    }
}

/** 通知上的「⏹ 停止写作」按钮：广播置停（12.x 后服务不能直接接收 PendingIntent 动作，用广播中转） */
class GenerationStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AutoWriteManager.stop()
    }
}
