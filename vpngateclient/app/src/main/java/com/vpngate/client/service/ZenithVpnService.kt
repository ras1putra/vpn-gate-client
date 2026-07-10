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

        private val _isKillSwitchEnabled = MutableStateFlow(false)
        val isKillSwitchEnabled: StateFlow<Boolean> = _isKillSwitchEnabled

        fun updateState(state: ConnectionState) {
            _connectionState.value = state
        }

        fun updateServerIp(ip: String?) {
            _connectedServerIp.value = ip
        }

        fun setKillSwitchEnabled(enabled: Boolean) {
            _isKillSwitchEnabled.value = enabled
        }
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR,
        KILL_SWITCH_ACTIVE
    }
}
