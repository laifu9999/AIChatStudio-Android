package com.lele.lelenote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 悬浮球前台服务：任何应用之上常驻一个小球。
 * 点击 → 暂停视频 → 弹出速记页（速记页关闭后自动续播）。
 * 可拖动换位；速记页打开时通过 ACTION_HIDE/ACTION_SHOW 控制隐藏。
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_HIDE = "com.lele.lelenote.BALL_HIDE"
        const val ACTION_SHOW = "com.lele.lelenote.BALL_SHOW"
        private const val CH = "leenote_overlay"
        private const val NOTIF_ID = 1
        private const val SIZE_DP = 54
    }

    private var ball: View? = null
    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH, "悬浮球", NotificationManager.IMPORTANCE_MIN)
        )
        val notif: Notification =
            androidx.core.app.NotificationCompat.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("乐乐速记悬浮球")
                .setContentText("点球速记，视频自动暂停 / 续播")
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        addBall()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> ball?.visibility = View.INVISIBLE
            ACTION_SHOW -> ball?.visibility = View.VISIBLE
        }
        return START_STICKY
    }

    override fun onDestroy() {
        removeBall()
        super.onDestroy()
    }

    private fun removeBall() {
        try {
            if (ball != null) wm?.removeView(ball)
        } catch (_: Exception) { }
        ball = null
    }

    private fun addBall() {
        if (ball != null) return
        val d = resources.displayMetrics.density
        val size = (SIZE_DP * d).toInt()
        val view = TextView(this).apply {
            text = "📝"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC3949AB.toInt())
                setStroke((1.5f * d).toInt(), 0x66FFFFFF)
            }
        }
        val p = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 30
            y = (resources.displayMetrics.heightPixels * 0.35f).toInt()
        }

        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        view.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downRawX
                    val dy = ev.rawY - downRawY
                    if (moved || dx * dx + dy * dy > 12f * 12f * d * d) {
                        moved = true
                        p.x = (startX + dx).toInt().coerceIn(0, (sw - size).coerceAtLeast(0))
                        p.y = (startY + dy).toInt().coerceIn(0, (sh - size).coerceAtLeast(0))
                        try { wm?.updateViewLayout(view, p) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (ev.actionMasked == MotionEvent.ACTION_UP && !moved) tapBall()
                    true
                }
                else -> false
            }
        }

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        params = p
        wm?.addView(view, p)
        ball = view
    }

    private fun tapBall() {
        MediaCtl.pause(this)
        val it = Intent(this, EditorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("fromBall", true)
        try {
            startActivity(it)
        } catch (_: Exception) { }
    }
}
