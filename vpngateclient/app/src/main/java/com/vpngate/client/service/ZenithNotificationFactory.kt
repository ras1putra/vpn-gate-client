package com.vpngate.client.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tim.basevpn.vpn.api.VpnNotificationFactory
import com.tim.basevpn.vpn.api.VpnNotificationManager
import com.tim.basevpn.vpn.api.VpnNotificationState
import com.tim.basevpn.vpn.api.VpnStopCommand
import com.vpngate.client.MainActivity
import com.vpngate.client.R
import java.util.Locale

class ZenithNotificationFactory(private val context: Context) : VpnNotificationFactory {
    override fun create(service: Service, stopCommand: VpnStopCommand): VpnNotificationManager {
        return ZenithNotificationManagerImpl(service, stopCommand)
    }
}

class ZenithNotificationManagerImpl(
    private val service: Service,
    private val stopCommand: VpnStopCommand
) : VpnNotificationManager {

    private val notificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false
    private var currentState = VpnNotificationState.IDLE
    private var sinceMs = 0L

    // Traffic speed tracking
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastTime = 0L

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            updateNotificationContent()
            handler.postDelayed(this, 1000)
        }
    }

    init {
        activeStopCommand = stopCommand
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Zenith VPN Connection Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active VPN connection speeds and status"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun startNotification() {
        isRunning = true
        sinceMs = System.currentTimeMillis()
        lastRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid())
        lastTxBytes = TrafficStats.getUidTxBytes(android.os.Process.myUid())
        lastTime = System.currentTimeMillis()

        val notification = buildNotification("Starting Zenith VPN...")
        service.startForeground(NOTIFICATION_ID, notification)

        handler.post(updateRunnable)
        Log.d("ZenithNotification", "startNotification: foreground started, update loop active")
    }

    override fun updateNotification(notification: Notification) {
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun updateNotificationForState(state: VpnNotificationState) {
        currentState = state
        if (state == VpnNotificationState.CONNECTED) {
            sinceMs = System.currentTimeMillis()
        }
        updateNotificationContent()
    }

    override fun stopNotification() {
        isRunning = false
        handler.removeCallbacks(updateRunnable)
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
        activeStopCommand = null
        Log.d("ZenithNotification", "stopNotification: loop cancelled and service foreground removed")
    }

    private fun updateNotificationContent() {
        if (!isRunning) return

        val title = when (currentState) {
            VpnNotificationState.IDLE -> "Zenith VPN: Idle"
            VpnNotificationState.CONNECTING -> "Zenith VPN: Connecting..."
            VpnNotificationState.CONNECTED -> "Zenith VPN: Connected"
            VpnNotificationState.DISCONNECTING -> "Zenith VPN: Disconnecting..."
        }

        val content = if (currentState == VpnNotificationState.CONNECTED) {
            val elapsedSec = (System.currentTimeMillis() - sinceMs) / 1000
            val durationStr = formatDuration(elapsedSec)

            val currentRx = TrafficStats.getUidRxBytes(android.os.Process.myUid())
            val currentTx = TrafficStats.getUidTxBytes(android.os.Process.myUid())
            val currentTime = System.currentTimeMillis()

            val timeDiffSec = (currentTime - lastTime) / 1000.0
            val dlSpeed = if (timeDiffSec > 0 && currentRx >= lastRxBytes) {
                (currentRx - lastRxBytes) / timeDiffSec
            } else 0.0
            val ulSpeed = if (timeDiffSec > 0 && currentTx >= lastTxBytes) {
                (currentTx - lastTxBytes) / timeDiffSec
            } else 0.0

            lastRxBytes = currentRx
            lastTxBytes = currentTx
            lastTime = currentTime

            val speedStr = "Down: ${formatSpeed(dlSpeed)}  •  Up: ${formatSpeed(ulSpeed)}"
            "Duration: $durationStr  •  $speedStr"
        } else {
            "Securing your internet connection..."
        }

        val notification = buildNotification(title, content)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(title: String, content: String = ""): Notification {
        val intent = Intent(service, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            service, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(service, ZenithDisconnectReceiver::class.java)
        val disconnectPendingIntent = PendingIntent.getBroadcast(
            service, 1, disconnectIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (currentState == VpnNotificationState.CONNECTED || currentState == VpnNotificationState.CONNECTING) {
            builder.addAction(
                R.drawable.ic_notification,
                "Disconnect",
                disconnectPendingIntent
            )
        }

        return builder.build()
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024)
            else -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
        }
    }

    companion object {
        private const val CHANNEL_ID = "zenith_vpn_channel"
        private const val NOTIFICATION_ID = 10101
        
        @Volatile
        var activeStopCommand: VpnStopCommand? = null
    }
}
