package com.vpngate.client

import android.app.Application
import com.vpngate.client.service.VpnManager

class ZenithVpnApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize the OpenVPN library for all processes (Main and openvpn service processes)
        VpnManager.initialize(this)
    }
}
