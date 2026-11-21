package com.shadowai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * AI 모델은 메모리를 많이 써서, 잠깐 다른 앱에 다녀오는 사이 시스템이 이 앱을 꺼 버리기 쉽다.
 * 모델이 올라와 있는 동안 알림을 띄워 두면 시스템이 앱을 살려 둔다.
 * 최근 앱 목록에서 앱을 닫으면 같이 꺼진다.
 */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "AI 모델 유지", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("쉐도잉 AI")
            .setContentText("AI 모델을 준비해 두고 있어요. 최근 앱 목록에서 앱을 닫으면 꺼져요.")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, notification)
            }
        } catch (_: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "keep"
    }
}

object KeepAlive {
    /** 앱이 화면에 보일 때만 시작할 수 있다. 뒤에 있을 때 부르면 조용히 넘어간다. */
    fun start(ctx: Context) {
        try {
            ctx.startForegroundService(Intent(ctx, KeepAliveService::class.java))
        } catch (_: Exception) {
        }
    }
}
