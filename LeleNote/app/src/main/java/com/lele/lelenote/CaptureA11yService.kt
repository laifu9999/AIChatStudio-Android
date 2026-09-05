package com.lele.lelenote

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import java.io.FileOutputStream

/**
 * 免授权截图：用户在系统设置里开启本无障碍服务一次后，
 * 之后每次截图直接 takeScreenshot() 全屏抓帧，不再弹 MediaProjection 授权、不用选共享目标。
 * API 30+（Android 11+）可用；服务本身不监听任何用户操作，只提供截图能力。
 */
class CaptureA11yService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CaptureA11yService? = null
            private set

        /** 截图并落盘，回调 path（空串=失败/服务未开启） */
        fun capture(onDone: (String) -> Unit) {
            val svc = instance
            if (svc == null || Build.VERSION.SDK_INT < 30) {
                onDone("")
                return
            }
            svc.takeShot(onDone)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun takeShot(onDone: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            onDone("")
            return
        }
        try {
            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(
                                result.hardwareBuffer, result.colorSpace
                            )
                            val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            result.hardwareBuffer.close()
                            if (soft == null) {
                                onDone("")
                                return
                            }
                            val f = NoteStore.newScreenshotFile(applicationContext)
                            FileOutputStream(f).use { fos ->
                                soft.compress(Bitmap.CompressFormat.JPEG, 88, fos)
                            }
                            soft.recycle()
                            onDone(f.absolutePath)
                        } catch (_: Exception) {
                            onDone("")
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        onDone("")
                    }
                }
            )
        } catch (_: Exception) {
            onDone("")
        }
    }
}
