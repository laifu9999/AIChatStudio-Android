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
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮球前台服务：任何应用之上常驻一个小球。
 * v1.2：点击 → 弹出菜单（📝 记笔记 / 📖 笔记目录），不再直接开速记页。
 *  - 记笔记：暂停视频 → 弹出速记页（关闭后自动续播）
 *  - 笔记目录：打开主界面，可查看 / 编辑 / 删除 / 分享所有笔记
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
    private var menu: View? = null
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
        removeMenu()
        removeBall()
        super.onDestroy()
    }

    private fun removeBall() {
        try {
            if (ball != null) wm?.removeView(ball)
        } catch (_: Exception) { }
        ball = null
    }

    /** v1.2：点击悬浮球 → 在球旁弹出功能菜单（再点一次球或点菜单外关闭） */
    private fun tapBall() {
        if (menu != null) {
            removeMenu()
            return
        }
        showMenu()
    }

    private fun removeMenu() {
        try {
            if (menu != null) wm?.removeView(menu)
        } catch (_: Exception) { }
        menu = null
    }

    private fun showMenu() {
        if (menu != null) return
        val d = resources.displayMetrics.density
        val dm = resources.displayMetrics

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 16f * d
                setColor(0xF7FFFFFF.toInt())
                setStroke((1f * d).toInt(), 0x22000000)
            }
            elevation = 10f * d
        }

        fun addItem(label: String, sub: String, onClick: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val padH = (16 * d).toInt()
                setPadding(padH, (10 * d).toInt(), padH, (10 * d).toInt())
                val grad = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
                background = grad
                addView(TextView(this@OverlayService).apply {
                    text = label
                    textSize = 15f
                    setTextColor(0xFF20223A.toInt())
                })
                addView(TextView(this@OverlayService).apply {
                    text = sub
                    textSize = 11f
                    setTextColor(0xFF8A8698.toInt())
                })
                setOnClickListener {
                    removeMenu()
                    onClick()
                }
            }
            panel.addView(item)
        }

        addItem("📝 记笔记", "暂停视频，快速记录这一刻") {
            MediaCtl.pause(this)
            openEditor()
        }
        // 分隔线
        panel.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt()
            ).apply { setMargins((14 * d).toInt(), 0, (14 * d).toInt(), 0) }
            background = GradientDrawable().apply { setColor(0xFFE4E2EE.toInt()) }
        })
        addItem("📖 笔记目录", "查看、修改、删除所有笔记") {
            openList()
        }

        // 菜单宽高估算：宽 ~200dp，高 ~96dp
        val menuW = (208 * d).toInt()
        val menuH = (100 * d).toInt()
        val p = WindowManager.LayoutParams(
            menuW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 菜单贴着球弹出：优先球右侧，靠右了就弹左侧；纵向优先球下方，贴底了就弹上方
            val size = (SIZE_DP * d).toInt()
            x = if (params!!.x + size + menuW <= dm.widthPixels) params!!.x + size / 2
            else (params!!.x + size / 2 - menuW).coerceAtLeast(0)
            y = if (params!!.y + size + menuH <= dm.heightPixels) params!!.y + size + (4 * d).toInt()
            else (params!!.y - menuH - (4 * d).toInt()).coerceAtLeast(0)
        }

        // 点菜单外自动收起
        panel.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                removeMenu()
                true
            } else false
        }

        wm?.addView(panel, p)
        menu = panel
    }

    private fun openEditor() {
        val it = Intent(this, EditorActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("fromBall", true)
        try {
            startActivity(it)
        } catch (_: Exception) { }
    }

    /** v1.2：打开主界面（笔记目录：查看 / 编辑 / 删除 / 导出） */
    private fun openList() {
        val it = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            startActivity(it)
        } catch (_: Exception) { }
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
                        // v1.1：每次拖动取实时屏幕尺寸（修横屏拖不到右边——旧代码缓存了竖屏宽度）
                        val dm = resources.displayMetrics
                        val swNow = dm.widthPixels
                        val shNow = dm.heightPixels
                        p.x = (startX + dx).toInt().coerceIn(0, (swNow - size).coerceAtLeast(0))
                        p.y = (startY + dy).toInt().coerceIn(0, (shNow - size).coerceAtLeast(0))
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
}
