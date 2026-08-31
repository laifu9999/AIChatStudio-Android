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
 * v6.9.34：多书并行——同时有多本书在写时，通知合并展示各书进度；每本书任务结束时各自发完成通知。
 * 由 AutoWriteManager.start() 拉起，观察其 state 流驱动通知更新。
 */
class GenerationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 各书书名缓存（dao.project 是 suspend，只能切线程取） */
    private val titles = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** 上一帧正在运行的 pid 集合——与当前帧做差集，检测"哪本书刚结束" */
    private var prevRunning: Set<Long> = emptySet()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        // v6.9.34 修复：startForegroundService 启动后必须立即 startForeground，
        // 否则 Android 8+ 会在 5 秒后抛 ForegroundServiceDidNotStartInTime 杀进程（v6.9.33 隐患）
        startForeground(NOTI_ID, buildNoti(CHANNEL_PROGRESS, "✍️ 写作服务", "准备中…", ongoing = true))
        AutoWriteManager.state
            .onEach { st ->
                val running = st.tasks.values.filter { it.running }
                val runningPids = running.map { it.projectId }.toSet()
                // 书名按项目懒加载（dao.project 是 suspend，切线程取后缓存）
                for (ts in running) {
                    val pid = ts.projectId
                    if (!titles.containsKey(pid)) {
                        titles[pid] = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            try { Repo.dao.project(pid)?.title ?: "小说" } catch (_: Exception) { "小说" }
                        }
                    }
                }
                val nm = getSystemService(NotificationManager::class.java)
                if (running.isNotEmpty()) {
                    val (title, text) = if (running.size == 1) {
                        val ts = running[0]
                        "✍️ 正在写作《${titles[ts.projectId] ?: "小说"}》" to
                            (if (ts.total > 0) "${ts.currentChapter}（${ts.done}/${ts.total}）"
                            else ts.currentChapter.ifBlank { "准备中…" })
                    } else {
                        "✍️ ${running.size} 本书并行写作中" to
                            running.joinToString("；") { "《${titles[it.projectId] ?: "小说"}》${it.done}/${it.total}" }
                    }
                    nm.notify(NOTI_ID, buildNoti(CHANNEL_PROGRESS, title, text, ongoing = true))
                } else {
                    nm.cancel(NOTI_ID)
                }
                // 任务结束检测：上一帧还在跑、这一帧不在了的 → 每本书单独发完成通知（不同通知 id 互不覆盖）
                for (pid in prevRunning - runningPids) {
                    nm.notify(
                        NOTI_ID + 1 + (pid % 500).toInt(),
                        buildNoti(
                            CHANNEL_DONE, "📖《${titles[pid] ?: "小说"}》写作任务已结束",
                            "点开查看进度与成果（通知可在系统设置关闭）", ongoing = false
                        )
                    )
                }
                prevRunning = runningPids
                // 没有任何书在写了 → 服务自行退出（下次启动新书时会重新 onCreate）
                if (runningPids.isEmpty()) stopSelf()
            }
            .launchIn(scope)
    }

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
