package com.vpngate.client.service

import android.content.Context
import android.util.Base64
import android.util.Log
import com.tim.basevpn.vpn.api.VpnClientApi
import com.tim.basevpn.vpn.api.VpnClientApiHandle
import com.tim.basevpn.vpn.api.VpnClients
import com.tim.basevpn.vpn.api.VpnConfig
import com.tim.basevpn.vpn.api.VpnState
import com.tim.openvpn.OpenVpnProtocol
import com.tim.openvpn.init.initializeOpenVpnLibrary
import com.vpngate.client.model.VpnServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

object VpnManager {
    private const val TAG = "VpnManager"
    private const val PREFS_NAME = "zenith_prefs"
    private const val KEY_CONNECTED_IP = "connected_server_ip"
    private const val KEY_KILL_SWITCH = "kill_switch_enabled"
    private const val CONNECT_TIMEOUT_MS = 15_000L

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var clientHandle: VpnClientApiHandle? = null
    private var clientApi: VpnClientApi? = null
    private var isInitialized = false

    private var connectJob: Job? = null
    private var timeoutJob: Job? = null
    private var failoverQueue: ArrayDeque<VpnServer> = ArrayDeque()

    // Set to true before we call clientApi.disconnect() ourselves (during reconnect/failover)
    // so the resulting DISCONNECTED event from the library does not trigger failover.
    private var skipNextDisconnect = false

