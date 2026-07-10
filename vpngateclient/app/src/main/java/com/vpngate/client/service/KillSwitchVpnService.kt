package com.vpngate.client.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

class KillSwitchVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        activate()
        return START_STICKY
    }

    private fun activate() {
        try {
            try { tunFd?.close() } catch (e: Exception) { /* fd may already be stale */ }
            tunFd = null

            tunFd = Builder()
                .setSession("Zenith KillSwitch")
                .addAddress("10.99.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .setBlocking(false)
                .establish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish null tunnel", e)
        }
    }

    override fun onDestroy() {
        try { tunFd?.close() } catch (e: Exception) { /* fd may already be stale */ }
        tunFd = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "KillSwitchVpnService"
        const val ACTION_STOP = "com.vpngate.client.KILLSWITCH_STOP"

        fun start(context: Context) {
            context.startService(Intent(context, KillSwitchVpnService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, KillSwitchVpnService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }
    }
}
