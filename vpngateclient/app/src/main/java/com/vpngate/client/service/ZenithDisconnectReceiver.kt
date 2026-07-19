package com.vpngate.client.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ZenithDisconnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("ZenithDisconnectReceiver", "Disconnect broadcast received from notification.")
        VpnManager.disconnect(context)
    }
}
