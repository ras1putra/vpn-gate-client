package com.vpngate.client.service

import android.content.Context
import android.util.Base64
import android.util.Log
import com.tim.basevpn.vpn.api.VpnClients
import com.tim.basevpn.vpn.api.VpnConfig
import com.tim.basevpn.vpn.api.VpnClientApi
import com.tim.basevpn.vpn.api.VpnClientApiHandle
import com.tim.openvpn.OpenVpnProtocol
import com.tim.openvpn.init.initializeOpenVpnLibrary
import com.vpngate.client.model.VpnServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object VpnManager {
    private const val TAG = "VpnManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var clientHandle: VpnClientApiHandle? = null
    private var clientApi: VpnClientApi? = null
    private var isInitialized = false
    private var timeoutJob: kotlinx.coroutines.Job? = null

    private fun startTimeoutTimer(context: Context) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            kotlinx.coroutines.delay(15000) // 15 seconds connection timeout limit
            if (ZenithVpnService.connectionState.value == ZenithVpnService.ConnectionState.CONNECTING) {
                Log.w(TAG, "Connection attempt timed out. Terminating service...")
                disconnect(context)
                ZenithVpnService.updateState(ZenithVpnService.ConnectionState.ERROR)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        "Connection timed out. Server is offline.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    fun initialize(context: Context) {
        if (isInitialized) return
        try {
            Log.d(TAG, "Initializing OpenVPN Library...")
            val app = context.applicationContext as android.app.Application
            
            app.initializeOpenVpnLibrary(notificationFactory = ZenithNotificationFactory(app))
            clientHandle = VpnClients.createAppScoped(app, OpenVpnProtocol.descriptor)
            clientApi = clientHandle?.api
            
            // Restore saved server IP if we are connected
            val prefs = context.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)
            val savedIp = prefs.getString("connected_server_ip", null)
            ZenithVpnService.updateServerIp(savedIp)

            scope.launch {
                clientApi?.observeState()?.collectLatest { state ->
                    Log.d(TAG, "State callback: status = ${state.status}")
                    val mapped = when (state.status) {
                        com.tim.basevpn.vpn.api.VpnState.Status.CONNECTED -> ZenithVpnService.ConnectionState.CONNECTED
                        com.tim.basevpn.vpn.api.VpnState.Status.CONNECTING -> ZenithVpnService.ConnectionState.CONNECTING
                        com.tim.basevpn.vpn.api.VpnState.Status.DISCONNECTING -> ZenithVpnService.ConnectionState.DISCONNECTED
                        else -> ZenithVpnService.ConnectionState.DISCONNECTED
                    }
                    if (mapped == ZenithVpnService.ConnectionState.CONNECTED ||
                        mapped == ZenithVpnService.ConnectionState.DISCONNECTED ||
                        mapped == ZenithVpnService.ConnectionState.ERROR) {
                        timeoutJob?.cancel()
                    }
                    if (mapped == ZenithVpnService.ConnectionState.DISCONNECTED ||
                        mapped == ZenithVpnService.ConnectionState.ERROR) {
                        ZenithVpnService.updateServerIp(null)
                        prefs.edit().remove("connected_server_ip").apply()
                    }
                    ZenithVpnService.updateState(mapped)
                }
            }
            isInitialized = true
            Log.d(TAG, "OpenVPN Library successfully initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OpenVPN library", e)
        }
    }

    fun prepare(context: Context): android.content.Intent? {
        val intent = android.net.VpnService.prepare(context)
        Log.d(TAG, "prepare: VpnService.prepare = ${if (intent == null) "null (Granted)" else "Intent"}")
        return intent
    }

    fun connect(context: Context, server: VpnServer) {
        initialize(context)
        Log.d(TAG, "connect: Connecting to VpnGate IP = ${server.ip}")
        ZenithVpnService.updateServerIp(server.ip)
        
        val prefs = context.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("connected_server_ip", server.ip).apply()

        startTimeoutTimer(context)
        
        scope.launch {
            try {
                val rawConfigBytes = Base64.decode(server.openVpnConfigBase64, Base64.DEFAULT)
                val ovpnConfigString = String(rawConfigBytes, Charsets.UTF_8)
                
                Log.d(TAG, "Parsed .ovpn config payload size: ${ovpnConfigString.length} chars")
                
                val config = VpnConfig(ovpnConfigString)
                clientApi?.connect(config, "ZenithVpnSession")
            } catch (e: Exception) {
                Log.e(TAG, "Exception during connection launch", e)
                ZenithVpnService.updateState(ZenithVpnService.ConnectionState.ERROR)
            }
        }
    }

    fun disconnect(context: Context) {
        initialize(context)
        Log.d(TAG, "disconnect: Requesting tunnel termination...")
        scope.launch {
            try {
                clientApi?.disconnect("ZenithVpnSession")
                ZenithVpnService.updateServerIp(null)
                val prefs = context.getSharedPreferences("zenith_prefs", Context.MODE_PRIVATE)
                prefs.edit().remove("connected_server_ip").apply()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disconnect cleanly", e)
            }
        }
    }
}
