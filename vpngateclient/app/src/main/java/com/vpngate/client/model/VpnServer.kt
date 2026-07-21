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
    val vpngateFlagged: Boolean? = null
) {
    val speedMbs: Double
        get() = speed.toDouble() / 1_000_000.0

    val isResidential: Boolean
        get() = serverType == ServerType.RESIDENTIAL

    val isStealth: Boolean
        get() = vpngateFlagged != true && serverType == ServerType.RESIDENTIAL

    val formattedUptime: String
        get() {
            val seconds = uptime.toLongOrNull() ?: return uptime
            val days = seconds / 86400
            val hours = (seconds % 86400) / 3600
            return when {
                days > 0 -> "${days}d ${hours}h"
                hours > 0 -> "${hours}h"
                else -> "< 1h"
            }
        }
}
