package com.nfriendcl.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * 자동화가 도는 동안 프로세스를 살려 두고 CPU 를 깨워 두는 포그라운드 서비스.
 *
 * - 부분 WakeLock(PARTIAL_WAKE_LOCK) 으로 화면이 꺼져도 CPU 가 잠들지 않게 한다.
 * - 포그라운드 서비스 + 상시 알림으로 홈 이동/화면 꺼짐 뒤에도 OS 가 프로세스를
 *   쉽게 죽이지 못하게 해 백그라운드 지속 실행을 돕는다.
 * WebView 자체는 MainActivity 가 계속 들고 있으며, 이 서비스는 수명만 붙잡는다.
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        acquireLock()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun acquireLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "nfriendcl:automation").apply {
            setReferenceCounted(false)
            // 무한 점유 방지용 안전 상한(최대 6시간). 서비스 종료 시 즉시 해제.
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "자동화 실행 중",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            mgr.createNotificationChannel(channel)
        }
        @Suppress("DEPRECATION")
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("N FriendCl 자동화 진행 중")
            .setContentText("화면을 꺼도 백그라운드에서 계속 실행됩니다")
            .setSmallIcon(R.drawable.ic_launcher_n)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "nfriendcl_automation"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            val intent = Intent(ctx, KeepAliveService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, KeepAliveService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
