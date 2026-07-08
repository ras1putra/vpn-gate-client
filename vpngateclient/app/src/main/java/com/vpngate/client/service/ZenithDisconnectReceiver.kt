package com.vpngate.client.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ZenithDisconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("ZenithDisconnectReceiver", "Disconnect broadcast received from notification.")
        val command = ZenithNotificationManagerImpl.activeStopCommand
        if (command != null) {
            Log.d("ZenithDisconnectReceiver", "Stopping via library VpnStopCommand.")
            command.stop()
        } else {
            Log.d("ZenithDisconnectReceiver", "Fallback: Stopping via VpnManager.disconnect.")
            VpnManager.disconnect(context)
        }
    }
}
