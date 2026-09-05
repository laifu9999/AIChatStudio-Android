package com.lele.lelenote

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream

/**
 * 一次性截图前台服务：接收 MediaProjection 授权结果，
 * 延迟约 0.6 秒（等速记页把自己隐藏）后抓一帧屏幕，存 JPEG 并广播给速记页。
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_DONE = "com.lele.lelenote.CAPTURE_DONE"
        private const val CH = "leenote_capture"
        private const val NOTIF_ID = 2
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var vdisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var done = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra("data", Intent::class.java)
        } else {
            intent?.getParcelableExtra("data")
        }
        if (data == null || code != Activity.RESULT_OK) {
            broadcastDone("")
            stopSelf()
            return START_NOT_STICKY
        }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH, "截图", NotificationManager.IMPORTANCE_MIN)
        )
        val notif: Notification =
            androidx.core.app.NotificationCompat.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("正在截图")
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }

        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data)
        } catch (_: Exception) {
            broadcastDone("")
            stopSelf()
            return START_NOT_STICKY
        }
        // 等速记页把自己隐藏后再抓屏
        mainHandler.postDelayed({ startCapture() }, 600)
        return START_NOT_STICKY
    }

    private fun screenSize(): Point {
        val p = Point()
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= 30) {
            val b = w.currentWindowMetrics.bounds
            p.x = b.width()
            p.y = b.height()
        } else {
            @Suppress("DEPRECATION")
            w.defaultDisplay.getRealSize(p)
        }
        return p
    }

    private fun startCapture() {
        val mp = projection ?: return
        if (Build.VERSION.SDK_INT >= 34) {
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { cleanup() }
            }, mainHandler)
        }
        val size = screenSize()
        val w = size.x.coerceAtLeast(1)
        val h = size.y.coerceAtLeast(1)
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ r ->
            if (done) return@setOnImageAvailableListener
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            done = true
            try {
                process(img)
            } catch (_: Exception) {
                broadcastDone("")
            } finally {
                img.close()
            }
            mainHandler.postDelayed({
                cleanup()
                stopSelf()
            }, 200)
        }, mainHandler)
        try {
            vdisplay = mp.createVirtualDisplay(
                "LeleNoteShot", w, h, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface, null, null
            )
        } catch (_: Exception) {
            broadcastDone("")
            stopSelf()
        }
    }

    private fun process(img: Image) {
        val plane = img.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val w = img.width
        val h = img.height
        val rowPadding = rowStride - pixelStride * w
        val full = Bitmap.createBitmap(
            w + (if (pixelStride > 0) rowPadding / pixelStride else 0), h, Bitmap.Config.ARGB_8888
        )
        full.copyPixelsFromBuffer(buffer)
        val bmp = if (full.width == w) full else Bitmap.createBitmap(full, 0, 0, w, h)

        val out: File = NoteStore.newScreenshotFile(this)
        try {
            FileOutputStream(out).use { fos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, fos)
            }
        } catch (_: Exception) {
            broadcastDone("")
            return
        }
        if (bmp !== full) full.recycle()
        bmp.recycle()
        broadcastDone(out.absolutePath)
    }

    private fun broadcastDone(path: String) {
        val it = Intent(ACTION_DONE).setPackage(packageName).putExtra("path", path)
        sendBroadcast(it)
    }

    private fun cleanup() {
        try { vdisplay?.release() } catch (_: Exception) { }
        try { reader?.close() } catch (_: Exception) { }
        try { if (Build.VERSION.SDK_INT >= 34) projection?.stop() } catch (_: Exception) { }
        vdisplay = null
        reader = null
        projection = null
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }
}
