package com.vpngate.client.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log

class KillSwitchVpnService : VpnService() {

    private var tunFd: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: action = ${intent?.action}")
        if (intent?.action == ACTION_STOP) {
            Log.d(TAG, "onStartCommand: ACTION_STOP received, releasing tunFd and calling stopSelf()")
            closeTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        Log.d(TAG, "onStartCommand: Activating null tunnel")
        activate()
        return START_STICKY
    }

    private fun activate() {
        try {
            closeTunnel()

            Log.d(TAG, "activate: Establishing null tunnel VPN...")
            tunFd = Builder()
                .setSession("Zenith KillSwitch")
                .addAddress("10.99.0.1", 32)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDisallowedApplication(packageName)
                .setBlocking(false)
                .establish()
            Log.d(TAG, "activate: Null tunnel established successfully (tunFd = $tunFd)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to establish null tunnel", e)
        }
    }

    private fun closeTunnel() {
        try {
            if (tunFd != null) {
                Log.d(TAG, "closeTunnel: closing tunFd ($tunFd)")
                tunFd?.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing tunFd", e)
        }
        tunFd = null
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: releasing resources and calling super.onDestroy()")
        closeTunnel()
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
