package com.matchconsole.sender

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.matchconsole.common.Logx
import com.matchconsole.common.Notifications

/**
 * 发送端屏幕采集前台服务。
 *
 * 关键时序（Android 14+ 强制要求）：
 *   1. Activity 通过 createScreenCaptureIntent() 拿到用户授权
 *   2. 用授权结果启动本服务
 *   3. 本服务 startForeground(type = mediaProjection)
 *   4. 之后才允许调用 MediaProjectionManager.getMediaProjection()
 *      （由 SenderController.attachProjection -> ScreenCapturerAndroid 触发）
 *
 * 顺序颠倒会在 Android 14 上直接抛 SecurityException。
 */
class ScreenCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> {
                Logx.i("收到停止采集指令")
                stopSelfSafely()
            }
            else -> {
                Logx.w("未知的服务指令: ${intent?.action}")
                stopSelfSafely()
            }
        }
        // 不使用 STICKY：进程被杀后不应在无授权的情况下自行重启
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val permissionData: Intent? = IntentCompat.getParcelableExtra(
            intent, EXTRA_RESULT_DATA, Intent::class.java
        )

        // 第 3 步：必须先进入前台，再申请 MediaProjection
        goForeground()

        if (resultCode != Activity.RESULT_OK || permissionData == null) {
            Logx.w("屏幕采集授权无效，停止服务")
            stopSelfSafely()
            return
        }

        // 第 4 步
        SenderController.attachProjection(this, permissionData)
    }

    private fun goForeground() {
        val notification = Notifications.buildCaptureNotification(
            this,
            "正在采集屏幕画面并推流到接收端"
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.NOTIF_ID_CAPTURE, notification, type)
    }

    override fun onDestroy() {
        Logx.i("ScreenCaptureService onDestroy，回收采集资源")
        SenderController.onServiceDestroyed()
        super.onDestroy()
    }

    private fun stopSelfSafely() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val ACTION_START = "com.matchconsole.action.START_CAPTURE"
        private const val ACTION_STOP = "com.matchconsole.action.STOP_CAPTURE"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"

        /** 传入 MediaProjection 授权结果，启动前台服务。 */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}