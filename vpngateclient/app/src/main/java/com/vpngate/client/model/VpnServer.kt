package com.vpngate.client.model

import com.squareup.moshi.Json

enum class ServerType {
    RESIDENTIAL,
    ACADEMIC,
    DATACENTER
}

data class VpnServer(
    val hostName: String,
    val ip: String,
    val port: Int = 1194,
    val score: Long,
    val ping: Int,
    val speed: Long,
    val countryLong: String,
    val countryShort: String,
    val operator: String,
    @Json(name = "openVpnConfigBase64")
    val openVpnConfigBase64: String,
    val serverType: ServerType = ServerType.RESIDENTIAL,
    val uptime: String = "Unknown",
    val method: String = "UDP",
    val vpnDetected: Boolean? = null,
    val vpnChecked: Boolean = false,
    val vpngateFlagged: Boolean? = null,
    val isStealth: Boolean = false,
    val isAdvanceStealth: Boolean = false
) {
    val speedMbs: Double
        get() = speed.toDouble() / 1_000_000.0

    val isResidential: Boolean
        get() = serverType == ServerType.RESIDENTIAL

    val formattedUptime: String
        get() {
            val raw = uptime.toLongOrNull() ?: return uptime
            val seconds = raw / 1000L
            val days = seconds / 86400
            val hours = (seconds % 86400) / 3600
            val mins = (seconds % 3600) / 60
            return when {
                days > 0 -> "${days}d ${hours}h"
                hours > 0 -> "${hours}h ${mins}m"
                else -> "${mins}m"
            }
        }
}