    private lateinit var appContext: Context
    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun initialize(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        try {
            Log.d(TAG, "Initializing OpenVPN Library...")
            val app = appContext as android.app.Application

            app.initializeOpenVpnLibrary(notificationFactory = ZenithNotificationFactory(app))
            clientHandle = VpnClients.createAppScoped(app, OpenVpnProtocol.descriptor)
            clientApi = clientHandle?.api

            val savedIp = prefs().getString(KEY_CONNECTED_IP, null)
            val killSwitchEnabled = prefs().getBoolean(KEY_KILL_SWITCH, false)

            ZenithVpnService.updateServerIp(savedIp)
            ZenithVpnService.setKillSwitchEnabled(killSwitchEnabled)

            if (killSwitchEnabled && savedIp == null) {
                KillSwitchVpnService.start(appContext)
                ZenithVpnService.updateState(ZenithVpnService.ConnectionState.KILL_SWITCH_ACTIVE)
            }

            scope.launch {
                clientApi?.observeState()?.collectLatest { state ->
                    Log.d(TAG, "State callback: status = ${state.status}")

                    val mapped = when (state.status) {
                        VpnState.Status.CONNECTED -> ZenithVpnService.ConnectionState.CONNECTED
                        VpnState.Status.CONNECTING -> ZenithVpnService.ConnectionState.CONNECTING
                        VpnState.Status.DISCONNECTING -> return@collectLatest
                        VpnState.Status.DISCONNECTED -> ZenithVpnService.ConnectionState.DISCONNECTED
                        // IPC init states (CONNECTING_IPC, etc.) and any other transient states
                        // are not terminal — ignore them.
                        else -> return@collectLatest
                    }

                    when (mapped) {
                        ZenithVpnService.ConnectionState.CONNECTED -> {
                            timeoutJob?.cancel()
                            KillSwitchVpnService.stop(appContext)
                            ZenithVpnService.updateState(mapped)
                        }

                        ZenithVpnService.ConnectionState.CONNECTING -> {
                            KillSwitchVpnService.stop(appContext)
                            ZenithVpnService.updateState(mapped)
                        }

                        ZenithVpnService.ConnectionState.DISCONNECTED -> {
                            if (skipNextDisconnect) {
                                skipNextDisconnect = false
                                return@collectLatest
                            }

                            val wasConnecting = ZenithVpnService.connectionState.value ==
                                ZenithVpnService.ConnectionState.CONNECTING

                            timeoutJob?.cancel()

                            if (wasConnecting) {
                                // Active connection attempt failed — try next failover server.
                                tryNextOrKillSwitch()
                            } else {
                                // Clean disconnect from CONNECTED or startup initial state.
                                ZenithVpnService.updateServerIp(null)
                                prefs().edit().remove(KEY_CONNECTED_IP).apply()
                                if (ZenithVpnService.isKillSwitchEnabled.value) {
                                    KillSwitchVpnService.start(appContext)
                                    ZenithVpnService.updateState(
                                        ZenithVpnService.ConnectionState.KILL_SWITCH_ACTIVE
                                    )
                                } else {
                                    ZenithVpnService.updateState(mapped)
                                }
                            }
                        }

                        else -> ZenithVpnService.updateState(mapped)
                    }
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

    fun setKillSwitch(context: Context, enabled: Boolean) {
        initialize(context)
        prefs().edit().putBoolean(KEY_KILL_SWITCH, enabled).apply()
        ZenithVpnService.setKillSwitchEnabled(enabled)
        Log.d(TAG, "Kill switch set to: $enabled")

        val currentState = ZenithVpnService.connectionState.value
        when {
            enabled && (currentState == ZenithVpnService.ConnectionState.DISCONNECTED ||
                        currentState == ZenithVpnService.ConnectionState.ERROR) -> {
                KillSwitchVpnService.start(appContext)
                ZenithVpnService.updateState(ZenithVpnService.ConnectionState.KILL_SWITCH_ACTIVE)
            }
            !enabled && currentState == ZenithVpnService.ConnectionState.KILL_SWITCH_ACTIVE -> {
                KillSwitchVpnService.stop(appContext)
                ZenithVpnService.updateState(ZenithVpnService.ConnectionState.DISCONNECTED)
            }
        }
    }

    fun connect(context: Context, server: VpnServer, failoverCandidates: List<VpnServer> = emptyList()) {
        initialize(context)
        failoverQueue = ArrayDeque(failoverCandidates)
        connectInternal(server)
    }

    private fun connectInternal(server: VpnServer) {
        Log.d(TAG, "connectInternal: ${server.ip} (${failoverQueue.size} failovers remaining)")
        prefs().edit().putString(KEY_CONNECTED_IP, server.ip).apply()
        ZenithVpnService.updateServerIp(server.ip)

        connectJob?.cancel()
        connectJob = scope.launch {
            try {
                // Prevent the library's DISCONNECTED callback (from our own disconnect() below)
                // from triggering tryNextOrKillSwitch() prematurely.
                skipNextDisconnect = true
                clientApi?.disconnect("ZenithVpnSession")
                delay(500)

                val rawConfigBytes = Base64.decode(server.openVpnConfigBase64, Base64.DEFAULT)
                val baseConfig = String(rawConfigBytes, Charsets.UTF_8)
                val finalConfig = buildConfig(baseConfig, server)

                Log.d(TAG, "Config built (${finalConfig.length} chars)")

                ZenithVpnService.updateState(ZenithVpnService.ConnectionState.CONNECTING)
                startTimeoutTimer()

                val api = clientApi ?: run {
                    Log.e(TAG, "clientApi is null")
                    skipNextDisconnect = false
                    ZenithVpnService.updateState(ZenithVpnService.ConnectionState.ERROR)
                    return@launch
                }

                api.connect(VpnConfig(finalConfig), "ZenithVpnSession")
            } catch (e: Exception) {
                Log.e(TAG, "Exception during connection", e)
                skipNextDisconnect = false
                ZenithVpnService.updateState(ZenithVpnService.ConnectionState.ERROR)
            }
        }
    }

    private fun tryNextOrKillSwitch() {
        val next = failoverQueue.removeFirstOrNull()
        if (next != null) {
            Log.d(TAG, "Failing over to ${next.ip}")
            connectInternal(next)
        } else {
            Log.d(TAG, "All servers exhausted")
            ZenithVpnService.updateServerIp(null)
            prefs().edit().remove(KEY_CONNECTED_IP).apply()
            if (ZenithVpnService.isKillSwitchEnabled.value) {
                KillSwitchVpnService.start(appContext)
                ZenithVpnService.updateState(ZenithVpnService.ConnectionState.KILL_SWITCH_ACTIVE)
            } else {
                ZenithVpnService.updateState(ZenithVpnService.ConnectionState.DISCONNECTED)
            }
        }
    }

    private fun startTimeoutTimer() {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            if (ZenithVpnService.connectionState.value == ZenithVpnService.ConnectionState.CONNECTING) {
                Log.w(TAG, "Connection timed out")
                tryNextOrKillSwitch()
            }
        }
    }

    private fun buildConfig(baseConfig: String, server: VpnServer): String {
        val sb = StringBuilder()
        sb.appendLine("persist-tun")
        sb.appendLine("remap-usr1-to-sighup")
        sb.appendLine()

        val hasRemote = baseConfig.lines().any { it.trim().startsWith("remote ") }
        if (!hasRemote) {
            sb.appendLine("remote ${server.ip} ${server.port}")
        }

        sb.appendLine()
        sb.append(baseConfig)

        val cleaned = mutableListOf<String>()
        var seenPersistTun = false
        var seenRemapUsr1 = false

        for (line in sb.toString().lines()) {
            val trimmed = line.trim()
            when {
                trimmed == "persist-tun" && !seenPersistTun -> { seenPersistTun = true; cleaned.add(line) }
                trimmed == "persist-tun" -> {}
                trimmed == "remap-usr1-to-sighup" && !seenRemapUsr1 -> { seenRemapUsr1 = true; cleaned.add(line) }
                trimmed == "remap-usr1-to-sighup" -> {}
                trimmed.startsWith("resolv-retry") -> {}
                else -> cleaned.add(line)
            }
        }

        return cleaned.joinToString("\n")
    }

    fun disconnect(context: Context) {
        initialize(context)
        Log.d(TAG, "disconnect: Requesting tunnel termination...")
        connectJob?.cancel()
        timeoutJob?.cancel()
        failoverQueue.clear()
        skipNextDisconnect = false
        scope.launch {
            try {
                clientApi?.disconnect("ZenithVpnSession")
                ZenithVpnService.updateServerIp(null)
                prefs().edit().remove(KEY_CONNECTED_IP).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disconnect cleanly", e)
            }
        }
    }
}
