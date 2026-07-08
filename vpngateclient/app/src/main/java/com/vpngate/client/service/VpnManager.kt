package com.vpngate.client.service

import android.content.Context
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.tim.basevpn.vpn.api.VpnClientApi
import com.tim.basevpn.vpn.api.VpnClientApiHandle
import com.tim.basevpn.vpn.api.VpnClients
import com.tim.basevpn.vpn.api.VpnConfig
import com.tim.basevpn.vpn.api.VpnState
import com.tim.openvpn.OpenVpnProtocol
import com.tim.openvpn.init.initializeOpenVpnLibrary
import com.vpngate.client.data.ServerRepository
import com.vpngate.client.model.VpnServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object VpnManager {
    private const val TAG = "VpnManager"
    private const val PREFS_NAME = "zenith_prefs"
    private const val KEY_CONNECTED_IP = "connected_server_ip"
    private const val CONNECT_TIMEOUT_MS = 15_000L

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var clientHandle: VpnClientApiHandle? = null
    private var clientApi: VpnClientApi? = null
    private var isInitialized = false
    private var timeoutJob: Job? = null
    private var appContext: Context? = null

    private var failoverCandidates: List<VpnServer> = emptyList()
    private var nextFailoverIndex = 0

    private val _failoverState = MutableStateFlow(FailoverState())
    val failoverState: StateFlow<FailoverState> = _failoverState

    data class FailoverState(
        val isFailover: Boolean = false,
        val current: Int = 0,
        val total: Int = 0,
        val allFailed: Boolean = false
    )

    fun setFailoverCandidates(candidates: List<VpnServer>) {
        failoverCandidates = candidates
        nextFailoverIndex = 0
        _failoverState.value = FailoverState()
    }

    private fun isKillSwitchEnabled(context: Context): Boolean {
        return try {
            val alwaysOn = Settings.Secure.getString(
                context.contentResolver,
                "always_on_vpn_app"
            )
            alwaysOn == context.packageName
        } catch (_: Exception) {
            false
        }
    }

    private fun startTimeoutTimer(context: Context) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (ZenithVpnService.connectionState.value == ZenithVpnService.ConnectionState.CONNECTING) {
                Log.w(TAG, "Connection attempt timed out")
                ZenithVpnService.updateState(ZenithVpnService.ConnectionState.ERROR)
                handleConnectionFailed(context)
            }
        }
    }

    private fun handleConnectionFailed(context: Context) {
        if (isKillSwitchEnabled(context) && nextFailoverIndex < failoverCandidates.size) {
            val nextServer = failoverCandidates[nextFailoverIndex]
            nextFailoverIndex++
            _failoverState.value = FailoverState(
                isFailover = true,
                current = nextFailoverIndex,
                total = failoverCandidates.size
            )
            Log.d(TAG, "Failover: trying ${nextServer.ip} ($nextFailoverIndex/${failoverCandidates.size})")
            connect(context, nextServer)
        } else {
            _failoverState.value = FailoverState(allFailed = true)
            disconnect(context)
            Log.w(TAG, "All failover attempts failed or kill switch off")
        }
    }

    fun initialize(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        try {
            Log.d(TAG, "Initializing OpenVPN Library...")
            val app = context.applicationContext as android.app.Application

            app.initializeOpenVpnLibrary(notificationFactory = ZenithNotificationFactory(app))
            clientHandle = VpnClients.createAppScoped(app, OpenVpnProtocol.descriptor)
            clientApi = clientHandle?.api

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedIp = prefs.getString(KEY_CONNECTED_IP, null)
            ZenithVpnService.updateServerIp(savedIp)

            scope.launch {
                clientApi?.observeState()?.collectLatest { state ->
                    Log.d(TAG, "State callback: status = ${state.status}")
                    val previousState = ZenithVpnService.connectionState.value
                    val mapped = when (state.status) {
                        VpnState.Status.CONNECTED -> ZenithVpnService.ConnectionState.CONNECTED
                        VpnState.Status.CONNECTING -> ZenithVpnService.ConnectionState.CONNECTING
                        VpnState.Status.DISCONNECTING -> ZenithVpnService.ConnectionState.DISCONNECTED
                        else -> ZenithVpnService.ConnectionState.DISCONNECTED
                    }
                    if (mapped == ZenithVpnService.ConnectionState.CONNECTED ||
                        mapped == ZenithVpnService.ConnectionState.DISCONNECTED ||
                        mapped == ZenithVpnService.ConnectionState.ERROR) {
                        timeoutJob?.cancel()
                    }
                    if (mapped == ZenithVpnService.ConnectionState.CONNECTED) {
                        failoverCandidates = emptyList()
                        nextFailoverIndex = 0
                        _failoverState.value = FailoverState()
                    }
                    if (mapped == ZenithVpnService.ConnectionState.DISCONNECTED &&
                        previousState == ZenithVpnService.ConnectionState.CONNECTED) {
                        appContext?.let { ctx ->
                            if (isKillSwitchEnabled(ctx)) {
                                Log.w(TAG, "VPN disconnected with kill switch on, attempting failover")
                                handleConnectionFailed(ctx)
                                return@collectLatest
                            }
                        }
                    }
                    if (mapped == ZenithVpnService.ConnectionState.DISCONNECTED ||
                        mapped == ZenithVpnService.ConnectionState.ERROR) {
                        ZenithVpnService.updateServerIp(null)
                        prefs.edit().remove(KEY_CONNECTED_IP).apply()
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
        Log.d(TAG, "connect: Connecting to ${server.ip}")
        ZenithVpnService.updateServerIp(server.ip)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONNECTED_IP, server.ip).apply()

        timeoutJob?.cancel()

        scope.launch {
            try {
                clientApi?.disconnect("ZenithVpnSession")
                delay(500)

                val rawConfigBytes = Base64.decode(server.openVpnConfigBase64, Base64.DEFAULT)
                val ovpnConfigString = String(rawConfigBytes, Charsets.UTF_8)

                Log.d(TAG, "Parsed .ovpn config payload size: ${ovpnConfigString.length} chars")

                ZenithVpnService.updateState(ZenithVpnService.ConnectionState.CONNECTING)
                startTimeoutTimer(context)

                val api = clientApi
                if (api == null) {
                    Log.e(TAG, "clientApi is null, cannot connect")
                    ZenithVpnService.updateState(ZenithVpnService.ConnectionState.ERROR)
                    handleConnectionFailed(context)
                    return@launch
                }

                val config = VpnConfig(ovpnConfigString)
                api.connect(config, "ZenithVpnSession")
            } catch (e: Exception) {
                Log.e(TAG, "Exception during connection launch", e)
                ZenithVpnService.updateState(ZenithVpnService.ConnectionState.ERROR)
                handleConnectionFailed(context)
            }
        }
    }

    fun disconnect(context: Context) {
        initialize(context)
        Log.d(TAG, "disconnect: Requesting tunnel termination...")
        timeoutJob?.cancel()
        failoverCandidates = emptyList()
        nextFailoverIndex = 0
        _failoverState.value = FailoverState()
        scope.launch {
            try {
                clientApi?.disconnect("ZenithVpnSession")
                ZenithVpnService.updateServerIp(null)
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().remove(KEY_CONNECTED_IP).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disconnect cleanly", e)
            }
        }
    }
}
