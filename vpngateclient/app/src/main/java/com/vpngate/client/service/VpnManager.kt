package com.vpngate.client.service

import android.content.Context
import android.os.Build
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
import com.vpngate.client.data.ServerRepository
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
    private var skipNextDisconnect = false

    private lateinit var appContext: Context
    private fun prefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getProcessName(context: Context): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.app.Application.getProcessName()
        } else {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val processes = am?.runningAppProcesses
            val pid = android.os.Process.myPid()
            processes?.firstOrNull { it.pid == pid }?.processName ?: ""
        }
    }

    fun initialize(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        try {
            val currentProcess = getProcessName(appContext)
            Log.d(TAG, "Initializing OpenVPN Library for process: $currentProcess")
            val app = appContext as android.app.Application

            app.initializeOpenVpnLibrary(notificationFactory = ZenithNotificationFactory(app))

            if (currentProcess != appContext.packageName) {
                Log.d(TAG, "Skipping VpnManager logic for non-main process: $currentProcess")
                return
            }

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
                            startTimeoutTimer()
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
                                tryNextOrKillSwitch()
                            } else {
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
            !enabled -> {
                KillSwitchVpnService.stop(appContext)
                if (currentState == ZenithVpnService.ConnectionState.KILL_SWITCH_ACTIVE) {
                    ZenithVpnService.updateState(ZenithVpnService.ConnectionState.DISCONNECTED)
                }
            }
        }
    }

    fun connect(context: Context, server: VpnServer, failoverCandidates: List<VpnServer> = emptyList()) {
        initialize(context)
        // For stealth/advanced stealth servers, prioritize TCP to bypass firewalls without waiting for UDP timeouts
        val prioritizeTcp = (server.isStealth || server.isAdvanceStealth) && server.method.equals("UDP", ignoreCase = true)

        val primaryServer = if (prioritizeTcp) {
            server.copy(method = "TCP")
        } else {
            server
        }

        val alternate = if (prioritizeTcp) {
            server.copy(method = "UDP")
        } else {
            server.copy(
                method = if (server.method.equals("TCP", ignoreCase = true)) "UDP" else "TCP",
                port = server.port
            )
        }

        val queue = mutableListOf<VpnServer>()
        queue.add(alternate)
        queue.addAll(failoverCandidates)

        failoverQueue = ArrayDeque(queue)
        connectInternal(primaryServer)
    }

    private fun connectInternal(server: VpnServer) {
        Log.d(TAG, "connectInternal: ${server.ip} (${failoverQueue.size} failovers remaining)")
        prefs().edit().putString(KEY_CONNECTED_IP, server.ip).apply()
        ZenithVpnService.updateServerIp(server.ip)

        connectJob?.cancel()
        connectJob = scope.launch {
            try {
                var currentServer = server
                if (currentServer.openVpnConfigBase64.isEmpty()) {
                    Log.d(TAG, "Config empty for server ${server.ip}, fetching from API...")
                    val result = ServerRepository(appContext).fetchConfig(server.ip)
                    val fetched = result.getOrNull()
                    if (fetched != null) {
                        currentServer = fetched.copy(
                            method = server.method,
                            port = server.port
                        )
                    } else {
                        Log.e(TAG, "Failed to fetch config for server ${server.ip}")
                        tryNextOrKillSwitch()
                        return@launch
                    }
                }

                skipNextDisconnect = true
                clientApi?.disconnect("ZenithVpnSession")
                delay(500)

                val rawConfigBytes = Base64.decode(currentServer.openVpnConfigBase64, Base64.DEFAULT)
                val baseConfig = String(rawConfigBytes, Charsets.UTF_8)
                val finalConfig = buildConfig(baseConfig, currentServer)

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
        val cleaned = mutableListOf<String>()

        for (line in baseConfig.lines()) {
            val trimmed = line.trim()
            when {
                trimmed == "persist-tun" -> {}
                trimmed == "remap-usr1-to-sighup" -> {}
                trimmed.startsWith("resolv-retry") -> {}
                trimmed.startsWith("remote ") -> {}
                trimmed.startsWith("proto ") -> {}
                trimmed.startsWith("connect-retry") -> {}
                trimmed.startsWith("connect-timeout") -> {}
                trimmed == "persist-key" -> {}
                else -> cleaned.add(line)
            }
        }

        // Force connection protocol and remote endpoint according to target server configuration
        val protoName = if (server.method.equals("TCP", ignoreCase = true)) "tcp-client" else "udp"
        cleaned.add(0, "proto $protoName")
        cleaned.add(1, "remote ${server.ip} ${server.port}")

        // Add fast fail options to prevent indefinite hangs
        cleaned.add("connect-timeout 8")
        cleaned.add("connect-retry-max 1")
        cleaned.add("resolv-retry 8")

        return cleaned.joinToString("\n")
    }

    fun disconnect(context: Context) {
        initialize(context)
        Log.d(TAG, "disconnect: Requesting tunnel termination...")
        connectJob?.cancel()
        timeoutJob?.cancel()
        failoverQueue.clear()
        skipNextDisconnect = true
        KillSwitchVpnService.stop(appContext)
        ZenithVpnService.updateState(ZenithVpnService.ConnectionState.DISCONNECTED)
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
