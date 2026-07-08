package com.vpngate.client.service

import android.net.VpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ZenithVpnService : VpnService() {
    companion object {
        private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
        val connectionState: StateFlow<ConnectionState> = _connectionState

        private val _connectedServerIp = MutableStateFlow<String?>(null)
        val connectedServerIp: StateFlow<String?> = _connectedServerIp

        fun updateState(state: ConnectionState) {
            _connectionState.value = state
        }

        fun updateServerIp(ip: String?) {
            _connectedServerIp.value = ip
        }
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }
}
